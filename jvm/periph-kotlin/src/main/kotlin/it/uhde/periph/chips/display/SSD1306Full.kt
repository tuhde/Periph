package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection

/**
 * SSD1306 OLED controller-driver — full driver.
 *
 * Extends [SSD1306Minimal] with drawing primitives, contrast, sleep, invert, all-on test, flip, start line, display
 * offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH level, charge pump, addressing
 * mode, hardware scrolling and raw commands. The SSD1306 cannot be read over its serial interfaces.
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..128
 * @param height rows, a multiple of 8, 16..64
 * @param externalVcc true when the board supplies VCC (the charge pump stays off)
 */
open class SSD1306Full protected constructor(
    bus: MonoOledBus,
    width: Int,
    height: Int,
    externalVcc: Boolean,
) : SSD1306Minimal(bus, width, height, externalVcc) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 100 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        externalVcc: Boolean = false,
    ) : this(MonoOledI2cBus(connection), width, height, externalVcc)

    // ---- framebuffer ----------------------------------------------------------------------

    /** Return a framebuffer pixel; out-of-range coordinates read as false. */
    fun getPixel(x: Int, y: Int): Boolean = canvas.getPixel(x, y)

    /** Set (or clear) the whole framebuffer. */
    fun fill(on: Boolean) = canvas.fill(on)

    /** Draw a Bresenham line into the framebuffer. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, on: Boolean) = canvas.drawLine(x0, y0, x1, y1, on)

    /** Draw a rectangle outline or a filled rectangle into the framebuffer. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, on: Boolean, fill: Boolean) = canvas.drawRect(x, y, w, h, on, fill)

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer; clipped.
     *
     * @throws IllegalArgumentException if [y] is not a multiple of 8
     */
    fun blit(x: Int, y: Int, columns: ByteArray, heightPages: Int) = canvas.blit(x, y, columns, heightPages)

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages are ignored.
     *
     * @throws IllegalStateException while a hardware scroll is active (call [scrollStop] first)
     */
    fun showPage(page: Int) {
        checkRamAccess()
        if (page !in 0 until canvas.pages) return
        if (mode == ADDR_PAGE) {
            showPage0(page)
            return
        }
        command(CMD_COL_RANGE, 0, canvas.width - 1, CMD_PAGE_RANGE, page, page)
        bus.writeData(canvas.data, page * canvas.width, canvas.width)
    }

    // ---- display control ------------------------------------------------------------------

    /** Set the segment output current (contrast), 0..255 (reset 0x7F). */
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

    /** Set the clock divide ratio (1..16) and oscillator setting (0..15, 8 = reset). */
    fun setClock(divide: Int, osc: Int) = command(CMD_CLOCK, ((osc and 0x0F) shl 4) or ((divide - 1) and 0x0F))

    /** Set the pre-charge phase 1 and phase 2 periods (1..15 display clocks each). */
    fun setPrecharge(phase1: Int, phase2: Int) =
        command(CMD_PRECHARGE, ((phase2 and 0x0F) shl 4) or (phase1 and 0x0F))

    /** Select the COM pin configuration: alternative (true, reset) or sequential; [remap] enables the COM left / right remap. */
    fun setComConfig(alternative: Boolean, remap: Boolean = false) =
        command(CMD_COM, 0x02 or (if (alternative) 0x10 else 0) or (if (remap) 0x20 else 0))

    /** Set the VCOMH deselect level (only bits 6:4 used; reset 0x20). */
    fun setVcomh(value: Int) = command(CMD_VCOMH, value and 0x70)

    /** Enable (0x14) or disable (0x10) the internal charge pump; takes effect with the display on. */
    fun setChargePump(enabled: Boolean) = command(CMD_CHARGE_PUMP, if (enabled) 0x14 else 0x10)

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
     * Set up a horizontal scroll, one column per step (start with [scrollStart]); [interval] is a table index 0..7 =
     * 5, 64, 128, 256, 3, 4, 25, 2 frames per step. Do not call while scrolling is active.
     */
    fun setScrollHorizontal(right: Boolean, startPage: Int, endPage: Int, interval: Int) =
        command(if (right) 0x26 else 0x27, 0x00, startPage and 7, interval and 7, endPage and 7, 0x00, 0xFF)

    /** Set up a vertical + horizontal (diagonal) scroll ([rows] 1..63 per step). */
    fun setScrollVertical(right: Boolean, startPage: Int, endPage: Int, interval: Int, rows: Int) =
        command(if (right) 0x29 else 0x2A, 0x00, startPage and 7, interval and 7, endPage and 7, rows and 0x3F)

    /** Set the vertical scroll area: [topFixed] rows (0..63) fixed, [scrollRows] (1..64) scrolling. */
    fun setScrollArea(topFixed: Int, scrollRows: Int) = command(CMD_SCROLL_AREA, topFixed and 0x3F, scrollRows and 0x7F)

    /** Activate the configured scroll (0x2F); [show] and [showPage] are refused until [scrollStop]. */
    fun scrollStart() {
        command(0x2F)
        scrolling = true
    }

    /** Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. */
    fun scrollStop() {
        command(0x2E)
        scrolling = false
        showAll()
    }

    /** Send raw bytes as one command stream. */
    fun sendCommand(vararg bytes: Int) = command(*bytes)
}
