package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SH1106 132 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * Drives a monochrome SH1106 OLED panel (typically a 1.3" 128 x 64 module) over I2C at `0x3C` (SA0 = VSS) or `0x3D`
 * (SA0 = VDD1). The chip has 132 RAM columns; 128-pixel-wide modules show RAM columns 2..129, so `columnOffset`
 * defaults to 2.
 *
 * All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top pixel of the page);
 * [show] sends it page by page (the chip has page addressing only). The initialisation follows the datasheet
 * power-on flow with the internal DC-DC: setup stream with the display off, DC-DC on + 100 ms, RAM cleared,
 * display on + 150 ms.
 *
 * The same driver runs over 4-wire SPI as [SH1106SpiMinimal] / [SH1106SpiFull].
 *
 * @param bus transport (use the public constructor with a [Connection])
 * @param width visible columns, 1..132
 * @param height rows, a multiple of 8, 8..64
 * @param columnOffset first RAM column shown (2 for 128-pixel modules, 0 for a true 132-column panel)
 * @param internalDcdc true for the internal charge pump, false for an external VPP
 * @throws IllegalArgumentException if width, height or columnOffset is out of range
 * @throws IOException if a transfer fails
 */
open class SH1106Minimal protected constructor(
    protected val bus: SH1106Bus,
    protected val width: Int,
    protected val height: Int,
    protected val columnOffset: Int,
    internalDcdc: Boolean,
) {

    /** Construct on an I2C connection at 0x3C or 0x3D and run the initialisation sequence (about 250 ms). */
    constructor(
        connection: Connection,
        width: Int = 128,
        height: Int = HEIGHT,
        columnOffset: Int = 2,
        internalDcdc: Boolean = true,
    ) : this(SH1106I2cBus(connection), width, height, columnOffset, internalDcdc)

    companion object {
        const val WIDTH = 132
        const val HEIGHT = 64
        /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
        const val I2C_CHUNK = SH1106I2cBus.CHUNK

        const val CMD_COL_LO = 0x00       // | A[3:0]
        const val CMD_COL_HI = 0x10       // | A[7:4]
        const val CMD_PUMP = 0x30         // | A[1:0]
        const val CMD_START_LINE = 0x40   // | A[5:0]
        const val CMD_CONTRAST = 0x81
        const val CMD_SEG_REMAP = 0xA0    // | ADC
        const val CMD_ALL_ON = 0xA4       // | 1
        const val CMD_INVERT = 0xA6       // | 1
        const val CMD_MULTIPLEX = 0xA8
        const val CMD_DCDC = 0xAD
        const val CMD_DISPLAY = 0xAE      // | 1
        const val CMD_PAGE = 0xB0         // | A[2:0]
        const val CMD_SCAN = 0xC0         // | (scan << 3)
        const val CMD_OFFSET = 0xD3
        const val CMD_CLOCK = 0xD5
        const val CMD_PRECHARGE = 0xD9
        const val CMD_COM = 0xDA
        const val CMD_VCOM = 0xDB

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
    protected var useDcdc: Boolean = internalDcdc
    protected var adc: Int = 0
    protected var scan: Int = 0

    init {
        require(width in 1..WIDTH) { "width must be 1..132" }
        require(height in 8..HEIGHT && height % 8 == 0) { "height must be a multiple of 8, 8..64" }
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
     * Write the whole framebuffer to the display RAM (page + column command, then `width` data bytes per page).
     *
     * @throws IOException if a transfer fails
     */
    fun show() {
        for (page in 0 until pages) showPage0(page)
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

    /** Datasheet power-on flow: setup stream, DC-DC, RAM clear, display on. */
    private fun initSequence() {
        bus.hardwareReset()
        sleepMs(1)
        command(
            CMD_DISPLAY,
            CMD_CLOCK, 0x50,
            CMD_MULTIPLEX, height - 1,
            CMD_OFFSET, 0x00,
            CMD_START_LINE,
            CMD_SEG_REMAP or adc,
            CMD_SCAN or (scan shl 3),
            CMD_COM, 0x12,
            CMD_CONTRAST, 0x80,
            CMD_PRECHARGE, 0x22,
            CMD_VCOM, 0x35,
            CMD_PUMP,
            CMD_ALL_ON,
            CMD_INVERT,
        )
        if (useDcdc) {
            command(CMD_DCDC, 0x8B)
            sleepMs(100)
        } else {
            command(CMD_DCDC, 0x8A)
        }
        val blank = ByteArray(WIDTH)
        for (page in 0 until 8) {
            bus.writeCommands(addressBytes(page, 0))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY or 1)
        sleepMs(if (useDcdc) 150 else 50)
    }
}
