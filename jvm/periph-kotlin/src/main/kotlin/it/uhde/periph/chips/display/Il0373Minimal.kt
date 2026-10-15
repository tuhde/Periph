package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * IL0373 black/white e-paper controller — minimal driver.
 *
 * Drives a 104 x 212 (up to 160 source x 296 gate) black/white electrophoretic
 * panel such as the Waveshare 2.13inch e-Paper (D). The driver keeps the picture
 * in a framebuffer and sends it on [show]. Every update follows the vendor
 * reference flow: `PON` (if the booster is off), `DTM1 + DTM2`, `DRF` and wait
 * on BUSY (active low), `CDI` border floating, `POF` and wait; [show] blocks for
 * the whole refresh.
 *
 * The connection is a [DisplayConn], which owns CS#, D/C#, RES# and BUSY (see
 * `specs/transport_display.md`); SIMPLEX wiring is enough.
 *
 * Pixel polarity: bit 1 = white, bit 0 = black (`CDI.DDX[0] = 1`, KW mode). The
 * controller drives a pixel only when its NEW and OLD bits differ, so the driver
 * sends OLD = NOT NEW: every pixel is driven to its final state and a refresh
 * does not depend on the previous picture.
 *
 * The constructor runs the initialisation sequence (vendor OTP reference flow):
 * hardware reset, wait for BUSY, `BTST 17 17 17`, `PON`, wait, `PSR 0x1F` (KW,
 * LUT from OTP, scan up, shift right, booster on), `TRES` and `CDI 0x97`. The
 * panel picture is left unchanged until [show].
 *
 * @param connection display connection
 * @param width pixels per row, a multiple of 8, 8..160
 * @param height rows, 1..296
 * @param busyTimeoutMs limit for every BUSY wait in milliseconds, `<= 0` for no
 *   limit (the panel datasheet gives no refresh time)
 * @throws IllegalArgumentException if width or height is out of range
 * @throws IOException if the bus fails or BUSY never goes high
 */
open class Il0373Minimal @JvmOverloads constructor(
    protected val connection: DisplayConn,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    protected val busyTimeoutMs: Long = 30_000L,
) {

    companion object {
        const val BLACK = 0
        const val WHITE = 1

        const val MAX_WIDTH = 160
        const val MAX_HEIGHT = 296
        const val DEFAULT_WIDTH = 104
        const val DEFAULT_HEIGHT = 212

        const val CMD_PSR = 0x00
        const val CMD_PWR = 0x01
        const val CMD_POF = 0x02
        const val CMD_PON = 0x04
        const val CMD_BTST = 0x06
        const val CMD_DSLP = 0x07
        const val CMD_DTM1 = 0x10
        const val CMD_DRF = 0x12
        const val CMD_DTM2 = 0x13
        const val CMD_LUTC = 0x20
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
    protected val fb: ByteArray
    protected var psr: Int = 0x1F
    protected var powered: Boolean = false
    protected var asleep: Boolean = false

    init {
        require(width in 8..MAX_WIDTH && width % 8 == 0) { "width must be a multiple of 8 in 8..$MAX_WIDTH" }
        require(height in 1..MAX_HEIGHT) { "height must be in 1..$MAX_HEIGHT" }
        rowBytes = width / 8
        size = rowBytes * height
        fb = ByteArray(size) { 0xFF.toByte() }
        initSequence()
    }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until [show].
     *
     * @param color [WHITE] or [BLACK]
     */
    fun clear(color: Int = WHITE) {
        fb.fill(if (color == WHITE) 0xFF.toByte() else 0x00)
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x column, 0 <= x < width
     * @param y row, 0 <= y < height
     * @param color [BLACK] or [WHITE]
     */
    fun setPixel(x: Int, y: Int, color: Int = BLACK) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val index = y * rowBytes + (x shr 3)
        val mask = 0x80 shr (x and 7)
        fb[index] = if (color == WHITE) (fb[index].toInt() or mask).toByte()
        else (fb[index].toInt() and mask.inv()).toByte()
    }

    /**
     * Write OLD and NEW data to the controller and run a full display update:
     * PON (if the booster is off), DTM1 (OLD = NOT NEW), DTM2 (NEW), DRF, wait
     * BUSY, CDI 0xF7 (border floating), POF, wait BUSY. A sleeping controller is
     * woken (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY wait exceeds the timeout
     */
    fun show() {
        if (asleep) wake()
        ensurePower()
        writePlanes()
        refreshPowerOff()
    }

    /**
     * Put the controller into deep sleep (border floating, `POF`, `DSLP` with
     * check code 0xA5). Only a hardware reset restores communication; [show] and
     * `Il0373Full.wake` do that. The panel keeps its image.
     *
     * @throws IOException on a bus error or BUSY timeout
     */
    fun sleep() {
        if (powered) powerOff()
        connection.sendCommand(CMD_DSLP, byteArrayOf(0xA5.toByte()))
        asleep = true
    }

    protected fun waitReady() {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw IOException("IL0373: BUSY did not go high within $busyTimeoutMs ms")
        }
    }

    /**
     * Let `PON` settle: wait up to 200 ms for BUSY high, ignoring a timeout. On the Waveshare
     * 2.13inch (D) module BUSY shows no clean ready after `PON` (it stays low or toggles until
     * the first refresh; the vendor demo waits a fixed 200 ms).
     */
    protected fun settle() {
        connection.waitBusy(false, 200L)
    }

    protected fun initSequence() {
        connection.reset()
        waitReady()
        connection.sendCommand(CMD_PWR, byteArrayOf(0x03, 0x00, 0x2B, 0x2B, 0x03))
        connection.sendCommand(CMD_BTST, byteArrayOf(0x17, 0x17, 0x17))
        connection.sendCommand(CMD_PON, ByteArray(0))
        settle()
        powered = true
        connection.sendCommand(CMD_PSR, byteArrayOf(psr.toByte()))
        connection.sendCommand(
            CMD_TRES,
            byteArrayOf((width and 0xF8).toByte(), ((height shr 8) and 1).toByte(), (height and 0xFF).toByte()),
        )
        connection.sendCommand(CMD_CDI, byteArrayOf(0x97.toByte()))
    }

    protected open fun wake() {
        asleep = false
        powered = false
        initSequence()
    }

    protected fun ensurePower() {
        if (powered) return
        connection.sendCommand(CMD_PON, ByteArray(0))
        settle()
        powered = true
        connection.sendCommand(CMD_CDI, byteArrayOf(0x97.toByte()))
    }

    protected fun powerOff() {
        connection.sendCommand(CMD_CDI, byteArrayOf(0xF7.toByte()))
        connection.sendCommand(CMD_POF, ByteArray(0))
        powered = false
        waitReady()
    }

    protected fun refreshPowerOff() {
        connection.sendCommand(CMD_DRF, ByteArray(0))
        waitReady()
        powerOff()
    }

    protected fun inverted(src: ByteArray, from: Int, to: Int): ByteArray =
        ByteArray(to - from) { (src[from + it].toInt().inv()).toByte() }

    protected fun writePlanes() {
        connection.sendCommand(CMD_DTM1, ByteArray(0))
        connection.sendData(inverted(fb, 0, size))
        connection.sendCommand(CMD_DTM2, ByteArray(0))
        connection.sendData(fb.copyOf())
    }
}
