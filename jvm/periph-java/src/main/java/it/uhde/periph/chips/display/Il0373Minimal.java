package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;
import java.io.IOException;
import java.util.Arrays;

/**
 * IL0373 black/white e-paper controller — minimal driver.
 *
 * <p>Drives a 104 x 212 (up to 160 source x 296 gate) black/white
 * electrophoretic panel such as the Waveshare 2.13inch e-Paper (D). The driver
 * keeps the picture in a framebuffer and sends it on {@link #show()}. Every
 * update follows the vendor reference flow: {@code PON} (if the booster is
 * off), {@code DTM1 + DTM2}, {@code DRF} and wait on BUSY (active low),
 * {@code CDI} border floating, {@code POF} and wait; {@code show()} blocks for
 * the whole refresh.
 *
 * <p>The connection is a {@link DisplayConn}, which owns CS#, D/C#, RES# and
 * BUSY (see {@code specs/transport_display.md}); SIMPLEX wiring is enough.
 *
 * <p>Pixel polarity: bit 1 = white, bit 0 = black ({@code CDI.DDX[0] = 1}, KW
 * mode). The controller drives a pixel only when its NEW and OLD bits differ,
 * so the driver sends OLD = NOT NEW: every pixel is driven to its final state
 * and a refresh does not depend on the previous picture.
 */
public class Il0373Minimal {

    public static final int BLACK = 0;
    public static final int WHITE = 1;

    public static final int MAX_WIDTH = 160;
    public static final int MAX_HEIGHT = 296;
    public static final int DEFAULT_WIDTH = 104;
    public static final int DEFAULT_HEIGHT = 212;

    protected static final int CMD_PSR = 0x00;
    protected static final int CMD_PWR = 0x01;
    protected static final int CMD_POF = 0x02;
    protected static final int CMD_PON = 0x04;
    protected static final int CMD_BTST = 0x06;
    protected static final int CMD_DSLP = 0x07;
    protected static final int CMD_DTM1 = 0x10;
    protected static final int CMD_DRF = 0x12;
    protected static final int CMD_DTM2 = 0x13;
    protected static final int CMD_LUTC = 0x20;
    protected static final int CMD_PLL = 0x30;
    protected static final int CMD_TSC = 0x40;
    protected static final int CMD_TSE = 0x41;
    protected static final int CMD_CDI = 0x50;
    protected static final int CMD_LPD = 0x51;
    protected static final int CMD_TRES = 0x61;
    protected static final int CMD_REV = 0x70;
    protected static final int CMD_FLG = 0x71;
    protected static final int CMD_VV = 0x81;
    protected static final int CMD_VDCS = 0x82;
    protected static final int CMD_PTL = 0x90;
    protected static final int CMD_PTIN = 0x91;
    protected static final int CMD_PTOUT = 0x92;
    protected static final int CMD_ROTP = 0xA2;

    protected final DisplayConn connection;
    protected final int width;
    protected final int height;
    protected final int rowBytes;
    protected final int size;
    protected final long busyTimeoutMs;
    protected final byte[] fb;
    protected int psr = 0x1F;
    protected boolean powered = false;
    protected boolean asleep = false;

    /**
     * Construct a 104 x 212 driver with a 30 s BUSY timeout and run the
     * initialisation sequence.
     *
     * @param connection display connection
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Il0373Minimal(DisplayConn connection) throws IOException {
        this(connection, DEFAULT_WIDTH, DEFAULT_HEIGHT, 30_000L);
    }

    /**
     * Construct the driver and run the initialisation sequence (vendor OTP
     * reference flow): hardware reset, wait for BUSY, {@code BTST 17 17 17},
     * {@code PON}, wait, {@code PSR 0x1F} (KW, LUT from OTP, scan up, shift
     * right, booster on), {@code TRES} and {@code CDI 0x97}. The panel picture
     * is left unchanged until {@link #show()}.
     *
     * @param connection     display connection
     * @param width          pixels per row, a multiple of 8, 8..160
     * @param height         rows, 1..296
     * @param busyTimeoutMs  limit for every BUSY wait in milliseconds,
     *                       {@code <= 0} for no limit (the panel datasheet
     *                       gives no refresh time)
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Il0373Minimal(DisplayConn connection, int width, int height, long busyTimeoutMs)
            throws IOException {
        if (width < 8 || width > MAX_WIDTH || width % 8 != 0) {
            throw new IllegalArgumentException("width must be a multiple of 8 in 8.." + MAX_WIDTH);
        }
        if (height < 1 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height must be in 1.." + MAX_HEIGHT);
        }
        this.connection = connection;
        this.width = width;
        this.height = height;
        this.rowBytes = width / 8;
        this.size = rowBytes * height;
        this.busyTimeoutMs = busyTimeoutMs;
        this.fb = new byte[size];
        Arrays.fill(fb, (byte) 0xFF);
        initSequence();
    }

    /** @return panel width in pixels */
    public int width() { return width; }

