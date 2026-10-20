package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * UC1611s graphic LCD controller-driver — full driver.
 *
 * Extends [Uc1611sMinimal] with drawing primitives, contrast, sleep, invert, flip / top view, hardware scroll
 * with fixed lines, bias ratio, temperature compensation, line rate, charge pump / external VLCD, panel
 * loading, partial display, N-line inversion, isolation clock, the four RAM patterns, status and RAM reads, and
 * raw commands.
 *
 * @param command connection at the command address (0x38, 0x3A, 0x3C or 0x3E)
 * @param data connection at the data address (command address + 1)
 * @param width columns, 1..240
 * @param height rows, a multiple of 8, 8..128
 * @param topView true for the 12:00 viewing direction (MY = 1, MX = 0)
 * @param contrast potentiometer PM 0..255 (the DOG example value is 0x8F)
 */
open class Uc1611sFull protected constructor(
    bus: Uc1611sBus,
    width: Int,
    height: Int,
    topView: Boolean,
    contrast: Int,
) : Uc1611sMinimal(bus, width, height, topView, contrast) {

    constructor(
        command: Connection,
        data: Connection,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        topView: Boolean = false,
        contrast: Int = 0x8F,
    ) : this(Uc1611sI2cBus(command, data), width, height, topView, contrast)

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

    /**
     * Draw a rectangle outline or a filled rectangle into the framebuffer.
     *
     * @param x left column
     * @param y top row
     * @param w width in pixels
     * @param h height in pixels
     * @param on true to set pixels, false to clear them
     * @param fill true for a filled rectangle
     */
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
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. `columns` holds
     * `heightPages` groups of columns, page by page: byte `p * n + c` is column `c` of page row `p`
     * (`n = columns.size / heightPages`). The bitmap is clipped to the framebuffer.
     *
     * @throws IllegalArgumentException if y is not a multiple of 8 or heightPages is 0
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
     * @throws IOException if the page still fails after all attempts
     */
    fun showPage(page: Int) {
        if (page in 0 until pages) showPage0(page)
    }

    // ---- display control ------------------------------------------------------------------

    /** Set the potentiometer PM that adjusts VLCD (contrast), 0..255. */
    fun setContrast(value: Int) {
        contrastValue = value and 0xFF
        command(CMD_POTENTIOMETER, contrastValue)
    }

    /** Switch the display on, or into sleep (registers and RAM kept, below 1 uA). */
    fun setDisplay(on: Boolean) {
        command(CMD_DISPLAY or (if (on) 1 else 0))
    }

    /** Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active. */
    fun sleep() {
        setDisplay(false)
    }

    /**
     * Leave sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump restarts, so
     * the driver polls DE (at most 50 ms) instead of sleeping.
     */
    fun wake() {
        setDisplay(true)
        waitDisplayEnabled(50)
    }

    /** Invert the displayed image without changing RAM. */
    fun setInvert(on: Boolean) {
        command(CMD_INVERSE or (if (on) 1 else 0))
    }

    /** Force all pixels on (test mode, RAM unchanged). */
    fun setAllOn(on: Boolean) {
        command(CMD_ALL_ON or (if (on) 1 else 0))
    }

    /**
     * Mirror columns (MX) and/or rows (MY). MY acts at once; MX is applied to RAM writes, so call [show]
     * afterwards to re-send the framebuffer.
     */
    fun setFlip(mirrorX: Boolean, mirrorY: Boolean) {
        mx = if (mirrorX) 1 else 0
        my = if (mirrorY) 1 else 0
        command(CMD_MAPPING, mapping())
    }

    /** Scroll the image up by `line` rows (hardware scroll, wraps at 160 rows); 0 .. 159 - 2 * fixed lines. */
    fun setScrollLine(line: Int) {
        command(CMD_SCROLL_LO or (line and 0x0F), CMD_SCROLL_HI or ((line shr 4) and 0x0F))
    }

    /** Keep the first `2 * lines` rows out of the scroll (FL, 0..15). */
    fun setFixedLines(lines: Int) {
        command(CMD_FIXED or (lines and 0x0F))
    }

    /** Select the LCD bias ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12. */
    fun setBiasRatio(ratio: Int) {
        command(CMD_BIAS or (ratio and 3))
    }

    /** Select the VBIAS temperature compensation: 0 = -0.05 %/degC, 1 = -0.10, 2 = -0.15, 3 = 0.00. */
    fun setTemperatureCompensation(curve: Int) {
        command(CMD_TEMP or (curve and 3))
    }

    /** Select the line rate (On/Off mode): 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second. */
    fun setLineRate(rate: Int) {
        command(CMD_LINE_RATE or (rate and 3))
    }

    /** Choose the internal 11x charge pump (true, reset) or an external VLCD supply (false). */
    fun setPump(internal: Boolean) {
        command(CMD_PUMP or (if (internal) 3 else 0))
    }

    /** Tell the chip the panel capacitance: true = 33..55 nF (reset), false = up to 33 nF. */
    fun setPanelLoading(high: Boolean) {
        command(CMD_PANEL or (if (high) 3 else 0))
    }

    /**
     * Scan only rows `start..end` (the rest sleeps), or return to full scan.
     *
     * @param end last scanned row (DEN); a negative value selects height - 1
     */
    fun setPartialDisplay(on: Boolean, start: Int, end: Int) {
        if (!on) {
            command(CMD_PARTIAL)
            return
        }
        val last = if (end < 0) height - 1 else end
        command(CMD_PART_START, start and 0xFF, CMD_PART_END, last and 0xFF, CMD_PARTIAL or 3)
    }

    /** Set N-line inversion (cross-talk tuning): `lines` 0 (off) .. 63, optional XOR mode. */
    fun setNLineInversion(lines: Int, xor: Boolean) {
        command(CMD_NLINE, (if (xor) 0x40 else 0) or (lines and 0x3F))
    }

    /** Set the isolation clock before (front) and after (back) the COM pulse, 0..15 each. */
    fun setIsolationClock(front: Int, back: Int) {
        command(CMD_ISOLATION, 0x13, front and 0x0F)
        command(CMD_ISOLATION, 0x14, back and 0x0F)
    }

    /** Select which of the four RAM screens (patterns, 0..3) is displayed. */
    fun setDisplayPattern(pattern: Int) {
        command(CMD_PATTERN or ((pattern and 3) shl 1) or 1)
    }

    /** Select the RAM screen (0..3) that [show] / [showPage] write to (PA[6:5]). */
    fun setWritePattern(pattern: Int) {
        this.pattern = pattern and 3
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the three status bytes (a NOP is sent first to restart the chip's status cycle): Status1 (Ver, MX, MY,
     * WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]), Status3 (product code 1 in bits 7..4, ESD flag in bit 0).
     *
     * @throws IOException if the read fails after all attempts
     */
    fun readStatus(): ByteArray = readStatus0()

    /**
     * Read `n` bytes of display RAM (the dummy byte after an address change is discarded).
     *
     * @throws IOException if address or read fail after all attempts
     */
    fun readRam(page: Int, column: Int, n: Int): ByteArray {
        if (!bus.canRead()) throw UnsupportedOperationException("the SPI interface of the module is write-only")
        return retry {
            bus.writeCommand(addressBytes(page, column))
            bus.readRam(n)
        }
    }

    /** System Reset and reconfigure with the cached contrast and mapping (RAM is kept). */
    fun reset() {
        resetChip()
        configure()
        enable()
        waitDisplayEnabled(50)
    }

    /** Send raw bytes as one command transaction (retried on NACK). */
    fun sendCommand(vararg bytes: Int) {
        command(*bytes)
    }

    /**
     * Send raw bytes to the display RAM (chunked) at the current RAM address. A failed chunk is not retried (the
     * partial write cannot be continued); the failure is counted.
     */
    fun sendData(bytes: ByteArray) {
        try {
            writeChunks(bytes, 0, bytes.size)
        } catch (e: IOException) {
            incrementErrors()
            throw e
        }
    }
}
