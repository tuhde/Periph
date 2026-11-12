package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SSD1306 128 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I2C at `0x3C` (SA0 = low) or
 * `0x3D` (SA0 = high). The chip has an exact 128-column RAM, so there is no column offset.
 *
 * All drawing goes into a [MonoFramebuffer] of `width * height / 8` bytes (page-major, bit 0 = top pixel of the
 * page); [show] sends it page by page (page addressing, the reset default). The initialisation follows the
 * datasheet and the charge-pump application note: setup stream with the display off (charge pump, page
 * addressing), RAM cleared, display on + 100 ms. With `externalVcc = false` (default) the internal charge pump
 * makes the OLED supply VCC from VBAT.
 *
 * The same driver runs over 4-wire SPI as [SSD1306SpiMinimal] / [SSD1306SpiFull].
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..128
 * @param height rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring)
 * @param externalVcc true when the board supplies VCC (the charge pump stays off)
 * @throws IllegalArgumentException if width or height is out of range
 * @throws IOException if a transfer fails
 */
open class SSD1306Minimal protected constructor(
    protected val bus: MonoOledBus,
    width: Int,
    height: Int,
    protected val externalVcc: Boolean,
) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 100 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        externalVcc: Boolean = false,
    ) : this(MonoOledI2cBus(connection), width, height, externalVcc)

    companion object {
        const val WIDTH = 128
        const val HEIGHT = 64
        /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
        const val I2C_CHUNK = MonoOledI2cBus.CHUNK

        const val CMD_ADDR_MODE = 0x20
        const val CMD_COL_RANGE = 0x21
        const val CMD_PAGE_RANGE = 0x22
        const val CMD_START_LINE = 0x40   // | A[5:0]
        const val CMD_CONTRAST = 0x81
        const val CMD_CHARGE_PUMP = 0x8D
        const val CMD_SEG_REMAP = 0xA0    // | ADC
        const val CMD_SCROLL_AREA = 0xA3
        const val CMD_ALL_ON = 0xA4       // | 1
        const val CMD_INVERT = 0xA6       // | 1
        const val CMD_MULTIPLEX = 0xA8
        const val CMD_DISPLAY = 0xAE      // | 1
        const val CMD_PAGE = 0xB0         // | A[2:0]
        const val CMD_SCAN = 0xC0         // | (scan << 3)
        const val CMD_OFFSET = 0xD3
        const val CMD_CLOCK = 0xD5
        const val CMD_PRECHARGE = 0xD9
        const val CMD_COM = 0xDA
        const val CMD_VCOMH = 0xDB

        /** Horizontal addressing: the column pointer advances first. */
        const val ADDR_HORIZONTAL = 0
        /** Vertical addressing: the page pointer advances first. */
        const val ADDR_VERTICAL = 1
        /** Page addressing (reset default). */
        const val ADDR_PAGE = 2

        @JvmStatic
        protected fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }
        }
    }

    protected val canvas: MonoFramebuffer
    protected var mode: Int = ADDR_PAGE
    protected var scrolling: Boolean = false
    protected var adc: Int = 0
    protected var scan: Int = 0

    init {
        require(width in 1..WIDTH) { "width must be 1..128" }
        require(height in 16..HEIGHT && height % 8 == 0) { "height must be a multiple of 8, 16..64" }
        canvas = MonoFramebuffer(width, height)
        initSequence()
    }

    /** @return the visible columns of the framebuffer */
    fun width(): Int = canvas.width

    /** @return the rows of the framebuffer */
    fun height(): Int = canvas.height

    /** Zero the framebuffer. Call [show] to update the panel. */
    fun clear() {
        canvas.clear()
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x column, 0 .. width - 1
     * @param y row, 0 .. height - 1
     * @param on true to set the pixel, false to clear it
     */
    fun setPixel(x: Int, y: Int, on: Boolean = true) {
        canvas.setPixel(x, y, on)
    }

    /**
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page + column command
     * stream, then `width` data bytes. Horizontal / vertical addressing: column and page range commands, then one
     * data stream.
     *
     * @throws IllegalStateException while a hardware scroll is active (call `scrollStop()` first)
     * @throws IOException if a transfer fails
     */
    fun show() {
        showAll()
    }

    // ---- internals ------------------------------------------------------------------------

    protected fun command(vararg values: Int) {
        bus.writeCommands(ByteArray(values.size) { values[it].toByte() })
    }

    protected fun addressBytes(page: Int): ByteArray = byteArrayOf((CMD_PAGE or (page and 7)).toByte(), 0x00, 0x10)

    protected fun checkRamAccess() {
        check(!scrolling) { "RAM access while a scroll is active; call scrollStop() first" }
    }

    protected fun showPage0(page: Int) {
        bus.writeCommands(addressBytes(page))
        bus.writeData(canvas.data, page * canvas.width, canvas.width)
    }

    protected fun showAll() {
        checkRamAccess()
        if (mode == ADDR_PAGE) {
            for (page in 0 until canvas.pages) showPage0(page)
            return
        }
        command(CMD_COL_RANGE, 0, canvas.width - 1, CMD_PAGE_RANGE, 0, canvas.pages - 1)
        val out = if (mode == ADDR_HORIZONTAL) canvas.data else canvas.transposed()
        bus.writeData(out, 0, out.size)
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    private fun initSequence() {
        bus.hardwareReset()
        sleepMs(1)
        command(
            CMD_DISPLAY,
            CMD_CLOCK, 0x80,
            CMD_MULTIPLEX, canvas.height - 1,
            CMD_OFFSET, 0x00,
            CMD_START_LINE,
            CMD_CHARGE_PUMP, if (externalVcc) 0x10 else 0x14,
            CMD_ADDR_MODE, 0x02,
            CMD_SEG_REMAP or adc,
            CMD_SCAN or (scan shl 3),
            CMD_COM, if (canvas.height > 32) 0x12 else 0x02,
            CMD_CONTRAST, 0x7F,
            CMD_PRECHARGE, 0x22,
            CMD_VCOMH, 0x20,
            CMD_ALL_ON,
            CMD_INVERT,
        )
        val blank = ByteArray(WIDTH)
        for (page in 0 until 8) {
            bus.writeCommands(addressBytes(page))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY or 1)
        sleepMs(100)
    }
}
