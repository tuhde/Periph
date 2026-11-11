package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SH1106 OLED controller-driver — full driver.
 *
 * Extends [SH1106Minimal] with drawing primitives, contrast, sleep, invert, all-on test, flip, hardware scroll
 * (display start line), display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOM
 * level, pump voltage, DC-DC control, status and RAM reads (I2C only) and raw commands.
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 8..64
 * @param columnOffset first RAM column shown (2 for 128-pixel modules)
 * @param internalDcdc true for the internal charge pump, false for an external VPP
 */
open class SH1106Full protected constructor(
    bus: SH1106Bus,
    width: Int,
    height: Int,
    columnOffset: Int,
    internalDcdc: Boolean,
) : SH1106Minimal(bus, width, height, columnOffset, internalDcdc) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 250 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        columnOffset: Int = 2,
        internalDcdc: Boolean = true,
    ) : this(SH1106I2cBus(connection), width, height, columnOffset, internalDcdc)

    // ---- framebuffer ----------------------------------------------------------------------

    /** Return a framebuffer pixel; out-of-range coordinates read as false. */
    fun getPixel(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return (fb[(y shr 3) * width + x].toInt() and (1 shl (y and 7))) != 0
    }

    /** Set (or clear) the whole framebuffer. */
    fun fill(on: Boolean) {
        fb.fill(if (on) 0xFF.toByte() else 0)
    }

    /** Draw a Bresenham line into the framebuffer. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, on: Boolean) {
        var x = x0
        var y = y0
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            setPixel(x, y, on)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Draw a rectangle outline or a filled rectangle into the framebuffer. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, on: Boolean, fill: Boolean) {
        if (w <= 0 || h <= 0) return
        if (fill) {
            for (yy in y until y + h) for (xx in x until x + w) setPixel(xx, yy, on)
            return
        }
        drawLine(x, y, x + w - 1, y, on)
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, on)
        drawLine(x, y, x, y + h - 1, on)
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, on)
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. [columns] holds
     * [heightPages] groups of columns, page by page: byte `p * n + c` is column `c` of page row `p`
     * (n = size / heightPages). The bitmap is clipped to the framebuffer; [y] must be a multiple of 8.
     *
     * @throws IllegalArgumentException if y is not a multiple of 8
     */
    fun blit(x: Int, y: Int, columns: ByteArray, heightPages: Int) {
        require(y % 8 == 0 && heightPages > 0) { "y must be a multiple of 8" }
        val n = columns.size / heightPages
        for (p in 0 until heightPages) {
            val page = y / 8 + p
            if (page < 0 || page >= pages) continue
            for (c in 0 until n) {
                val xx = x + c
                if (xx in 0 until width) fb[page * width + xx] = columns[p * n + c]
            }
        }
    }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages are ignored.
     *
     * @throws IOException if a transfer fails
     */
    fun showPage(page: Int) {
        if (page in 0 until pages) showPage0(page)
    }

    // ---- display control ------------------------------------------------------------------

    /** Set the segment output current (contrast), 0..255 (reset 0x80). */
    fun setContrast(value: Int) = command(CMD_CONTRAST, value and 0xFF)

    /** Switch the display on or off (RAM and settings are kept). */
    fun setDisplay(on: Boolean) = command(CMD_DISPLAY or (if (on) 1 else 0))

    /** Display off; the DC-DC stops, RAM and settings are kept and stay writable. */
    fun sleep() = setDisplay(false)

    /** Display on, then wait 100 ms for VPP when the internal DC-DC is used. */
    fun wake() {
        setDisplay(true)
        sleepMs(if (useDcdc) 100 else 50)
    }

    /** Invert the displayed image without changing RAM. */
    fun setInvert(on: Boolean) = command(CMD_INVERT or (if (on) 1 else 0))

    /** Force all pixels on (test mode, RAM unchanged). */
    fun setAllOn(on: Boolean) = command(CMD_ALL_ON or (if (on) 1 else 0))

    /** Mirror columns (segment re-map) and/or rows (COM scan direction); the framebuffer is unchanged. */
    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        adc = if (horizontal) 1 else 0
        scan = if (vertical) 1 else 0
        command(CMD_SEG_REMAP or adc, CMD_SCAN or (scan shl 3))
    }

    /** Set the RAM line shown at COM0 (hardware vertical scroll), 0..63. */
    fun setStartLine(line: Int) = command(CMD_START_LINE or (line and 0x3F))

    /** Shift the COM mapping, 0..63. */
    fun setDisplayOffset(offset: Int) = command(CMD_OFFSET, offset and 0x3F)

    /** Set the multiplex ratio, 1..64. */
    fun setMultiplex(ratio: Int) = command(CMD_MULTIPLEX, (ratio - 1) and 0x3F)

    /** Set the clock divide ratio (1..16) and oscillator setting (0..15, 5 = nominal). */
    fun setClock(divide: Int, osc: Int) = command(CMD_CLOCK, ((osc and 0x0F) shl 4) or ((divide - 1) and 0x0F))

    /** Set the pre-charge (1..15) and discharge (1..15) periods in display clocks. */
    fun setPrecharge(precharge: Int, discharge: Int) =
        command(CMD_PRECHARGE, ((discharge and 0x0F) shl 4) or (precharge and 0x0F))

    /** Select the COM pin configuration: true = alternative (0x12, reset), false = sequential (0x02). */
    fun setComConfig(alternative: Boolean) = command(CMD_COM, if (alternative) 0x12 else 0x02)

    /** Set the VCOM deselect level, 0..255 (reset 0x35). */
    fun setVcomDeselect(value: Int) = command(CMD_VCOM, value and 0xFF)

    /** Set the charge pump voltage: 0 = 7.4 V (reset), 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V. */
    fun setPumpVoltage(level: Int) = command(CMD_PUMP or (level and 3))

    /** Switch the internal DC-DC on (0x8B) or off (0x8A, external VPP); call with the display off. */
    fun setDcdc(on: Boolean) {
        useDcdc = on
        command(CMD_DCDC, if (on) 0x8B else 0x8A)
    }

    // ---- reads and raw access -------------------------------------------------------------

    /**
     * Read the status byte (I2C only): bit 7 BUSY, bit 6 = 1 when the display is off.
     *
     * @throws UnsupportedOperationException on SPI (write-only)
     */
    fun readStatus(): Int {
        if (!bus.canRead()) throw UnsupportedOperationException("the SPI interface is write-only")
        return bus.readStatus()
    }

    /**
     * Read [n] bytes (at most 30) of display RAM (I2C only); the dummy byte after an address change is discarded.
     *
     * @throws UnsupportedOperationException on SPI (write-only)
     */
    fun readPage(page: Int, column: Int, n: Int): ByteArray {
        if (!bus.canRead()) throw UnsupportedOperationException("the SPI interface is write-only")
        bus.writeCommands(addressBytes(page, column))
        return bus.readRam(n)
    }

    /** Send raw bytes as one command stream. */
    fun sendCommand(vararg bytes: Int) = command(*bytes)
}
