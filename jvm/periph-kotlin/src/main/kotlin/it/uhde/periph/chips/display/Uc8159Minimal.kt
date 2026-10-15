package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * UC8159 seven-colour e-paper controller — minimal driver.
 *
 * Drives a 600 x 448 ACeP seven-colour electrophoretic panel such as the
 * Waveshare 5.65inch e-Paper (F). The driver keeps the picture in a framebuffer
 * in wire format (4 bits per pixel, 134 400 bytes) and sends it on [show].
 * Every update is `DTM1`, `PON`, wait BUSY (active low), `DRF`, wait, `POF`,
 * wait; [show] blocks for the whole refresh (about 30 s).
 *
 * The connection is a [DisplayConn], which owns CS#, D/C#, RES# and BUSY (see
 * `specs/transport_display.md`); SIMPLEX wiring is enough.
 *
 * The constructor runs the initialisation sequence (the vendor demo's
 * commands); the panel picture is left unchanged until [show].
 *
 * @param connection display connection
 * @param busyTimeoutMs limit for every BUSY wait in milliseconds, `<= 0` for no
 *   limit (a refresh takes about 30 s)
 * @throws IOException if the bus fails or BUSY never goes high
 */
open class Uc8159Minimal @JvmOverloads constructor(
    protected val connection: DisplayConn,
    protected val busyTimeoutMs: Long = 60_000L,
) {

    companion object {
        const val BLACK = 0
        const val WHITE = 1
        const val GREEN = 2
        const val BLUE = 3
        const val RED = 4
        const val YELLOW = 5
        const val ORANGE = 6
        const val CLEAN = 7

        const val WIDTH = 600
        const val HEIGHT = 448
        const val FRAME_BYTES = WIDTH * HEIGHT / 2

        const val CMD_PSR = 0x00
        const val CMD_PWR = 0x01
        const val CMD_POF = 0x02
        const val CMD_PFS = 0x03
        const val CMD_PON = 0x04
        const val CMD_BTST = 0x06
        const val CMD_DSLP = 0x07
        const val CMD_DTM1 = 0x10
        const val CMD_DRF = 0x12
        const val CMD_PLL = 0x30
        const val CMD_TSC = 0x40
        const val CMD_TSE = 0x41
        const val CMD_CDI = 0x50
        const val CMD_LPD = 0x51
        const val CMD_TCON = 0x60
        const val CMD_TRES = 0x61
        const val CMD_PWS = 0xE3
        const val CMD_FLG = 0x71
        const val CMD_VV = 0x81
        const val CMD_VDCS = 0x82
    }

    val width: Int get() = WIDTH
    val height: Int get() = HEIGHT

    protected val fb: ByteArray = ByteArray(FRAME_BYTES) { 0x11 }
    protected var psr: Int = 0xEF
    protected var cdi: Int = 0x37
    protected var asleep: Boolean = false

    init {
        initSequence()
    }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until [show].
     *
     * @param color colour code 0..7
     */
    fun clear(color: Int = WHITE) {
        val c = color and 7
        fb.fill(((c shl 4) or c).toByte())
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x column, 0 <= x < 600
     * @param y row, 0 <= y < 448
     * @param color colour code 0..7
     */
    fun setPixel(x: Int, y: Int, color: Int = BLACK) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return
        val i = (y * WIDTH + x) shr 1
        val c = color and 7
        val b = fb[i].toInt() and 0xFF
        fb[i] = (if (x and 1 == 1) (b and 0xF0) or c else (b and 0x0F) or (c shl 4)).toByte()
    }

    /**
     * Send the frame and run a full display update: DTM1 + 134 400 bytes, PON,
     * wait BUSY, DRF, wait BUSY, POF, wait BUSY. A sleeping controller is woken
     * (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY wait exceeds the timeout
     */
    fun show() {
        if (asleep) wake()
        connection.sendCommand(CMD_DTM1, ByteArray(0))
        var off = 0
        while (off < FRAME_BYTES) {
            connection.sendData(fb.copyOfRange(off, minOf(off + 4096, FRAME_BYTES)))
            off += 4096
        }
        connection.sendCommand(CMD_PON, ByteArray(0))
        waitReady()
        connection.sendCommand(CMD_DRF, ByteArray(0))
        waitReady()
        connection.sendCommand(CMD_POF, ByteArray(0))
        waitReady()
    }

    /**
     * Put the controller into deep sleep (`DSLP` with check code 0xA5). Only a
     * hardware reset restores communication; [show] and `Uc8159Full.wake` do that.
     *
     * @throws IOException on a bus error
     */
    fun sleep() {
        connection.sendCommand(CMD_DSLP, byteArrayOf(0xA5.toByte()))
        asleep = true
    }

    protected fun waitReady() {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw IOException("UC8159: BUSY did not go high within $busyTimeoutMs ms")
        }
    }

    protected fun initSequence() {
        connection.reset()
        waitReady()
        connection.sendCommand(CMD_PSR, byteArrayOf(psr.toByte(), 0x08))
        connection.sendCommand(CMD_PWR, byteArrayOf(0x37, 0x00, 0x23, 0x23))
        connection.sendCommand(CMD_PFS, byteArrayOf(0x00))
        connection.sendCommand(CMD_BTST, byteArrayOf(0xC7.toByte(), 0xC7.toByte(), 0x1D))
        connection.sendCommand(CMD_PLL, byteArrayOf(0x3C))
        connection.sendCommand(CMD_TSE, byteArrayOf(0x00))
        connection.sendCommand(CMD_CDI, byteArrayOf(cdi.toByte()))
        connection.sendCommand(CMD_TCON, byteArrayOf(0x22))
        connection.sendCommand(CMD_TRES, byteArrayOf(0x02, 0x58, 0x01, 0xC0.toByte()))
        connection.sendCommand(CMD_PWS, byteArrayOf(0xAA.toByte()))
    }

    protected open fun wake() {
        asleep = false
        initSequence()
    }
}
