package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * UC8159 seven-colour e-paper controller — minimal driver.
 *
 * <p>Drives a 600 x 448 ACeP seven-colour electrophoretic panel such as the
 * Waveshare 5.65inch e-Paper (F). The driver keeps the picture in a framebuffer
 * in wire format (4 bits per pixel, 134 400 bytes) and sends it on
 * {@link #show()}. Every update is {@code DTM1}, {@code PON}, wait BUSY (active
 * low), {@code DRF}, wait, {@code POF}, wait; {@code show()} blocks for the
 * whole refresh (about 30 s).
 *
 * <p>The connection is a {@link DisplayConn}, which owns CS#, D/C#, RES# and
 * BUSY (see {@code specs/transport_display.md}); SIMPLEX wiring is enough.
 */
@CompileStatic
class Uc8159Minimal {

    static final int BLACK = 0
    static final int WHITE = 1
    static final int GREEN = 2
    static final int BLUE = 3
    static final int RED = 4
    static final int YELLOW = 5
    static final int ORANGE = 6
    static final int CLEAN = 7

    static final int WIDTH = 600
    static final int HEIGHT = 448
    static final int FRAME_BYTES = 134400

    protected static final int CMD_PSR = 0x00
    protected static final int CMD_PWR = 0x01
    protected static final int CMD_POF = 0x02
    protected static final int CMD_PFS = 0x03
    protected static final int CMD_PON = 0x04
    protected static final int CMD_BTST = 0x06
    protected static final int CMD_DSLP = 0x07
    protected static final int CMD_DTM1 = 0x10
    protected static final int CMD_DRF = 0x12
    protected static final int CMD_PLL = 0x30
    protected static final int CMD_TSC = 0x40
    protected static final int CMD_TSE = 0x41
    protected static final int CMD_CDI = 0x50
    protected static final int CMD_LPD = 0x51
    protected static final int CMD_TCON = 0x60
    protected static final int CMD_TRES = 0x61
    protected static final int CMD_PWS = 0xE3
    protected static final int CMD_FLG = 0x71
    protected static final int CMD_VV = 0x81
    protected static final int CMD_VDCS = 0x82

    protected final DisplayConn connection
    protected final long busyTimeoutMs
    protected final byte[] fb = new byte[FRAME_BYTES]
    protected int psr = 0xEF
    protected int cdi = 0x37
    protected boolean asleep = false

    /**
     * Construct the driver with a 60 s BUSY timeout and run the initialisation sequence.
     *
     * @param connection display connection
     */
    Uc8159Minimal(DisplayConn connection) {
        this(connection, 60000L)
    }

    /**
     * Construct the driver and run the initialisation sequence (the vendor
     * demo's commands). The panel picture is left unchanged until {@link #show()}.
     *
     * @param connection    display connection
     * @param busyTimeoutMs limit for every BUSY wait in milliseconds, {@code <= 0} for no limit
     * @throws IOException if the bus fails or BUSY never goes high
     */
    Uc8159Minimal(DisplayConn connection, long busyTimeoutMs) {
        this.connection = connection
        this.busyTimeoutMs = busyTimeoutMs
        Arrays.fill(fb, (byte) 0x11)
        initSequence()
    }

    /** @return panel width in pixels (600) */
    int width() { WIDTH }

    /** @return panel height in pixels (448) */
    int height() { HEIGHT }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until {@link #show()}.
     *
     * @param color colour code 0..7
     */
    void clear(int color = WHITE) {
        int c = color & 7
        Arrays.fill(fb, (byte) ((c << 4) | c))
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x     column, 0 &lt;= x &lt; 600
     * @param y     row, 0 &lt;= y &lt; 448
     * @param color colour code 0..7
     */
    void setPixel(int x, int y, int color = BLACK) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return
        int i = (y * WIDTH + x) >> 1
        int c = color & 7
        int b = fb[i] & 0xFF
        fb[i] = (byte) ((x & 1) == 1 ? ((b & 0xF0) | c) : ((b & 0x0F) | (c << 4)))
    }

    /**
     * Send the frame and run a full display update: DTM1 + 134 400 bytes, PON,
     * wait BUSY, DRF, wait BUSY, POF, wait BUSY. A sleeping controller is woken
     * (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY wait exceeds the timeout
     */
    void show() {
        if (asleep) wake()
        connection.sendCommand(CMD_DTM1, new byte[0])
        for (int off = 0; off < FRAME_BYTES; off += 4096) {
            connection.sendData(Arrays.copyOfRange(fb, off, Math.min(off + 4096, FRAME_BYTES)))
        }
        connection.sendCommand(CMD_PON, new byte[0])
        waitReady()
        connection.sendCommand(CMD_DRF, new byte[0])
        waitReady()
        connection.sendCommand(CMD_POF, new byte[0])
        waitReady()
    }

    /**
     * Put the controller into deep sleep ({@code DSLP} with check code
     * {@code 0xA5}). Only a hardware reset restores communication; {@link
     * #show()} and {@code Uc8159Full.wake()} do that.
     *
     * @throws IOException on a bus error
     */
    void sleep() {
        connection.sendCommand(CMD_DSLP, [(byte) 0xA5] as byte[])
        asleep = true
    }

    protected void waitReady() {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw new IOException("UC8159: BUSY did not go high within ${busyTimeoutMs} ms".toString())
        }
    }

    protected void initSequence() {
        connection.reset()
        waitReady()
        connection.sendCommand(CMD_PSR, [(byte) psr, (byte) 0x08] as byte[])
        connection.sendCommand(CMD_PWR, [(byte) 0x37, (byte) 0x00, (byte) 0x23, (byte) 0x23] as byte[])
        connection.sendCommand(CMD_PFS, [(byte) 0x00] as byte[])
        connection.sendCommand(CMD_BTST, [(byte) 0xC7, (byte) 0xC7, (byte) 0x1D] as byte[])
        connection.sendCommand(CMD_PLL, [(byte) 0x3C] as byte[])
        connection.sendCommand(CMD_TSE, [(byte) 0x00] as byte[])
        connection.sendCommand(CMD_CDI, [(byte) cdi] as byte[])
        connection.sendCommand(CMD_TCON, [(byte) 0x22] as byte[])
        connection.sendCommand(CMD_TRES, [(byte) 0x02, (byte) 0x58, (byte) 0x01, (byte) 0xC0] as byte[])
        connection.sendCommand(CMD_PWS, [(byte) 0xAA] as byte[])
    }

    protected void wake() {
        asleep = false
        initSequence()
    }
}
