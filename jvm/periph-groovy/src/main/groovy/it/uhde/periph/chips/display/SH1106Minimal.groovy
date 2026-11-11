package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.util.Arrays

/**
 * SH1106 132 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * <p>Drives a monochrome SH1106 OLED panel (typically a 1.3" 128 x 64 module) over I2C at {@code 0x3C} (SA0 = VSS)
 * or {@code 0x3D} (SA0 = VDD1). The chip has 132 RAM columns; 128-pixel-wide modules show RAM columns 2..129, so
 * {@code columnOffset} defaults to 2.
 *
 * <p>All drawing goes into a framebuffer of {@code width * height / 8} bytes (page-major, bit 0 = top pixel of the
 * page); {@link #show()} sends it page by page (the chip has page addressing only). The initialisation follows the
 * datasheet power-on flow with the internal DC-DC: setup stream with the display off, DC-DC on + 100 ms, RAM
 * cleared, display on + 150 ms.
 *
 * <p>The same driver runs over 4-wire SPI as {@link SH1106SpiMinimal} / {@link SH1106SpiFull}.
 */
@CompileStatic
class SH1106Minimal {

    static final int WIDTH = 132
    static final int HEIGHT = 64
    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    static final int I2C_CHUNK = SH1106I2cBus.CHUNK

    protected static final int CMD_COL_LO = 0x00;       // | A[3:0]
    protected static final int CMD_COL_HI = 0x10;       // | A[7:4]
    protected static final int CMD_PUMP = 0x30;         // | A[1:0]
    protected static final int CMD_START_LINE = 0x40;   // | A[5:0]
    protected static final int CMD_CONTRAST = 0x81
    protected static final int CMD_SEG_REMAP = 0xA0;    // | ADC
    protected static final int CMD_ALL_ON = 0xA4;       // | 1
    protected static final int CMD_INVERT = 0xA6;       // | 1
    protected static final int CMD_MULTIPLEX = 0xA8
    protected static final int CMD_DCDC = 0xAD
    protected static final int CMD_DISPLAY = 0xAE;      // | 1
    protected static final int CMD_PAGE = 0xB0;         // | A[2:0]
    protected static final int CMD_SCAN = 0xC0;         // | (scan << 3)
    protected static final int CMD_OFFSET = 0xD3
    protected static final int CMD_CLOCK = 0xD5
    protected static final int CMD_PRECHARGE = 0xD9
    protected static final int CMD_COM = 0xDA
    protected static final int CMD_VCOM = 0xDB

    protected final SH1106Bus bus
    protected final int width
    protected final int height
    protected final int pages
    protected final int offset
    protected final byte[] fb
    protected boolean useDcdc
    protected int adc
    protected int scan

    /**
     * Construct the driver and run the initialisation sequence (about 250 ms).
     *
     * @param connection   connection at 0x3C or 0x3D
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 8..64
     * @param columnOffset first RAM column shown (2 for 128-pixel modules, 0 for a true 132-column panel)
     * @param internalDcdc true for the internal charge pump, false for an external VPP
     * @throws IllegalArgumentException if width, height or columnOffset is out of range
     * @throws IOException              if a transfer fails
     */
    SH1106Minimal(Connection connection, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        this(new SH1106I2cBus(connection), width, height, columnOffset, internalDcdc)
    }

    /** Construct with the defaults 128 x 64, column offset 2 and the internal DC-DC. */
    SH1106Minimal(Connection connection) throws IOException {
        this(connection, 128, HEIGHT, 2, true)
    }

    /** Construct on an explicit transport (used by the SPI classes). */
    protected SH1106Minimal(SH1106Bus bus, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        if (width < 1 || width > WIDTH) throw new IllegalArgumentException("width must be 1..132")
        if (height < 8 || height > HEIGHT || height % 8 != 0) {
            throw new IllegalArgumentException("height must be a multiple of 8, 8..64")
        }
        if (columnOffset < 0 || columnOffset + width > WIDTH) {
            throw new IllegalArgumentException("columnOffset + width must be at most 132")
        }
        this.bus = bus
        this.width = width
        this.height = height
        this.pages = height.intdiv(8)
        this.offset = columnOffset
        this.useDcdc = internalDcdc
        this.fb = new byte[width * pages]
        initSequence()
    }

    /** @return the visible columns of the framebuffer */
    int width() {
        return width
    }

    /** @return the rows of the framebuffer */
    int height() {
        return height
    }

    /** Zero the framebuffer. Call {@link #show()} to update the panel. */
    void clear() {
        Arrays.fill(fb, (byte) 0)
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x  column, 0 .. width - 1
     * @param y  row, 0 .. height - 1
     * @param on true to set the pixel, false to clear it
     */
    void setPixel(int x, int y, boolean on) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        int i = (y >> 3) * width + x
        int mask = 1 << (y & 7)
        fb[i] = (byte) (on ? (fb[i] | mask) : (fb[i] & ~mask))
    }

    /** Set one pixel in the framebuffer. */
    void setPixel(int x, int y) {
        setPixel(x, y, true)
    }

    /**
     * Write the whole framebuffer to the display RAM (page + column command, then {@code width} data bytes per page).
     *
     * @throws IOException if a transfer fails
     */
    void show() throws IOException {
        for (int page = 0; page < pages; page++) showPage0(page)
    }

    // ---- internals ------------------------------------------------------------------------

    protected static void sleepMs(long ms) throws IOException {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IOException("interrupted", e)
        }
    }

    protected void command(int... values) throws IOException {
        byte[] b = new byte[values.length]
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i]
        bus.writeCommands(b)
    }

    protected byte[] addressBytes(int page, int column) {
        return [(byte) (CMD_PAGE | (page & 7)), (byte) (CMD_COL_LO | (column & 0x0F)),                            (byte) (CMD_COL_HI | ((column >> 4) & 0x0F))] as byte[]
    }

    protected void showPage0(int page) throws IOException {
        bus.writeCommands(addressBytes(page, offset))
        bus.writeData(fb, page * width, width)
    }

    /** Datasheet power-on flow: setup stream, DC-DC, RAM clear, display on. */
    private void initSequence() throws IOException {
        bus.hardwareReset()
        sleepMs(1)
        command(CMD_DISPLAY,
                CMD_CLOCK, 0x50,
                CMD_MULTIPLEX, height - 1,
                CMD_OFFSET, 0x00,
                CMD_START_LINE,
                CMD_SEG_REMAP | adc,
                CMD_SCAN | (scan << 3),
                CMD_COM, 0x12,
                CMD_CONTRAST, 0x80,
                CMD_PRECHARGE, 0x22,
                CMD_VCOM, 0x35,
                CMD_PUMP,
                CMD_ALL_ON,
                CMD_INVERT)
        if (useDcdc) {
            command(CMD_DCDC, 0x8B)
            sleepMs(100)
        } else {
            command(CMD_DCDC, 0x8A)
        }
        byte[] blank = new byte[WIDTH]
        for (int page = 0; page < 8; page++) {
            bus.writeCommands(addressBytes(page, 0))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY | 1)
        sleepMs(useDcdc ? 150 : 50)
    }
}
