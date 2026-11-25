package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * IS31FL3731 — 144-LED matrix driver (full driver): frames, auto-play, blink, breath, audio,
 * software shutdown and the INTB interrupt.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge of
 * INTB), otherwise a 50 ms polling thread; the callback receives FRAME_STATE.
 */
@CompileStatic
class Is31fl3731Full extends Is31fl3731Minimal {

    /** 32-step gamma table for 256 PWM steps (datasheet Table 17). */
    static final int[] GAMMA_32 = [
        0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
        78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255] as int[]

    /** 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended). */
    static final int[] GAMMA_64 = [
        0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
        24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
        77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
        158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255] as int[]

    private volatile IntConsumer callback
    private InputPin subscribedPin
    private Thread pollThread
    private volatile boolean polling
    private final EdgeHandler edgeHandler = { handleEdge() } as EdgeHandler

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the chip address (0x74–0x77)
     */
    Is31fl3731Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Read the PWM value of one LED from the shadow copy of the frame being drawn.
     *
     * @return PWM value, 0–255
     */
    synchronized int getPixel(int row, int col) {
        checkPixel(row, col)
        pwmCache[curFrame][16 * row + col] & 0xFF
    }

    /**
     * Write a whole frame of PWM values (one block write).
     *
     * @param data 144 PWM values, row-major (row * 16 + col)
     */
    synchronized void setPixels(byte[] data) {
        if (data.length != 144) throw new IllegalArgumentException('data must be 144 bytes')
        System.arraycopy(data, 0, pwmCache[curFrame], 0, 144)
        selectPage(curFrame)
        connection.write(REG_PWM, copyOf(data))
    }

    private void setBit(byte[][] cache, int base, int row, int col, boolean on) {
        checkPixel(row, col)
        int idx = 2 * row + (col >> 3)
        int mask = 1 << (col & 7)
        int v = cache[curFrame][idx] & 0xFF
        v = on ? (v | mask) : (v & ~mask & 0xFF)
        cache[curFrame][idx] = (byte) v
        selectPage(curFrame)
        connection.write(base + idx, [(byte) v] as byte[])
    }

    /** Set the LED-control (on/off) bit of one LED in the frame being drawn. */
    synchronized void setLedEnabled(int row, int col, boolean on) { setBit(ctrlCache, REG_CTRL, row, col, on) }

    /** Set the blink-enable bit of one LED in the frame being drawn (needs {@link #enableBlink}). */
    synchronized void setBlink(int row, int col, boolean on) { setBit(blinkCache, REG_BLINK, row, col, on) }

    /** Set all 18 blink-control bytes of the frame being drawn. */
    synchronized void setBlinkAll(boolean on) {
        Arrays.fill(blinkCache[curFrame], (byte) (on ? 0xFF : 0x00))
        selectPage(curFrame)
        connection.write(REG_BLINK, copyOf(blinkCache[curFrame]))
    }

    private void writeDisplay() {
        funcWrite(FN_DISPLAY, [(icBit << 5) | (beBit << 3) | bptBits] as int[])
    }

    /**
     * Enable or disable blinking (50 % duty).
     *
     * @param enabled true to enable
     * @param period blink period in units of 0.27 s, 0–7
     */
    synchronized void enableBlink(boolean enabled, int period = 1) {
        checkRange(period, 'period', 7)
        beBit = enabled ? 1 : 0
        bptBits = period
        writeDisplay()
    }

    /** Select the frame (0–7) subsequent drawing calls write to (does not change the display). */
    synchronized void selectFrame(int frame) {
        checkRange(frame, 'frame', 7)
        curFrame = frame
        selectPage(frame)
    }

    /** Show a frame (0–7) in Picture mode. */
    synchronized void showFrame(int frame) {
        checkRange(frame, 'frame', 7)
        funcWrite(FN_CONFIG, [0x00, frame] as int[])
    }

    /** Initialise a frame (0–7): LED-control 0xFF, PWM and blink 0. */
    synchronized void clearFrame(int frame) {
        checkRange(frame, 'frame', 7)
        initFrame(frame)
    }

    /** Initialise all eight frames. */
    synchronized void clearAllFrames() {
        for (int f = 0; f < 8; f++) initFrame(f)
    }

    /** Copy the PWM values of frame {@code src} to frame {@code dst} (both 0–7). */
    synchronized void copyFrame(int src, int dst) {
        checkRange(src, 'src', 7)
        checkRange(dst, 'dst', 7)
        System.arraycopy(pwmCache[src], 0, pwmCache[dst], 0, 144)
        selectPage(dst)
        connection.write(REG_PWM, copyOf(pwmCache[dst]))
    }

    /** Switch to Picture mode (shows the frame selected by {@link #showFrame}). */
    synchronized void setPictureMode() { funcWrite(FN_CONFIG, [0x00] as int[]) }

    /**
     * Start Auto Frame Play.
     *
     * @param frames frames played, 1–7, or 0 for all eight
     * @param loops loops, 1–7, or 0 for endless
     * @param delayMs frame delay in ms (rounded to a multiple of 11 ms, 11–704)
     * @param startFrame first frame, 0–7
     */
    synchronized void startAutoPlay(int frames = 0, int loops = 0, int delayMs = 121, int startFrame = 0) {
        checkRange(frames, 'frames', 7)
        checkRange(loops, 'loops', 7)
        checkRange(startFrame, 'startFrame', 7)
        int a = (int) Math.floor(delayMs / 11.0d + 0.5d)
        if (a < 1 || a > 64) throw new IllegalArgumentException('delayMs must be in 11..704')
        funcWrite(FN_AUTOPLAY1, [(loops << 4) | frames, a & 0x3F] as int[])
        funcWrite(FN_CONFIG, [0x08 | startFrame] as int[])
    }

