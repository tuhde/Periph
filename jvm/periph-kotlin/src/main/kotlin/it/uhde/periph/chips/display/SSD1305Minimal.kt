package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SSD1305 132 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I2C at `0x3C` (SA0 = low) or
 * `0x3D` (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide modules usually show RAM columns 4..131, so
 * `columnOffset` defaults to 4.
 *
 * All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top pixel of the page);
 * [show] sends it page by page (page addressing, the reset default). The chip has no charge pump: the OLED supply
 * VCC (7..15 V) must be supplied by the board. The initialisation follows the datasheet power-on flow: setup stream
 * with the display off, RAM cleared, display on + 100 ms.
 *
 * The same driver runs over 4-wire SPI as [SSD1305SpiMinimal] / [SSD1305SpiFull].
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring)
 * @param columnOffset first RAM column shown (4 for 128-pixel modules, 0 for a true 132-column panel)
 * @throws IllegalArgumentException if width, height or columnOffset is out of range
 * @throws IOException if a transfer fails
 */
open class SSD1305Minimal protected constructor(
    protected val bus: SSD1305Bus,
    protected val width: Int,
    protected val height: Int,
    protected val columnOffset: Int,
) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 100 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        columnOffset: Int = 4,
    ) : this(SSD1305I2cBus(connection), width, height, columnOffset)

    companion object {
        const val WIDTH = 132
        const val HEIGHT = 64
        /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
        const val I2C_CHUNK = SSD1305I2cBus.CHUNK

        const val CMD_COL_LO = 0x00       // | A[3:0]
        const val CMD_COL_HI = 0x10       // | A[7:4]
        const val CMD_ADDR_MODE = 0x20
        const val CMD_COL_RANGE = 0x21
        const val CMD_PAGE_RANGE = 0x22
        const val CMD_START_LINE = 0x40   // | A[5:0]
        const val CMD_CONTRAST = 0x81
        const val CMD_BRIGHTNESS = 0x82
        const val CMD_LUT = 0x91
        const val CMD_BANK_1_16 = 0x92
        const val CMD_BANK_17_32 = 0x93
        const val CMD_SEG_REMAP = 0xA0    // | ADC
        const val CMD_SCROLL_AREA = 0xA3
        const val CMD_ALL_ON = 0xA4       // | 1
        const val CMD_INVERT = 0xA6       // | 1
        const val CMD_MULTIPLEX = 0xA8
        const val CMD_DIM_MODE = 0xAB
        const val CMD_DIM_ON = 0xAC
        const val CMD_MASTER = 0xAD
        const val CMD_DISPLAY = 0xAE      // | 1
        const val CMD_PAGE = 0xB0         // | A[2:0]
        const val CMD_SCAN = 0xC0         // | (scan << 3)
        const val CMD_OFFSET = 0xD3
        const val CMD_CLOCK = 0xD5
        const val CMD_AREA_COLOR = 0xD8
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

    protected val pages: Int
    protected val fb: ByteArray
    protected var mode: Int = ADDR_PAGE
    protected var adc: Int = 0
    protected var scan: Int = 0

    init {
        require(width in 1..WIDTH) { "width must be 1..132" }
        require(height in 16..HEIGHT && height % 8 == 0) { "height must be a multiple of 8, 16..64" }
        require(columnOffset >= 0 && columnOffset + width <= WIDTH) { "columnOffset + width must be at most 132" }
        pages = height / 8
        fb = ByteArray(width * pages)
        initSequence()
    }

    /** @return the visible columns of the framebuffer */
    fun width(): Int = width

    /** @return the rows of the framebuffer */
    fun height(): Int = height

    /** Zero the framebuffer. Call [show] to update the panel. */
    fun clear() {
        fb.fill(0)
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x column, 0 .. width - 1
     * @param y row, 0 .. height - 1
     * @param on true to set the pixel, false to clear it
     */
    fun setPixel(x: Int, y: Int, on: Boolean = true) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = (y shr 3) * width + x
        val mask = 1 shl (y and 7)
        fb[i] = if (on) (fb[i].toInt() or mask).toByte() else (fb[i].toInt() and mask.inv()).toByte()
    }

    /**
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page + column command
     * stream, then `width` data bytes. Horizontal / vertical addressing: column and page range commands, then one
     * data stream.
     *
     * @throws IOException if a transfer fails
     */
    fun show() {
        showAll()
    }

    // ---- internals ------------------------------------------------------------------------

    protected fun command(vararg values: Int) {
        bus.writeCommands(ByteArray(values.size) { values[it].toByte() })
    }

    protected fun addressBytes(page: Int, column: Int): ByteArray = byteArrayOf(
        (CMD_PAGE or (page and 7)).toByte(), (CMD_COL_LO or (column and 0x0F)).toByte(),
        (CMD_COL_HI or ((column shr 4) and 0x0F)).toByte(),
    )

    protected fun showPage0(page: Int) {
        bus.writeCommands(addressBytes(page, columnOffset))
        bus.writeData(fb, page * width, width)
    }

    protected fun showAll() {
        if (mode == ADDR_PAGE) {
            for (page in 0 until pages) showPage0(page)
            return
        }
        command(CMD_COL_RANGE, columnOffset, columnOffset + width - 1, CMD_PAGE_RANGE, 0, pages - 1)
        if (mode == ADDR_HORIZONTAL) {
            bus.writeData(fb, 0, width * pages)
            return
        }
        val out = ByteArray(width * pages)
        var n = 0
        for (x in 0 until width) {
            for (p in 0 until pages) out[n++] = fb[p * width + x]
        }
        bus.writeData(out, 0, out.size)
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    private fun initSequence() {
        bus.hardwareReset()
        sleepMs(1)
        command(
            CMD_DISPLAY,
            CMD_CLOCK, 0x70,
            CMD_MULTIPLEX, height - 1,
            CMD_OFFSET, 0x00,
            CMD_START_LINE,
            CMD_SEG_REMAP or adc,
            CMD_SCAN or (scan shl 3),
            CMD_COM, if (height > 32) 0x12 else 0x02,
            CMD_CONTRAST, 0x80,
            CMD_PRECHARGE, 0x22,
            CMD_VCOMH, 0x34,
            CMD_MASTER, 0x8E,
            CMD_ALL_ON,
            CMD_INVERT,
            CMD_ADDR_MODE, 0x02,
        )
        val blank = ByteArray(WIDTH)
        for (page in 0 until 8) {
            bus.writeCommands(addressBytes(page, 0))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY or 1)
        sleepMs(100)
    }
}
