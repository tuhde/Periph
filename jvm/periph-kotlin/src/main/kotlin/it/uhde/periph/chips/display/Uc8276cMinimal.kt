package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * UC8276C black/white/red e-paper controller — minimal driver.
 *
 * Drives a 400 x 300 (up to 400 source x 300 gate) white/black/red
 * electrophoretic panel such as the Waveshare 4.2inch e-Paper (B). The driver
 * keeps the picture in framebuffers and sends it on [show]. Every update is the
 * datasheet sequence `DTM1 + DTM2 -> PON -> DRF -> POF`, waiting on BUSY_N
 * (active low) after each power/refresh step; [show] blocks for the whole
 * refresh (about 17 s on the reference panel).
 *
 * The connection is a [DisplayConn], which owns CS#, D/C#, RES# and BUSY_N (see
 * `specs/transport_display.md`); SIMPLEX wiring is enough.
 *
 * Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 = red)
 * holds for CDI.DDX = 01, which the driver writes explicitly at initialisation
 * instead of relying on the OTP default.
 *
 * The constructor runs the initialisation sequence: hardware reset, wait for
 * BUSY_N, PSR 0x0F (KWR, LUT from OTP, scan up, shift right, booster on), TRES
 * when the panel is not 400 x 300, and CDI 0xD7. The panel picture is left
 * unchanged until [show].
 *
 * @param connection display connection
 * @param width pixels per row, a multiple of 8, 8..400
 * @param height rows, 1..300
 * @param busyTimeoutMs limit for every BUSY_N wait in milliseconds, `<= 0` for
 *   no limit (the full refresh was measured at 16.8 s)
 * @throws IllegalArgumentException if width or height is out of range
 * @throws IOException if the bus fails or BUSY_N never goes high
 */
