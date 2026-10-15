package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * UC8276C black/white/red e-paper controller — minimal driver.
 *
 * <p>Drives a 400 x 300 (up to 400 source x 300 gate) white/black/red
 * electrophoretic panel such as the Waveshare 4.2inch e-Paper (B). The driver
 * keeps the picture in framebuffers and sends it on {@link #show()}. Every
 * update is the datasheet sequence {@code DTM1 + DTM2 -> PON -> DRF -> POF},
 * waiting on BUSY_N (active low) after each power/refresh step; {@code show()}
 * blocks for the whole refresh (about 17 s on the reference panel).
 *
 * <p>The connection is a {@link DisplayConn}, which owns CS#, D/C#, RES# and
 * BUSY_N (see {@code specs/transport_display.md}); SIMPLEX wiring is enough.
 *
 * <p>Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 =
 * red) holds for CDI.DDX = 01, which the driver writes explicitly at
 * initialisation instead of relying on the OTP default.
 */
@CompileStatic
class Uc8276cMinimal {

    static final int BLACK = 0
    static final int WHITE = 1
    static final int RED = 2

    static final int MODE_KWR = 0
    static final int MODE_KW = 1

    static final int MAX_WIDTH = 400
    static final int MAX_HEIGHT = 300

    protected static final int CMD_PSR = 0x00
    protected static final int CMD_PWR = 0x01
    protected static final int CMD_POF = 0x02
    protected static final int CMD_PON = 0x04
    protected static final int CMD_BTST = 0x06
    protected static final int CMD_DSLP = 0x07
    protected static final int CMD_DTM1 = 0x10
    protected static final int CMD_DRF = 0x12
    protected static final int CMD_DTM2 = 0x13
    protected static final int CMD_AUTO = 0x17
    protected static final int CMD_LUTC = 0x20
    protected static final int CMD_LUTWW = 0x21
    protected static final int CMD_LUTKW = 0x22
    protected static final int CMD_LUTWK = 0x23
    protected static final int CMD_LUTKK = 0x24
    protected static final int CMD_PLL = 0x30
    protected static final int CMD_TSC = 0x40
    protected static final int CMD_TSE = 0x41
    protected static final int CMD_CDI = 0x50
    protected static final int CMD_LPD = 0x51
    protected static final int CMD_TRES = 0x61
    protected static final int CMD_REV = 0x70
    protected static final int CMD_FLG = 0x71
    protected static final int CMD_VV = 0x81
    protected static final int CMD_VDCS = 0x82
    protected static final int CMD_PTL = 0x90
    protected static final int CMD_PTIN = 0x91
    protected static final int CMD_PTOUT = 0x92
    protected static final int CMD_ROTP = 0xA2

    protected final DisplayConn connection
    protected final int width
    protected final int height
    protected final int rowBytes
    protected final int size
    protected final long busyTimeoutMs
    protected final byte[] bw
    protected byte[] red // null until the first red pixel
    protected int psr = 0x0F
    protected boolean asleep = false

    /**
     * Construct a 400 x 300 driver with a 30 s BUSY_N timeout and run the
     * initialisation sequence.
     *
     * @param connection display connection
     */
    Uc8276cMinimal(DisplayConn connection) {
        this(connection, MAX_WIDTH, MAX_HEIGHT, 30000L)
    }

    /**
     * Construct the driver and run the initialisation sequence: hardware
     * reset, wait for BUSY_N, PSR {@code 0x0F} (KWR, LUT from OTP, scan up,
     * shift right, booster on), TRES when the panel is not 400 x 300, and CDI
     * {@code 0xD7}. The panel picture is left unchanged until {@link #show()}.
     *
     * @param connection    display connection
     * @param width         pixels per row, a multiple of 8, 8..400
     * @param height        rows, 1..300
     * @param busyTimeoutMs limit for every BUSY_N wait in milliseconds,
     *                      {@code <= 0} for no limit
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException if the bus fails or BUSY_N never goes high
     */
    Uc8276cMinimal(DisplayConn connection, int width, int height, long busyTimeoutMs) {
        if (width < 8 || width > MAX_WIDTH || width % 8 != 0) {
            throw new IllegalArgumentException("width must be a multiple of 8 in 8..${MAX_WIDTH}".toString())
        }
        if (height < 1 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("height must be in 1..${MAX_HEIGHT}".toString())
        }
        this.connection = connection
        this.width = width
        this.height = height
        this.rowBytes = width.intdiv(8)
        this.size = rowBytes * height
        this.busyTimeoutMs = busyTimeoutMs
        this.bw = new byte[size]
        Arrays.fill(bw, (byte) 0xFF)
        initSequence()
    }

