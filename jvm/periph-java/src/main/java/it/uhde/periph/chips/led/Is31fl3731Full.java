package it.uhde.periph.chips.led;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * IS31FL3731 — 144-LED matrix driver (full driver): frames, auto-play, blink, breath, audio,
 * software shutdown and the INTB interrupt.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge of
 * INTB), otherwise a 50 ms polling thread; the callback receives FRAME_STATE.
 */
public class Is31fl3731Full extends Is31fl3731Minimal {

    /** 32-step gamma table for 256 PWM steps (datasheet Table 17). */
    public static final int[] GAMMA_32 = {
        0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
        78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255};

    /** 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended). */
    public static final int[] GAMMA_64 = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
        24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
        77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
        158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255};

    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private Thread pollThread;
    private volatile boolean polling;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the chip address (0x74–0x77)
     * @throws IOException on I²C error
     */
    public Is31fl3731Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /**
     * Read the PWM value of one LED from the shadow copy of the frame being drawn.
     *
     * @param row 0–8
     * @param col 0–15
     * @return PWM value, 0–255
     */
    public synchronized int getPixel(int row, int col) {
        checkPixel(row, col);
        return pwmCache[curFrame][16 * row + col] & 0xFF;
    }

    /**
     * Write a whole frame of PWM values (one block write).
     *
     * @param data 144 PWM values, row-major (row * 16 + col)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if data is not 144 bytes
     */
    public synchronized void setPixels(byte[] data) throws IOException {
        if (data.length != 144) throw new IllegalArgumentException("data must be 144 bytes");
        System.arraycopy(data, 0, pwmCache[curFrame], 0, 144);
        selectPage(curFrame);
        connection.write(REG_PWM, data.clone());
    }

    private void setBit(byte[][] cache, int base, int row, int col, boolean on) throws IOException {
        checkPixel(row, col);
        int idx = 2 * row + (col >> 3);
        int mask = 1 << (col & 7);
        int v = cache[curFrame][idx] & 0xFF;
        v = on ? (v | mask) : (v & ~mask & 0xFF);
        cache[curFrame][idx] = (byte) v;
        selectPage(curFrame);
        connection.write(base + idx, new byte[]{(byte) v});
    }

    /**
     * Set the LED-control (on/off) bit of one LED in the frame being drawn.
     *
     * @param row 0–8
     * @param col 0–15
     * @param on  true to enable the LED
     * @throws IOException on I²C error
     */
    public synchronized void setLedEnabled(int row, int col, boolean on) throws IOException {
        setBit(ctrlCache, REG_CTRL, row, col, on);
    }

    /**
     * Set the blink-enable bit of one LED in the frame being drawn (needs {@link #enableBlink}).
     *
     * @param row 0–8
     * @param col 0–15
     * @param on  true to let the LED blink
     * @throws IOException on I²C error
     */
    public synchronized void setBlink(int row, int col, boolean on) throws IOException {
        setBit(blinkCache, REG_BLINK, row, col, on);
    }

    /**
     * Set all 18 blink-control bytes of the frame being drawn.
     *
     * @param on true to enable blinking of every LED
     * @throws IOException on I²C error
     */
    public synchronized void setBlinkAll(boolean on) throws IOException {
        java.util.Arrays.fill(blinkCache[curFrame], (byte) (on ? 0xFF : 0x00));
        selectPage(curFrame);
        connection.write(REG_BLINK, blinkCache[curFrame].clone());
    }

    private void writeDisplay() throws IOException {
        funcWrite(FN_DISPLAY, (icBit << 5) | (beBit << 3) | bptBits);
    }

    /**
     * Enable or disable blinking (50 % duty).
     *
     * @param enabled true to enable
     * @param period  blink period in units of 0.27 s, 0–7
     * @throws IOException on I²C error
     */
    public synchronized void enableBlink(boolean enabled, int period) throws IOException {
        checkRange(period, "period", 7);
        beBit = enabled ? 1 : 0;
        bptBits = period;
        writeDisplay();
    }

    /**
     * Select the frame subsequent drawing calls write to (does not change the display).
     *
     * @param frame 0–7 (frame 1–8)
     * @throws IOException on I²C error
     */
    public synchronized void selectFrame(int frame) throws IOException {
        checkRange(frame, "frame", 7);
        curFrame = frame;
        selectPage(frame);
    }

    /**
     * Show a frame in Picture mode.
     *
     * @param frame 0–7 (frame 1–8)
     * @throws IOException on I²C error
     */
    public synchronized void showFrame(int frame) throws IOException {
        checkRange(frame, "frame", 7);
        funcWrite(FN_CONFIG, 0x00, frame);
    }

    /**
     * Initialise a frame: LED-control 0xFF, PWM and blink 0.
     *
     * @param frame 0–7
     * @throws IOException on I²C error
     */
    public synchronized void clearFrame(int frame) throws IOException {
        checkRange(frame, "frame", 7);
        initFrame(frame);
    }

    /**
     * Initialise all eight frames.
     *
     * @throws IOException on I²C error
     */
    public synchronized void clearAllFrames() throws IOException {
        for (int f = 0; f < 8; f++) initFrame(f);
    }

    /**
     * Copy the PWM values of one frame to another.
     *
     * @param src source frame, 0–7
     * @param dst destination frame, 0–7
     * @throws IOException on I²C error
     */
    public synchronized void copyFrame(int src, int dst) throws IOException {
        checkRange(src, "src", 7);
        checkRange(dst, "dst", 7);
        System.arraycopy(pwmCache[src], 0, pwmCache[dst], 0, 144);
        selectPage(dst);
        connection.write(REG_PWM, pwmCache[dst].clone());
    }

    /**
     * Switch to Picture mode (shows the frame selected by {@link #showFrame}).
     *
     * @throws IOException on I²C error
     */
    public synchronized void setPictureMode() throws IOException {
        funcWrite(FN_CONFIG, 0x00);
    }

    /**
     * Start Auto Frame Play.
     *
     * @param frames     frames played, 1–7, or 0 for all eight
     * @param loops      loops, 1–7, or 0 for endless
     * @param delayMs    frame delay in ms (rounded to a multiple of 11 ms, 11–704)
     * @param startFrame first frame, 0–7
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an argument is out of range
     */
    public synchronized void startAutoPlay(int frames, int loops, int delayMs, int startFrame) throws IOException {
        checkRange(frames, "frames", 7);
        checkRange(loops, "loops", 7);
        checkRange(startFrame, "startFrame", 7);
        int a = (int) Math.floor(delayMs / 11.0 + 0.5);
        if (a < 1 || a > 64) throw new IllegalArgumentException("delayMs must be in 11..704");
        funcWrite(FN_AUTOPLAY1, (loops << 4) | frames, a & 0x3F);
        funcWrite(FN_CONFIG, 0x08 | startFrame);
    }

    /**
     * Stop Auto Frame Play and keep showing the current frame (Picture mode).
     * Reading the current frame clears the movie-finished flag.
     *
     * @throws IOException on I²C error
     */
    public synchronized void stopAutoPlay() throws IOException {
        int frame = funcRead(FN_FRAME_STATE) & 0x07;
        funcWrite(FN_CONFIG, 0x00, frame);
    }

    /**
     * Switch Audio Frame Play on, or back to Picture mode.
     *
     * @param enabled true for Audio Frame Play
     * @throws IOException on I²C error
     */
    public synchronized void setAudioMode(boolean enabled) throws IOException {
        funcWrite(FN_CONFIG, enabled ? 0x10 : 0x00);
    }

    /**
     * Let the audio input modulate the matrix intensity.
     *
     * @param enabled true to enable
     * @throws IOException on I²C error
     */
    public synchronized void setAudioSync(boolean enabled) throws IOException {
        funcWrite(FN_AUDIO_SYNC, enabled ? 1 : 0);
    }

    /**
     * Configure the audio gain.
     *
     * @param gainDb 0, 3, … 21 dB
     * @param agc    true to enable automatic gain control
     * @param fast   true for fast AGC, false for slow
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if gainDb is not a multiple of 3 in 0..21
     */
    public synchronized void setAudioGain(int gainDb, boolean agc, boolean fast) throws IOException {
        if (gainDb < 0 || gainDb > 21 || gainDb % 3 != 0) {
            throw new IllegalArgumentException("gainDb must be a multiple of 3 in 0..21");
        }
        funcWrite(FN_AGC, ((fast ? 1 : 0) << 4) | ((agc ? 1 : 0) << 3) | (gainDb / 3));
    }

    /**
     * Set the audio ADC sample interval (46 µs × a, 0 = 46 µs × 256).
     *
     * @param a 0–255
     * @throws IOException on I²C error
     */
    public synchronized void setAudioRate(int a) throws IOException {
        checkRange(a, "a", 255);
        funcWrite(FN_AUDIO_RATE, a);
    }

    /**
     * Configure the breath (fade) function.
     *
     * @param enabled    true to enable
     * @param fadeIn     fade-in exponent, 0–7 (26 ms × 2^n)
     * @param fadeOut    fade-out exponent, 0–7 (26 ms × 2^n)
     * @param extinguish extinguish exponent, 0–7 (3.5 ms × 2^n)
     * @throws IOException on I²C error
     */
    public synchronized void setBreath(boolean enabled, int fadeIn, int fadeOut, int extinguish) throws IOException {
        checkRange(fadeIn, "fadeIn", 7);
        checkRange(fadeOut, "fadeOut", 7);
        checkRange(extinguish, "extinguish", 7);
        funcWrite(FN_BREATH1, (fadeOut << 4) | fadeIn, ((enabled ? 1 : 0) << 4) | extinguish);
    }

    /**
     * Choose whether frame 1's PWM values apply to all frames.
     *
     * @param useFrame1 true to use frame 1's intensity for every frame
     * @throws IOException on I²C error
     */
    public synchronized void setIntensityControl(boolean useFrame1) throws IOException {
        icBit = useFrame1 ? 1 : 0;
        writeDisplay();
    }

    /**
     * Enter or leave software shutdown (frame registers are kept).
     *
     * @param on true for shutdown, false for normal operation
     * @throws IOException on I²C error
     */
    public synchronized void shutdown(boolean on) throws IOException {
        funcWrite(FN_SHUTDOWN, on ? 0 : 1);
    }

    /**
     * Read FRAME_STATE (reading clears the movie-finished flag).
     *
     * @return bit 4 = movie finished, bits 2:0 = current frame
     * @throws IOException on I²C error
     */
    public synchronized int frameState() throws IOException {
        return funcRead(FN_FRAME_STATE);
    }

    /**
     * Frame being displayed.
     *
     * @return 0–7
     * @throws IOException on I²C error
     */
    public int currentFrame() throws IOException {
        return frameState() & 0x07;
    }

    /**
     * Whether an Auto Frame Play movie has finished (clears the flag).
     *
     * @return true if finished
     * @throws IOException on I²C error
     */
    public boolean movieFinished() throws IOException {
        return (frameState() & 0x10) != 0;
    }

    private void restore(int prev) throws IOException {
        if (prev >= 0 && prev != curPage) selectPage(prev);
    }

    /**
     * Read a function-page register and restore the selected page.
     *
     * @param reg register address, 0–0x0C
     * @return register value
     * @throws IOException on I²C error
     */
    public synchronized int readFunctionRegister(int reg) throws IOException {
        checkRange(reg, "reg", 0x0C);
        int prev = curPage;
        int v = funcRead(reg);
        restore(prev);
        return v;
    }

    /**
     * Write a function-page register and restore the selected page.
     *
     * @param reg   register address, 0–0x0C
     * @param value byte to write
     * @throws IOException on I²C error
     */
    public synchronized void writeFunctionRegister(int reg, int value) throws IOException {
        checkRange(reg, "reg", 0x0C);
        checkRange(value, "value", 255);
        int prev = curPage;
        funcWrite(reg, value);
        restore(prev);
    }

    /**
     * Read FRAME_STATE (clears INT) and restore the selected page.
     *
     * @return FRAME_STATE (INT = 0x10, current frame in bits 2:0)
     * @throws IOException on I²C error
     */
    public synchronized int pollInterrupt() throws IOException {
        int prev = curPage;
        int v = funcRead(FN_FRAME_STATE);
        restore(prev);
        return v;
    }

    /**
     * Subscribe to the end of an Auto Frame Play movie using {@code connection.intPin()}
     * (falling edge), or a 50 ms polling thread if none is wired.
     *
     * @param callback receives FRAME_STATE
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to INTB on an explicit pin.
     *
     * @param callback receives FRAME_STATE
     * @param intPin   pin to arm, or {@code null} to force the 50 ms polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleTick();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "is31fl3731-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            cb.accept(pollInterrupt());
        } catch (IOException e) {
            // transient bus error: the next edge retries
        }
    }

    private void handleTick() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if ((status & 0x10) != 0) cb.accept(status);
        } catch (IOException e) {
            // transient bus error: the next tick retries
        }
    }
}
