package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * UC1611s graphic LCD controller-driver — minimal driver.
 *
 * Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white mode over I2C. The chip
 * carries the command/data bit in its I2C address, so it answers on two consecutive 7-bit addresses:
 * `0x38 + 2n` for commands and `0x39 + 2n` for display RAM data (n = A3 << 1 | A2, the two address pins). The
 * driver therefore takes two connections on the same bus.
 *
 * All drawing goes into a framebuffer of `width * height / 8` bytes (page-major, bit 0 = top pixel of the page,
 * 3840 bytes at 240 x 128); [show] sends it to the display RAM. The initialisation follows the DOG module's
 * bottom-view example: System Reset, temperature compensation -0.10 %/degC, COM end 127, potentiometer
 * (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
 *
 * Error handling: the chip occasionally NACKs a transfer (seen on long cables and for about 6 ms while the
 * charge pump restarts). Every transfer is retried up to [MAX_ATTEMPTS] times, [RETRY_DELAY_MS] apart: a
 * command stream is repeated and a RAM page is rewritten from its address command (a partial write cannot be
 * continued). Repeated attempts are counted ([retryCount]); a transfer that still fails throws its
 * [IOException] and is counted in [errorCount].
 *
 * @param command connection at the command address (0x38, 0x3A, 0x3C or 0x3E)
 * @param data connection at the data address (command address + 1)
 * @param width columns, 1..240
 * @param height rows, a multiple of 8, 8..128
 * @throws IllegalArgumentException if width or height is out of range
 * @throws IOException if the chip does not acknowledge the System Reset
 */
open class Uc1611sMinimal protected constructor(
    protected val cmd: Connection,
    protected val data: Connection,
    protected val width: Int,
    protected val height: Int,
    topView: Boolean,
    contrast: Int,
) {

    constructor(command: Connection, data: Connection, width: Int = WIDTH, height: Int = HEIGHT) :
        this(command, data, width, height, false, 0x8F)

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 128
        /** Largest data write in bytes (the 32-byte limit of some I2C layers). */
        const val I2C_CHUNK = 32
        /** Attempts per transfer before it is counted as failed. */
        const val MAX_ATTEMPTS = 8
        /** Wait between two attempts in milliseconds. */
        const val RETRY_DELAY_MS = 2

        protected const val PAGES_RAM = 20   // RAM pages cleared at init (160 rows / 8)
        const val CMD_TEMP = 0x24          // | TC[1:0]
        const val CMD_PANEL = 0x28         // | PC[1:0]
        const val CMD_PUMP = 0x2C          // | PC[3:2]
        const val CMD_SCROLL_LO = 0x40     // | SL[3:0]
        const val CMD_SCROLL_HI = 0x50     // | SL[7:4]
        const val CMD_PAGE_LO = 0x60       // | PA[3:0]
        const val CMD_PAGE_HI = 0x70       // | PA[6:4]
        const val CMD_POTENTIOMETER = 0x81
        const val CMD_ISOLATION = 0x82
        const val CMD_PARTIAL = 0x84       // | LC[9:8]
        const val CMD_RAM_ADDR = 0x88      // | AC[2:0]
        const val CMD_FIXED = 0x90         // | FL[3:0]
        const val CMD_LINE_RATE = 0xA0     // | LC[5:4]
        const val CMD_ALL_ON = 0xA4        // | DC1
        const val CMD_INVERSE = 0xA6       // | DC0
        const val CMD_DISPLAY = 0xA8       // | DC[4:2]
        const val CMD_MAPPING = 0xC0
        const val CMD_NLINE = 0xC8
        const val CMD_PATTERN = 0xD0       // | DC[7:5]
        const val CMD_RESET = 0xE2
        const val CMD_NOP = 0xE3
        const val CMD_BIAS = 0xE8          // | BR[1:0]
        const val CMD_COM_END = 0xF1
        const val CMD_PART_START = 0xF2
        const val CMD_PART_END = 0xF3

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
    protected var mx: Int = if (topView) 0 else 1
    protected var my: Int = if (topView) 1 else 0
    protected var pattern: Int = 0
    protected var contrastValue: Int = contrast and 0xFF
    private var retries = 0
    private var errors = 0

    init {
        require(width in 1..WIDTH) { "width must be 1..240" }
        require(height in 8..HEIGHT && height % 8 == 0) { "height must be a multiple of 8, 8..128" }
        pages = height / 8
        fb = ByteArray(width * pages)
        initSequence()
    }

    /** @return the columns of the framebuffer */
    fun width(): Int = width

    /** @return the rows of the framebuffer */
    fun height(): Int = height

    /** @return true if no transfer has failed after all retries since construction or [clearErrors] */
    fun ok(): Boolean = errors == 0

    /** @return the number of transfer attempts that had to be repeated */
    fun retryCount(): Int = retries

    /** @return the number of transfers that still failed after [MAX_ATTEMPTS] attempts */
    fun errorCount(): Int = errors

    /** Reset the retry and error counters. */
    fun clearErrors() {
        retries = 0
        errors = 0
    }

    /** Count one failed transfer (used by the raw data write of [Uc1611sFull]). */
    protected fun incrementErrors() {
        errors++
    }

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
    fun setPixel(x: Int, y: Int, on: Boolean) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = (y shr 3) * width + x
        val mask = 1 shl (y and 7)
        fb[i] = if (on) (fb[i].toInt() or mask).toByte() else (fb[i].toInt() and mask.inv()).toByte()
    }

    /**
     * Write the whole framebuffer to the display RAM (page + column, then width data bytes per page).
     *
     * @throws IOException if a page still fails after all attempts
     */
    fun show() {
        for (page in 0 until pages) showPage0(page)
    }

    // ---- internals ------------------------------------------------------------------------

    /** Run [fn] up to MAX_ATTEMPTS times (RETRY_DELAY_MS apart); count retries and the final failure. */
    protected fun <T> retry(fn: () -> T): T {
        var last: IOException? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return fn()
            } catch (e: IOException) {
                last = e
                retries++
                sleepMs(RETRY_DELAY_MS.toLong())
            }
        }
        errors++
        throw last!!
    }

    /** Send bytes as one command transaction on the command address, retried on failure. */
    protected fun command(vararg values: Int) {
        val bytes = ByteArray(values.size) { values[it].toByte() }
        retry { cmd.write(bytes) }
    }

    /** One pass over the data in chunks of at most I2C_CHUNK bytes (throws on a NACK). */
    protected fun writeChunks(buf: ByteArray, off: Int, len: Int) {
        var i = 0
        while (i < len) {
            val n = minOf(I2C_CHUNK, len - i)
            data.write(buf.copyOfRange(off + i, off + i + n))
            i += n
        }
    }

    protected fun addressBytes(page: Int, column: Int): ByteArray {
        val pa = (pattern shl 5) or (page and 0x1F)
        return byteArrayOf(
            (CMD_PAGE_LO or (pa and 0x0F)).toByte(), (CMD_PAGE_HI or ((pa shr 4) and 0x07)).toByte(),
            (column and 0x0F).toByte(), (0x10 or (column shr 4)).toByte(),
        )
    }

    /** Write one page: address command, then the data; a failure repeats both. */
    protected fun writePage(page: Int, buf: ByteArray, off: Int, len: Int) {
        retry {
            cmd.write(addressBytes(page, 0))
            writeChunks(buf, off, len)
        }
    }

    protected fun showPage0(page: Int) {
        writePage(page, fb, page * width, width)
    }

    /** Read Status1..3; a NOP write first restarts the chip's status cycle (it advances by n + 1 per read). */
    protected fun readStatus0(): ByteArray = retry {
        cmd.write(byteArrayOf(CMD_NOP.toByte()))
        cmd.read(3)
    }

    /** Poll Status1.DE until the pump is up; NACKs while it starts are expected and not counted. */
    protected fun waitDisplayEnabled(timeoutMs: Int) {
        var waited = 0
        while (waited <= timeoutMs) {
            try {
                cmd.write(byteArrayOf(CMD_NOP.toByte()))
                val s = cmd.read(3)
                if ((s[0].toInt() and 0x08) != 0) return
            } catch (e: IOException) {
                // expected while the charge pump restarts
            }
            sleepMs(RETRY_DELAY_MS.toLong())
            waited += RETRY_DELAY_MS
        }
    }

    protected fun mapping(): Int = (my shl 2) or (mx shl 1)

    /** System Reset in its own transaction, then the conservative 150 ms wait. */
    protected fun resetChip() {
        command(CMD_RESET)
        sleepMs(150)
    }

    /** Configuration stream of the DOG bottom-view example (the display stays off). */
    protected fun configure() {
        val last = height - 1
        command(
            CMD_TEMP or 1,
            CMD_MAPPING, mapping(),
            CMD_LINE_RATE or 3,
            CMD_COM_END, last,
            CMD_PART_START, 0,
            CMD_PART_END, last,
            CMD_POTENTIOMETER, contrastValue,
            CMD_RAM_ADDR or 1,
            CMD_PATTERN or 1,
        )
    }

    protected fun enable() {
        command(CMD_DISPLAY or 1)
    }

    private fun initSequence() {
        sleepMs(150)
        resetChip()
        configure()
        val zeros = ByteArray(width)
        for (page in 0 until PAGES_RAM) writePage(page, zeros, 0, width)
        enable()
        waitDisplayEnabled(200)
    }
}