    /** @return panel width in pixels */
    int width() { width }

    /** @return panel height in pixels */
    int height() { height }

    /**
     * Fill the framebuffers with one colour. The panel is unchanged until
     * {@link #show()}.
     *
     * @param color {@link #WHITE}, {@link #BLACK} or {@link #RED}
     */
    void clear(int color = WHITE) {
        Arrays.fill(bw, color == WHITE ? (byte) 0xFF : (byte) 0x00)
        if (color == RED) {
            red = new byte[size]
            Arrays.fill(red, (byte) 0xFF)
        } else if (red != null) {
            Arrays.fill(red, (byte) 0x00)
        }
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * The red plane is allocated on the first {@link #RED} pixel.
     *
     * @param x     column, 0 &lt;= x &lt; width
     * @param y     row, 0 &lt;= y &lt; height
     * @param color {@link #BLACK}, {@link #WHITE} or {@link #RED}
     */
    void setPixel(int x, int y, int color = BLACK) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        int index = y * rowBytes + (x >> 3)
        int mask = 0x80 >> (x & 7)
        if (color == WHITE) {
            bw[index] = (byte) (bw[index] | mask)
        } else {
            bw[index] = (byte) (bw[index] & ~mask)
        }
        if (color == RED) {
            if (red == null) red = new byte[size]
            red[index] = (byte) (red[index] | mask)
        } else if (red != null) {
            red[index] = (byte) (red[index] & ~mask)
        }
    }

    /**
     * Write both planes to the controller and run a full display update:
     * DTM1, DTM2, PON, wait BUSY_N, DRF, wait BUSY_N, POF, wait BUSY_N. A
     * sleeping controller is woken (hardware reset + initialisation) first.
     *
     * @throws IOException on a bus error or when a BUSY_N wait exceeds the timeout
     */
    void show() {
        if (asleep) wake()
        writePlanes()
        powerCycleRefresh()
    }

    /**
     * Put the controller into deep sleep ({@code DSLP} with check code
     * {@code 0xA5}). Only a hardware reset restores communication; {@link
     * #show()} and {@code Uc8276cFull.wake()} do that. The panel keeps its image.
     *
     * @throws IOException on a bus error
     */
    void sleep() {
        connection.sendCommand(CMD_DSLP, [(byte) 0xA5] as byte[])
        asleep = true
    }

    protected void waitReady() {
        if (!connection.waitBusy(false, busyTimeoutMs)) {
            throw new IOException("UC8276C: BUSY_N did not go high within ${busyTimeoutMs} ms".toString())
        }
    }

    protected void initSequence() {
        connection.reset()
        waitReady()
        connection.sendCommand(CMD_PSR, [(byte) psr] as byte[])
        if (width != MAX_WIDTH || height != MAX_HEIGHT) {
            connection.sendCommand(CMD_TRES, [
                (byte) ((width >> 8) & 1), (byte) (width & 0xF8),
                (byte) ((height >> 8) & 1), (byte) (height & 0xFF)] as byte[])
        }
        // VBD = 11, DDX = 01, CDI = 0111: the framebuffer polarity holds
        connection.sendCommand(CMD_CDI, [(byte) 0xD7] as byte[])
    }

    protected void wake() {
        asleep = false
        initSequence()
    }

    protected void sendZeros(int total) {
        int left = total
        byte[] zeros = new byte[512]
        while (left > 0) {
            int n = Math.min(left, zeros.length)
            connection.sendData(n == zeros.length ? zeros : Arrays.copyOf(zeros, n))
            left -= n
        }
    }

    protected void sendPlane(int cmd, byte[] plane) {
        connection.sendCommand(cmd, new byte[0])
        if (plane == null) sendZeros(size) else connection.sendData(plane)
    }

    protected void writePlanes() {
        sendPlane(CMD_DTM1, bw)
        sendPlane(CMD_DTM2, red)
    }

    protected void powerCycleRefresh() {
        connection.sendCommand(CMD_PON, new byte[0])
        waitReady()
        connection.sendCommand(CMD_DRF, new byte[0])
        waitReady()
        connection.sendCommand(CMD_POF, new byte[0])
        waitReady()
    }
}