open class Uc8276cMinimal @JvmOverloads constructor(
    protected val connection: DisplayConn,
    val width: Int = MAX_WIDTH,
    val height: Int = MAX_HEIGHT,
    protected val busyTimeoutMs: Long = 30_000L,
) {

    companion object {
        const val BLACK = 0
        const val WHITE = 1
        const val RED = 2

        const val MODE_KWR = 0
        const val MODE_KW = 1

        const val MAX_WIDTH = 400
        const val MAX_HEIGHT = 300

        const val CMD_PSR = 0x00
        const val CMD_PWR = 0x01
        const val CMD_POF = 0x02
        const val CMD_PON = 0x04
        const val CMD_BTST = 0x06
        const val CMD_DSLP = 0x07
        const val CMD_DTM1 = 0x10
        const val CMD_DRF = 0x12
        const val CMD_DTM2 = 0x13
        const val CMD_AUTO = 0x17
        const val CMD_LUTC = 0x20
        const val CMD_LUTWW = 0x21
        const val CMD_LUTKW = 0x22
        const val CMD_LUTWK = 0x23
        const val CMD_LUTKK = 0x24
        const val CMD_PLL = 0x30
        const val CMD_TSC = 0x40
        const val CMD_TSE = 0x41
        const val CMD_CDI = 0x50
        const val CMD_LPD = 0x51
        const val CMD_TRES = 0x61
        const val CMD_REV = 0x70
        const val CMD_FLG = 0x71
        const val CMD_VV = 0x81
        const val CMD_VDCS = 0x82
        const val CMD_PTL = 0x90
        const val CMD_PTIN = 0x91
        const val CMD_PTOUT = 0x92
        const val CMD_ROTP = 0xA2
    }

    protected val rowBytes: Int
    protected val size: Int
    protected val bw: ByteArray
    protected var red: ByteArray? = null // null until the first red pixel
    protected var psr: Int = 0x0F
    protected var asleep: Boolean = false

    init {
        require(width in 8..MAX_WIDTH && width % 8 == 0) { "width must be a multiple of 8 in 8..$MAX_WIDTH" }
        require(height in 1..MAX_HEIGHT) { "height must be in 1..$MAX_HEIGHT" }
        rowBytes = width / 8
        size = rowBytes * height
        bw = ByteArray(size) { 0xFF.toByte() }
        initSequence()
    }

    /**
     * Fill the framebuffers with one colour. The panel is unchanged until [show].
     *
     * @param color [WHITE], [BLACK] or [RED]
     */
    fun clear(color: Int = WHITE) {
        bw.fill(if (color == WHITE) 0xFF.toByte() else 0x00)
        if (color == RED) {
            red = ByteArray(size) { 0xFF.toByte() }
        } else {
            red?.fill(0x00)
        }
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * The red plane is allocated on the first [RED] pixel.
     *
     * @param x column, 0 <= x < width
     * @param y row, 0 <= y < height
     * @param color [BLACK], [WHITE] or [RED]
     */
    fun setPixel(x: Int, y: Int, color: Int = BLACK) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val index = y * rowBytes + (x shr 3)
        val mask = 0x80 shr (x and 7)
        bw[index] = if (color == WHITE) (bw[index].toInt() or mask).toByte()
        else (bw[index].toInt() and mask.inv()).toByte()
        if (color == RED) {
            val plane = red ?: ByteArray(size).also { red = it }
            plane[index] = (plane[index].toInt() or mask).toByte()
        } else {
            red?.let { it[index] = (it[index].toInt() and mask.inv()).toByte() }
        }
    }

    /**
     * Write both planes to the controller and run a full display update: DTM1,
     * DTM2, PON, wait BUSY_N, DRF, wait BUSY_N, POF, wait BUSY_N. A sleeping
     * controller is woken (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY_N wait exceeds the timeout
     */
    fun show() {
        if (asleep) wake()
        writePlanes()
        powerCycleRefresh()
    }

    /**
     * Put the controller into deep sleep (`DSLP` with check code 0xA5). Only a
     * hardware reset restores communication; [show] and `Uc8276cFull.wake` do
     * that. The panel keeps its image.
     *
     * @throws IOException on a bus error
     */
    fun sleep() {
        connection.sendCommand(CMD_DSLP, byteArrayOf(0xA5.toByte()))
        asleep = true
    }

    protected fun waitReady() {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw IOException("UC8276C: BUSY_N did not go high within $busyTimeoutMs ms")
        }
    }

    protected fun initSequence() {
        connection.reset()
        waitReady()
        connection.sendCommand(CMD_PSR, byteArrayOf(psr.toByte()))
        if (width != MAX_WIDTH || height != MAX_HEIGHT) {
            connection.sendCommand(
                CMD_TRES,
                byteArrayOf(
                    ((width shr 8) and 1).toByte(), (width and 0xF8).toByte(),
                    ((height shr 8) and 1).toByte(), (height and 0xFF).toByte(),
                ),
            )
        }
        // VBD = 11, DDX = 01, CDI = 0111: the framebuffer polarity holds
        connection.sendCommand(CMD_CDI, byteArrayOf(0xD7.toByte()))
    }

    protected open fun wake() {
        asleep = false
        initSequence()
    }

    protected fun sendZeros(total: Int) {
        var left = total
        val zeros = ByteArray(512)
        while (left > 0) {
            val n = minOf(left, zeros.size)
            connection.sendData(if (n == zeros.size) zeros else zeros.copyOf(n))
            left -= n
        }
    }

    protected fun sendPlane(cmd: Int, plane: ByteArray?) {
        connection.sendCommand(cmd, ByteArray(0))
        if (plane == null) sendZeros(size) else connection.sendData(plane)
    }

    protected fun writePlanes() {
        sendPlane(CMD_DTM1, bw)
        sendPlane(CMD_DTM2, red)
    }

    protected fun powerCycleRefresh() {
        connection.sendCommand(CMD_PON, ByteArray(0))
        waitReady()
        connection.sendCommand(CMD_DRF, ByteArray(0))
        waitReady()
        connection.sendCommand(CMD_POF, ByteArray(0))
        waitReady()
    }
}
