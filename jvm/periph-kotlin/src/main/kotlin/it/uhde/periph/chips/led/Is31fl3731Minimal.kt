package it.uhde.periph.chips.led

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * IS31FL3731 — 144-LED matrix driver, CharliePlex (minimal driver).
 *
 * Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture mode, drawing into
 * frame 1. I²C address 0x74–0x77 (AD pin).
 *
 * Defaults baked in at construction: software shutdown while configuring, Picture mode showing
 * frame 1, frame 1 initialised (all LED-control bits on, PWM and blink cleared), then normal
 * operation; LED current is set by R_EXT (3.2 mA at 20 kΩ). The driver keeps a shadow copy of
 * every frame because the chip only allows frame register reads in software shutdown. Bus access
 * is synchronized.
 *
 * @param connection I²C register connection bound to the chip address (0x74–0x77)
 * @throws IOException on I²C error during initialization
 */
open class Is31fl3731Minimal(protected val connection: RegisterConnection) {

    companion object {
        const val ROWS = 9
        const val COLS = 16

        const val REG_COMMAND = 0xFD
        const val PAGE_FUNCTION = 0x0B
        const val REG_CTRL = 0x00
        const val REG_BLINK = 0x12
        const val REG_PWM = 0x24

        const val FN_CONFIG = 0x00
        const val FN_AUTOPLAY1 = 0x02
        const val FN_DISPLAY = 0x05
        const val FN_AUDIO_SYNC = 0x06
        const val FN_FRAME_STATE = 0x07
        const val FN_BREATH1 = 0x08
        const val FN_SHUTDOWN = 0x0A
        const val FN_AGC = 0x0B
        const val FN_AUDIO_RATE = 0x0C

        @JvmStatic
        protected fun checkPixel(row: Int, col: Int) {
            require(row in 0..8) { "row must be in 0..8" }
            require(col in 0..15) { "col must be in 0..15" }
        }

        @JvmStatic
        protected fun checkRange(value: Int, name: String, max: Int) {
            require(value in 0..max) { "$name must be in 0..$max" }
        }
    }

    protected var curPage: Int = -1
    protected var curFrame: Int = 0
    protected var icBit: Int = 0
    protected var beBit: Int = 0
    protected var bptBits: Int = 0
    protected val pwmCache: Array<ByteArray> = Array(8) { ByteArray(144) }
    protected val ctrlCache: Array<ByteArray> = Array(8) { ByteArray(18) { 0xFF.toByte() } }
    protected val blinkCache: Array<ByteArray> = Array(8) { ByteArray(18) }

    init {
        funcWrite(FN_SHUTDOWN, 0x00)
        funcWrite(FN_CONFIG, 0x00, 0x00)
        funcWrite(FN_DISPLAY, 0x00)
        initFrame(0)
        funcWrite(FN_SHUTDOWN, 0x01)
    }

    @Throws(IOException::class)
    protected fun selectPage(page: Int) {
        if (curPage != page) {
            connection.write(REG_COMMAND, byteArrayOf(page.toByte()))
            curPage = page
        }
    }

    @Throws(IOException::class)
    protected fun funcWrite(reg: Int, vararg values: Int) {
        selectPage(PAGE_FUNCTION)
        connection.write(reg, ByteArray(values.size) { values[it].toByte() })
    }

    @Throws(IOException::class)
    protected fun funcRead(reg: Int): Int {
        selectPage(PAGE_FUNCTION)
        return connection.read(reg, 1)[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun initFrame(frame: Int) {
        ctrlCache[frame].fill(0xFF.toByte())
        blinkCache[frame].fill(0)
        pwmCache[frame].fill(0)
        selectPage(frame)
        connection.write(REG_CTRL, ctrlCache[frame].copyOf())
        connection.write(REG_BLINK, blinkCache[frame].copyOf())
        connection.write(REG_PWM, pwmCache[frame].copyOf())
    }

    /**
     * Set the PWM brightness of one LED in the frame being drawn.
     *
     * @param row common line, 0–8
     * @param col sink line, 0–15
     * @param brightness PWM value, 0–255
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an argument is out of range
     */
    @Synchronized
    @Throws(IOException::class)
    fun setPixel(row: Int, col: Int, brightness: Int) {
        checkPixel(row, col)
        checkRange(brightness, "brightness", 255)
        pwmCache[curFrame][16 * row + col] = brightness.toByte()
        selectPage(curFrame)
        connection.write(REG_PWM + 16 * row + col, byteArrayOf(brightness.toByte()))
    }

    /**
     * Set all 144 PWM values of the frame being drawn to 0 (one block write).
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun clear() = fill(0)

    /**
     * Set all 144 PWM values of the frame being drawn (one block write).
     *
     * @param brightness PWM value, 0–255
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if brightness is out of range
     */
    @Synchronized
    @Throws(IOException::class)
    fun fill(brightness: Int) {
        checkRange(brightness, "brightness", 255)
        pwmCache[curFrame].fill(brightness.toByte())
        selectPage(curFrame)
        connection.write(REG_PWM, pwmCache[curFrame].copyOf())
    }
}