    /**
     * Stop Auto Frame Play and keep showing the current frame (Picture mode).
     * Reading the current frame clears the movie-finished flag.
     */
    synchronized void stopAutoPlay() {
        int frame = funcRead(FN_FRAME_STATE) & 0x07
        funcWrite(FN_CONFIG, [0x00, frame] as int[])
    }

    /** Switch Audio Frame Play on, or back to Picture mode. */
    synchronized void setAudioMode(boolean enabled) { funcWrite(FN_CONFIG, [enabled ? 0x10 : 0x00] as int[]) }

    /** Let the audio input modulate the matrix intensity. */
    synchronized void setAudioSync(boolean enabled) { funcWrite(FN_AUDIO_SYNC, [enabled ? 1 : 0] as int[]) }

    /**
     * Configure the audio gain.
     *
     * @param gainDb 0, 3, … 21 dB
     * @param agc true to enable automatic gain control
     * @param fast true for fast AGC, false for slow
     */
    synchronized void setAudioGain(int gainDb, boolean agc = false, boolean fast = false) {
        if (gainDb < 0 || gainDb > 21 || gainDb % 3 != 0) {
            throw new IllegalArgumentException('gainDb must be a multiple of 3 in 0..21')
        }
        funcWrite(FN_AGC, [((fast ? 1 : 0) << 4) | ((agc ? 1 : 0) << 3) | (gainDb.intdiv(3))] as int[])
    }

    /** Set the audio ADC sample interval (46 µs × a, 0 = 46 µs × 256), a 0–255. */
    synchronized void setAudioRate(int a) {
        checkRange(a, 'a', 255)
        funcWrite(FN_AUDIO_RATE, [a] as int[])
    }

    /**
     * Configure the breath (fade) function.
     *
     * @param enabled true to enable
     * @param fadeIn fade-in exponent, 0–7 (26 ms × 2^n)
     * @param fadeOut fade-out exponent, 0–7 (26 ms × 2^n)
     * @param extinguish extinguish exponent, 0–7 (3.5 ms × 2^n)
     */
    synchronized void setBreath(boolean enabled, int fadeIn = 0, int fadeOut = 0, int extinguish = 0) {
        checkRange(fadeIn, 'fadeIn', 7)
        checkRange(fadeOut, 'fadeOut', 7)
        checkRange(extinguish, 'extinguish', 7)
        funcWrite(FN_BREATH1, [(fadeOut << 4) | fadeIn, ((enabled ? 1 : 0) << 4) | extinguish] as int[])
    }

    /** Choose whether frame 1's PWM values apply to all frames. */
    synchronized void setIntensityControl(boolean useFrame1) {
        icBit = useFrame1 ? 1 : 0
        writeDisplay()
    }

    /** Enter (true) or leave (false) software shutdown; frame registers are kept. */
    synchronized void shutdown(boolean on = true) { funcWrite(FN_SHUTDOWN, [on ? 0 : 1] as int[]) }

    /** @return FRAME_STATE: bit 4 = movie finished, bits 2:0 = current frame (reading clears the flag) */
    synchronized int frameState() { funcRead(FN_FRAME_STATE) }

    /** @return the frame being displayed, 0–7 */
    int currentFrame() { frameState() & 0x07 }

    /** @return true if an Auto Frame Play movie has finished (clears the flag) */
    boolean movieFinished() { (frameState() & 0x10) != 0 }

    private void restore(int prev) {
        if (prev >= 0 && prev != curPage) selectPage(prev)
    }

    /** Read a function-page register (0–0x0C) and restore the selected page. */
    synchronized int readFunctionRegister(int reg) {
        checkRange(reg, 'reg', 0x0C)
        int prev = curPage
        int v = funcRead(reg)
        restore(prev)
        v
    }

    /** Write a function-page register (0–0x0C) and restore the selected page. */
    synchronized void writeFunctionRegister(int reg, int value) {
        checkRange(reg, 'reg', 0x0C)
        checkRange(value, 'value', 255)
        int prev = curPage
        funcWrite(reg, [value] as int[])
        restore(prev)
    }

    /**
     * Read FRAME_STATE (clears INT) and restore the selected page.
     *
     * @return FRAME_STATE (INT = 0x10, current frame in bits 2:0)
     */
    synchronized int pollInterrupt() {
        int prev = curPage
        int v = funcRead(FN_FRAME_STATE)
        restore(prev)
        v
    }

    /**
     * Subscribe to the end of an Auto Frame Play movie using {@code connection.intPin()}
     * (falling edge), or a 50 ms polling thread if none is wired.
     *
     * @param callback receives FRAME_STATE
     */
    void onInterrupt(IntConsumer callback) { onInterrupt(callback, connection.intPin()) }

    /**
     * Subscribe to INTB on an explicit pin.
     *
     * @param callback receives FRAME_STATE
     * @param intPin pin to arm, or null to force the 50 ms polling fallback
     */
    void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({
                while (polling) {
                    handleTick()
                    try {
                        Thread.sleep(50)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } as Runnable, 'is31fl3731-poll')
            pollThread.daemon = true
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback
        if (cb == null) return
        try {
            cb.accept(pollInterrupt())
        } catch (IOException ignored) {
            // transient bus error: the next edge retries
        }
    }

    private void handleTick() {
        IntConsumer cb = callback
        if (cb == null) return
        try {
            int status = pollInterrupt()
            if ((status & 0x10) != 0) cb.accept(status)
        } catch (IOException ignored) {
            // transient bus error: the next tick retries
        }
    }
}
