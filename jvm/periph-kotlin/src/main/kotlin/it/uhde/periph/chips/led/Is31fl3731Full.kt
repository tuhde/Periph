package it.uhde.periph.chips.led

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * IS31FL3731 — 144-LED matrix driver (full driver): frames, auto-play, blink, breath, audio,
 * software shutdown and the INTB interrupt.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge of INTB), otherwise a 50 ms
 * polling thread; the callback receives FRAME_STATE.
 *
 * @param connection I²C register connection bound to the chip address (0x74–0x77)
 */
class Is31fl3731Full(connection: RegisterConnection) : Is31fl3731Minimal(connection) {

    companion object {
        /** 32-step gamma table for 256 PWM steps (datasheet Table 17). */
        @JvmField
        val GAMMA_32 = intArrayOf(
            0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
            78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255)

        /** 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended). */
        @JvmField
        val GAMMA_64 = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
            24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
            77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
            158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255)
    }

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Read the PWM value of one LED from the shadow copy of the frame being drawn.
     *
     * @return PWM value, 0–255
     */
    @Synchronized
    fun getPixel(row: Int, col: Int): Int {
        checkPixel(row, col)
        return pwmCache[curFrame][16 * row + col].toInt() and 0xFF
    }

    /**
     * Write a whole frame of PWM values (one block write).
     *
     * @param data 144 PWM values, row-major (row * 16 + col)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if data is not 144 bytes
     */
    @Synchronized
    @Throws(IOException::class)
    fun setPixels(data: ByteArray) {
        require(data.size == 144) { "data must be 144 bytes" }
        data.copyInto(pwmCache[curFrame])
        selectPage(curFrame)
        connection.write(REG_PWM, data.copyOf())
    }

    @Throws(IOException::class)
    private fun setBit(cache: Array<ByteArray>, base: Int, row: Int, col: Int, on: Boolean) {
        checkPixel(row, col)
        val idx = 2 * row + (col shr 3)
        val mask = 1 shl (col and 7)
        var v = cache[curFrame][idx].toInt() and 0xFF
        v = if (on) v or mask else v and mask.inv() and 0xFF
        cache[curFrame][idx] = v.toByte()
        selectPage(curFrame)
        connection.write(base + idx, byteArrayOf(v.toByte()))
    }

    /**
     * Set the LED-control (on/off) bit of one LED in the frame being drawn.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setLedEnabled(row: Int, col: Int, on: Boolean) = setBit(ctrlCache, REG_CTRL, row, col, on)

    /**
     * Set the blink-enable bit of one LED in the frame being drawn (needs [enableBlink]).
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setBlink(row: Int, col: Int, on: Boolean) = setBit(blinkCache, REG_BLINK, row, col, on)

    /**
     * Set all 18 blink-control bytes of the frame being drawn.
     *
     * @param on true to enable blinking of every LED
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setBlinkAll(on: Boolean) {
        blinkCache[curFrame].fill(if (on) 0xFF.toByte() else 0)
        selectPage(curFrame)
        connection.write(REG_BLINK, blinkCache[curFrame].copyOf())
    }

    @Throws(IOException::class)
    private fun writeDisplay() = funcWrite(FN_DISPLAY, (icBit shl 5) or (beBit shl 3) or bptBits)

    /**
     * Enable or disable blinking (50 % duty).
     *
     * @param enabled true to enable
     * @param period blink period in units of 0.27 s, 0–7
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun enableBlink(enabled: Boolean, period: Int = 1) {
        checkRange(period, "period", 7)
        beBit = if (enabled) 1 else 0
        bptBits = period
        writeDisplay()
    }

    /**
     * Select the frame subsequent drawing calls write to (does not change the display).
     *
     * @param frame 0–7 (frame 1–8)
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun selectFrame(frame: Int) {
        checkRange(frame, "frame", 7)
        curFrame = frame
        selectPage(frame)
    }

    /**
     * Show a frame in Picture mode.
     *
     * @param frame 0–7 (frame 1–8)
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun showFrame(frame: Int) {
        checkRange(frame, "frame", 7)
        funcWrite(FN_CONFIG, 0x00, frame)
    }

    /**
     * Initialise a frame: LED-control 0xFF, PWM and blink 0.
     *
     * @param frame 0–7
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun clearFrame(frame: Int) {
        checkRange(frame, "frame", 7)
        initFrame(frame)
    }

    /**
     * Initialise all eight frames.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun clearAllFrames() {
        for (f in 0..7) initFrame(f)
    }

    /**
     * Copy the PWM values of one frame to another.
     *
     * @param src source frame, 0–7
     * @param dst destination frame, 0–7
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun copyFrame(src: Int, dst: Int) {
        checkRange(src, "src", 7)
        checkRange(dst, "dst", 7)
        pwmCache[src].copyInto(pwmCache[dst])
        selectPage(dst)
        connection.write(REG_PWM, pwmCache[dst].copyOf())
    }

    /**
     * Switch to Picture mode (shows the frame selected by [showFrame]).
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setPictureMode() = funcWrite(FN_CONFIG, 0x00)

    /**
     * Start Auto Frame Play.
     *
     * @param frames frames played, 1–7, or 0 for all eight
     * @param loops loops, 1–7, or 0 for endless
     * @param delayMs frame delay in ms (rounded to a multiple of 11 ms, 11–704)
     * @param startFrame first frame, 0–7
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an argument is out of range
     */
    @Synchronized
    @Throws(IOException::class)
    fun startAutoPlay(frames: Int = 0, loops: Int = 0, delayMs: Int = 121, startFrame: Int = 0) {
        checkRange(frames, "frames", 7)
        checkRange(loops, "loops", 7)
        checkRange(startFrame, "startFrame", 7)
        val a = Math.floor(delayMs / 11.0 + 0.5).toInt()
        require(a in 1..64) { "delayMs must be in 11..704" }
        funcWrite(FN_AUTOPLAY1, (loops shl 4) or frames, a and 0x3F)
        funcWrite(FN_CONFIG, 0x08 or startFrame)
    }

    /**
     * Stop Auto Frame Play and keep showing the current frame (Picture mode).
     * Reading the current frame clears the movie-finished flag.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun stopAutoPlay() {
        val frame = funcRead(FN_FRAME_STATE) and 0x07
        funcWrite(FN_CONFIG, 0x00, frame)
    }

    /**
     * Switch Audio Frame Play on, or back to Picture mode.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setAudioMode(enabled: Boolean) = funcWrite(FN_CONFIG, if (enabled) 0x10 else 0x00)

    /**
     * Let the audio input modulate the matrix intensity.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setAudioSync(enabled: Boolean) = funcWrite(FN_AUDIO_SYNC, if (enabled) 1 else 0)

    /**
     * Configure the audio gain.
     *
     * @param gainDb 0, 3, … 21 dB
     * @param agc true to enable automatic gain control
     * @param fast true for fast AGC, false for slow
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if gainDb is not a multiple of 3 in 0..21
     */
    @Synchronized
    @Throws(IOException::class)
    fun setAudioGain(gainDb: Int, agc: Boolean = false, fast: Boolean = false) {
        require(gainDb in 0..21 && gainDb % 3 == 0) { "gainDb must be a multiple of 3 in 0..21" }
        funcWrite(FN_AGC, ((if (fast) 1 else 0) shl 4) or ((if (agc) 1 else 0) shl 3) or (gainDb / 3))
    }

    /**
     * Set the audio ADC sample interval (46 µs × a, 0 = 46 µs × 256).
     *
     * @param a 0–255
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setAudioRate(a: Int) {
        checkRange(a, "a", 255)
        funcWrite(FN_AUDIO_RATE, a)
    }

    /**
     * Configure the breath (fade) function.
     *
     * @param enabled true to enable
     * @param fadeIn fade-in exponent, 0–7 (26 ms × 2^n)
     * @param fadeOut fade-out exponent, 0–7 (26 ms × 2^n)
     * @param extinguish extinguish exponent, 0–7 (3.5 ms × 2^n)
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setBreath(enabled: Boolean, fadeIn: Int = 0, fadeOut: Int = 0, extinguish: Int = 0) {
        checkRange(fadeIn, "fadeIn", 7)
        checkRange(fadeOut, "fadeOut", 7)
        checkRange(extinguish, "extinguish", 7)
        funcWrite(FN_BREATH1, (fadeOut shl 4) or fadeIn, ((if (enabled) 1 else 0) shl 4) or extinguish)
    }

    /**
     * Choose whether frame 1's PWM values apply to all frames.
     *
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun setIntensityControl(useFrame1: Boolean) {
        icBit = if (useFrame1) 1 else 0
        writeDisplay()
    }

    /**
     * Enter or leave software shutdown (frame registers are kept).
     *
     * @param on true for shutdown, false for normal operation
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun shutdown(on: Boolean = true) = funcWrite(FN_SHUTDOWN, if (on) 0 else 1)

    /**
     * Read FRAME_STATE (reading clears the movie-finished flag).
     *
     * @return bit 4 = movie finished, bits 2:0 = current frame
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun frameState(): Int = funcRead(FN_FRAME_STATE)

    /**
     * Frame being displayed, 0–7.
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun currentFrame(): Int = frameState() and 0x07

    /**
     * Whether an Auto Frame Play movie has finished (clears the flag).
     *
     * @throws IOException on I²C error
     */
    @Throws(IOException::class)
    fun movieFinished(): Boolean = (frameState() and 0x10) != 0

    @Throws(IOException::class)
    private fun restore(prev: Int) {
        if (prev >= 0 && prev != curPage) selectPage(prev)
    }

    /**
     * Read a function-page register and restore the selected page.
     *
     * @param reg register address, 0–0x0C
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun readFunctionRegister(reg: Int): Int {
        checkRange(reg, "reg", 0x0C)
        val prev = curPage
        val v = funcRead(reg)
        restore(prev)
        return v
    }

    /**
     * Write a function-page register and restore the selected page.
     *
     * @param reg register address, 0–0x0C
     * @param value byte to write
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun writeFunctionRegister(reg: Int, value: Int) {
        checkRange(reg, "reg", 0x0C)
        checkRange(value, "value", 255)
        val prev = curPage
        funcWrite(reg, value)
        restore(prev)
    }

    /**
     * Read FRAME_STATE (clears INT) and restore the selected page.
     *
     * @return FRAME_STATE (INT = 0x10, current frame in bits 2:0)
     * @throws IOException on I²C error
     */
    @Synchronized
    @Throws(IOException::class)
    fun pollInterrupt(): Int {
        val prev = curPage
        val v = funcRead(FN_FRAME_STATE)
        restore(prev)
        return v
    }

    /**
     * Subscribe to the end of an Auto Frame Play movie on [intPin] (falling edge; defaults to
     * `connection.intPin()`), or a 50 ms polling thread if no pin is wired.
     *
     * @param callback receives FRAME_STATE
     * @param intPin pin to arm, or null to force the polling fallback
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin?) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleTick()
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "is31fl3731-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /**
     * Subscribe to the end of an Auto Frame Play movie using `connection.intPin()` (falling edge),
     * or a 50 ms polling thread if none is wired.
     *
     * @param callback receives FRAME_STATE
     */
    fun onInterrupt(callback: IntConsumer) = onInterrupt(callback, connection.intPin())

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            cb.accept(pollInterrupt())
        } catch (e: IOException) {
            // transient bus error: the next edge retries
        }
    }

    private fun handleTick() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status and 0x10 != 0) cb.accept(status)
        } catch (e: IOException) {
            // transient bus error: the next tick retries
        }
    }
}