    /** @return panel height in pixels */
    public int height() { return height; }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until
     * {@link #show()}.
     *
     * @param color {@link #WHITE} or {@link #BLACK}
     */
    public void clear(int color) {
        Arrays.fill(fb, color == WHITE ? (byte) 0xFF : (byte) 0x00);
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x     column, 0 &lt;= x &lt; width
     * @param y     row, 0 &lt;= y &lt; height
     * @param color {@link #BLACK} or {@link #WHITE}
     */
    public void setPixel(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int index = y * rowBytes + (x >> 3);
        int mask = 0x80 >> (x & 7);
        if (color == WHITE) fb[index] |= (byte) mask; else fb[index] &= (byte) ~mask;
    }

    /**
     * Write OLD and NEW data to the controller and run a full display update:
     * PON (if the booster is off), DTM1 (OLD = NOT NEW), DTM2 (NEW), DRF, wait
     * BUSY, CDI 0xF7 (border floating), POF, wait BUSY. A sleeping controller
     * is woken (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY wait exceeds the timeout
     */
    public void show() throws IOException {
        if (asleep) wake();
        ensurePower();
        writePlanes();
        refreshPowerOff();
    }

    /**
     * Put the controller into deep sleep (border floating, {@code POF},
     * {@code DSLP} with check code {@code 0xA5}). Only a hardware reset
     * restores communication; {@link #show()} and {@code Il0373Full.wake()} do
     * that. The panel keeps its image.
     *
     * @throws IOException on a bus error or BUSY timeout
     */
    public void sleep() throws IOException {
        if (powered) powerOff();
        connection.sendCommand(CMD_DSLP, new byte[] {(byte) 0xA5});
        asleep = true;
    }

    protected void waitReady() throws IOException {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw new IOException("IL0373: BUSY did not go high within " + busyTimeoutMs + " ms");
        }
    }

    protected void initSequence() throws IOException {
        connection.reset();
        waitReady();
        connection.sendCommand(CMD_BTST, new byte[] {0x17, 0x17, 0x17});
        connection.sendCommand(CMD_PON, new byte[0]);
        waitReady();
        powered = true;
        connection.sendCommand(CMD_PSR, new byte[] {(byte) psr});
        connection.sendCommand(CMD_TRES, new byte[] {
            (byte) (width & 0xF8), (byte) ((height >> 8) & 1), (byte) (height & 0xFF)});
        connection.sendCommand(CMD_CDI, new byte[] {(byte) 0x97});
    }

    protected void wake() throws IOException {
        asleep = false;
        powered = false;
        initSequence();
    }

    protected void ensurePower() throws IOException {
        if (powered) return;
        connection.sendCommand(CMD_PON, new byte[0]);
        waitReady();
        powered = true;
        connection.sendCommand(CMD_CDI, new byte[] {(byte) 0x97});
    }

    protected void powerOff() throws IOException {
        connection.sendCommand(CMD_CDI, new byte[] {(byte) 0xF7});
        connection.sendCommand(CMD_POF, new byte[0]);
        powered = false;
        waitReady();
    }

    protected void refreshPowerOff() throws IOException {
        connection.sendCommand(CMD_DRF, new byte[0]);
        waitReady();
        powerOff();
    }

    protected static byte[] inverted(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ~src[from + i];
        return out;
    }

    protected void writePlanes() throws IOException {
        connection.sendCommand(CMD_DTM1, new byte[0]);
        connection.sendData(inverted(fb, 0, size));
        connection.sendCommand(CMD_DTM2, new byte[0]);
        connection.sendData(Arrays.copyOf(fb, size));
    }
}
