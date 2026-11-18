package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * IS31FL3731 — 144-LED matrix driver, CharliePlex (minimal driver).
 *
 * <p>Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture mode, drawing into
 * frame 1. I²C address 0x74–0x77 (AD pin).
 *
 * <p>Defaults baked in at construction: software shutdown while configuring, Picture mode showing
 * frame 1, frame 1 initialised (all LED-control bits on, PWM and blink cleared), then normal
 * operation; LED current is set by R_EXT (3.2 mA at 20 kΩ). The driver keeps a shadow copy of
 * every frame because the chip only allows frame register reads in software shutdown. Bus access
 * is synchronized.
 */
@CompileStatic
class Is31fl3731Minimal {

    static final int ROWS = 9
    static final int COLS = 16

    protected static final int REG_COMMAND = 0xFD
    protected static final int PAGE_FUNCTION = 0x0B
    protected static final int REG_CTRL = 0x00
    protected static final int REG_BLINK = 0x12
    protected static final int REG_PWM = 0x24

    protected static final int FN_CONFIG = 0x00
    protected static final int FN_AUTOPLAY1 = 0x02
    protected static final int FN_DISPLAY = 0x05
    protected static final int FN_AUDIO_SYNC = 0x06
    protected static final int FN_FRAME_STATE = 0x07
    protected static final int FN_BREATH1 = 0x08
    protected static final int FN_SHUTDOWN = 0x0A
    protected static final int FN_AGC = 0x0B
    protected static final int FN_AUDIO_RATE = 0x0C

    protected final RegisterConnection connection
    protected int curPage = -1
    protected int curFrame = 0
    protected int icBit = 0
    protected int beBit = 0
    protected int bptBits = 0
    protected final byte[][] pwmCache = new byte[8][144]
    protected final byte[][] ctrlCache = new byte[8][18]
    protected final byte[][] blinkCache = new byte[8][18]

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the chip address (0x74–0x77)
     */
    Is31fl3731Minimal(RegisterConnection connection) {
        this.connection = connection
        for (int f = 0; f < 8; f++) Arrays.fill(ctrlCache[f], (byte) 0xFF)
        funcWrite(FN_SHUTDOWN, [0x00] as int[])
        funcWrite(FN_CONFIG, [0x00, 0x00] as int[])
        funcWrite(FN_DISPLAY, [0x00] as int[])
        initFrame(0)
        funcWrite(FN_SHUTDOWN, [0x01] as int[])
    }

    protected void selectPage(int page) {
        if (curPage != page) {
            connection.write(REG_COMMAND, [(byte) page] as byte[])
            curPage = page
        }
    }

    protected void funcWrite(int reg, int[] values) {
        selectPage(PAGE_FUNCTION)
        byte[] data = new byte[values.length]
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i]
        connection.write(reg, data)
    }

    protected int funcRead(int reg) {
        selectPage(PAGE_FUNCTION)
        connection.read(reg, 1)[0] & 0xFF
    }

    protected void initFrame(int frame) {
        Arrays.fill(ctrlCache[frame], (byte) 0xFF)
        Arrays.fill(blinkCache[frame], (byte) 0)
        Arrays.fill(pwmCache[frame], (byte) 0)
        selectPage(frame)
        connection.write(REG_CTRL, copyOf(ctrlCache[frame]))
        connection.write(REG_BLINK, copyOf(blinkCache[frame]))
        connection.write(REG_PWM, copyOf(pwmCache[frame]))
    }

    protected static byte[] copyOf(byte[] a) { Arrays.copyOf(a, a.length) }

    protected static void checkPixel(int row, int col) {
        if (row < 0 || row > 8) throw new IllegalArgumentException('row must be in 0..8')
        if (col < 0 || col > 15) throw new IllegalArgumentException('col must be in 0..15')
    }

    protected static void checkRange(int value, String name, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("$name must be in 0..$max".toString())
    }

    /**
     * Set the PWM brightness of one LED in the frame being drawn.
     *
     * @param row common line, 0–8
     * @param col sink line, 0–15
     * @param brightness PWM value, 0–255
     */
    synchronized void setPixel(int row, int col, int brightness) {
        checkPixel(row, col)
        checkRange(brightness, 'brightness', 255)
        pwmCache[curFrame][16 * row + col] = (byte) brightness
        selectPage(curFrame)
        connection.write(REG_PWM + 16 * row + col, [(byte) brightness] as byte[])
    }

    /** Set all 144 PWM values of the frame being drawn to 0 (one block write). */
    void clear() { fill(0) }

    /**
     * Set all 144 PWM values of the frame being drawn (one block write).
     *
     * @param brightness PWM value, 0–255
     */
    synchronized void fill(int brightness) {
        checkRange(brightness, 'brightness', 255)
        Arrays.fill(pwmCache[curFrame], (byte) brightness)
        selectPage(curFrame)
        connection.write(REG_PWM, copyOf(pwmCache[curFrame]))
    }
}
