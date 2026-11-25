package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SSD1305 OLED controller-driver — full driver.
 *
 * Extends [SSD1305Minimal] with drawing primitives, contrast, brightness, dim mode, sleep, invert, all-on test, flip,
 * start line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH level,
 * addressing mode, hardware scrolling, area-colour mode with LUT / bank colours, status and RAM reads (I2C only) and
 * raw commands.
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 16..64
 * @param columnOffset first RAM column shown (4 for 128-pixel modules)
 */
open class SSD1305Full protected constructor(
    bus: SSD1305Bus,
    width: Int,
    height: Int,
    columnOffset: Int,
) : SSD1305Minimal(bus, width, height, columnOffset) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 100 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        columnOffset: Int = 4,
    ) : this(SSD1305I2cBus(connection), width, height, columnOffset)

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
        if (page !in 0 until pages) return
        if (mode == ADDR_PAGE) {
            showPage0(page)
            return
        }
        command(CMD_COL_RANGE, columnOffset, columnOffset + width - 1, CMD_PAGE_RANGE, page, page)
        bus.writeData(fb, page * width, width)
    }

    // ---- display control ------------------------------------------------------------------

    /** Set the segment output current (contrast), 0..255 (reset 0x80). */
    fun setContrast(value: Int) = command(CMD_CONTRAST, value and 0xFF)

    /** Switch the display on or off (RAM and settings are kept). */
    fun setDisplay(on: Boolean) = command(CMD_DISPLAY or (if (on) 1 else 0))

    /** Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards. */
    fun sleep() = setDisplay(false)

    /** Display on, then wait 100 ms (tAF) for SEG / COM to turn on. */
    fun wake() {
        setDisplay(true)
        sleepMs(100)
    }

    /** Switch between dim mode (0xAC) and normal display on (0xAF); configure the levels with [setDimMode] first. */
    fun setDim(on: Boolean) = command(if (on) CMD_DIM_ON else CMD_DISPLAY or 1)

    /** Configure the BANK0 contrast and colour-bank brightness (0..255 each) used in dim mode. */
    fun setDimMode(contrast: Int, brightness: Int) = command(CMD_DIM_MODE, 0x00, contrast and 0xFF, brightness and 0xFF)

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

    /** Set the multiplex ratio, 16..64. */
    fun setMultiplex(ratio: Int) = command(CMD_MULTIPLEX, (ratio - 1) and 0x3F)

    /** Set the clock divide ratio (1..16) and oscillator setting (0..15, 7 = reset). */
    fun setClock(divide: Int, osc: Int) = command(CMD_CLOCK, ((osc and 0x0F) shl 4) or ((divide - 1) and 0x0F))

    /** Set the pre-charge phase 1 and phase 2 periods (1..15 display clocks each). */
    fun setPrecharge(phase1: Int, phase2: Int) =
        command(CMD_PRECHARGE, ((phase2 and 0x0F) shl 4) or (phase1 and 0x0F))

    /** Select the COM pin configuration: alternative (true, reset) or sequential; [remap] enables the COM left / right remap. */
    fun setComConfig(alternative: Boolean, remap: Boolean = false) =
        command(CMD_COM, 0x02 or (if (alternative) 0x10 else 0) or (if (remap) 0x20 else 0))

    /** Set the VCOMH deselect level (only bits 5:2 used; reset 0x34). */
    fun setVcomh(value: Int) = command(CMD_VCOMH, value and 0x3C)

    /**
     * Select how [show] writes the display RAM: [ADDR_PAGE] (reset default), [ADDR_HORIZONTAL] or [ADDR_VERTICAL].
     *
     * @throws IllegalArgumentException if the mode is not one of the three constants
     */
    fun setAddressingMode(newMode: Int) {
        require(newMode in ADDR_HORIZONTAL..ADDR_PAGE) { "invalid addressing mode" }
        mode = newMode
        command(CMD_ADDR_MODE, newMode)
    }

    // ---- hardware scrolling ---------------------------------------------------------------

    /**
     * Set up a horizontal scroll (start with [scrollStart]); [interval] is a table index 0..6 = 6, 32, 64, 128, 3, 4,
     * 2 frames per step, [columns] 1..4. Do not call while scrolling is active.
     */
    fun setScrollHorizontal(right: Boolean, startPage: Int, endPage: Int, interval: Int, columns: Int = 1) =
        command(if (right) 0x26 else 0x27, columns and 7, startPage and 7, minOf(interval, 6), endPage and 7)

    /** Set up a vertical + horizontal scroll ([columns] 0..4, [rows] 1..63 per step). */
    fun setScrollVertical(right: Boolean, startPage: Int, endPage: Int, interval: Int, columns: Int, rows: Int) =
        command(if (right) 0x29 else 0x2A, columns and 7, startPage and 7, minOf(interval, 6), endPage and 7, rows and 0x3F)

    /** Set the vertical scroll area: [topFixed] rows (0..63) fixed, [scrollRows] (1..64) scrolling. */
    fun setScrollArea(topFixed: Int, scrollRows: Int) = command(CMD_SCROLL_AREA, topFixed and 0x3F, scrollRows and 0x7F)

    /** Activate the configured scroll (0x2F). */
    fun scrollStart() = command(0x2F)

    /** Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. */
    fun scrollStop() {
        command(0x2E)
        showAll()
    }

    // ---- area colour ----------------------------------------------------------------------

    /** Enable the area-colour mode and/or the low-power display mode (0xD8). */
    fun setAreaColor(enable: Boolean, lowPower: Boolean = false) =
        command(CMD_AREA_COLOR, (if (enable) 0x30 else 0) or (if (lowPower) 0x05 else 0))

    /** Set the colour-bank brightness, 0..255 (reset 0x80). */
    fun setBrightness(value: Int) = command(CMD_BRIGHTNESS, value and 0xFF)

    /** Set the drive pulse width codes (31..63) of BANK0 and colours A, B, C. */
    fun setLut(bank0: Int, a: Int, b: Int, c: Int) = command(CMD_LUT, bank0 and 0x3F, a and 0x3F, b and 0x3F, c and 0x3F)

    /**
     * Assign colours A..D (2 bits per bank) to banks 1..16 and optionally banks 17..32.
     *
     * @throws IllegalArgumentException if a group is not exactly 4 bytes
     */
    fun setBankColors(banks1to16: ByteArray, banks17to32: ByteArray? = null) {
        require(banks1to16.size == 4 && (banks17to32 == null || banks17to32.size == 4)) { "bank colour groups need 4 bytes" }
        command(CMD_BANK_1_16, *IntArray(4) { banks1to16[it].toInt() and 0xFF })
        if (banks17to32 != null) command(CMD_BANK_17_32, *IntArray(4) { banks17to32[it].toInt() and 0xFF })
    }

    // ---- reads and raw access -------------------------------------------------------------

    /**
     * Read the status byte (I2C only): bit 6 = 1 when the display is off; the other bits are reserved.
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
