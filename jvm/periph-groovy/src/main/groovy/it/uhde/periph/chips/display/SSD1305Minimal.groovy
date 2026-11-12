package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.util.Arrays

/**
 * SSD1305 132 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * <p>Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I2C at {@code 0x3C}
 * (SA0 = low) or {@code 0x3D} (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide modules usually show RAM
 * columns 4..131, so {@code columnOffset} defaults to 4.
 *
 * <p>All drawing goes into a framebuffer of {@code width * height / 8} bytes (page-major, bit 0 = top pixel of the
 * page); {@link #show()} sends it page by page (page addressing, the reset default). The chip has no charge pump:
 * the OLED supply VCC (7..15 V) must be supplied by the board. The initialisation follows the datasheet power-on
 * flow: setup stream with the display off, RAM cleared, display on + 100 ms.
 *
 * <p>The same driver runs over 4-wire SPI as {@link SSD1305SpiMinimal} / {@link SSD1305SpiFull}.
 */
@CompileStatic
class SSD1305Minimal {

    static final int WIDTH = 132
    static final int HEIGHT = 64
    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    static final int I2C_CHUNK = SSD1305I2cBus.CHUNK

    protected static final int CMD_COL_LO = 0x00;       // | A[3:0]
    protected static final int CMD_COL_HI = 0x10;       // | A[7:4]
    protected static final int CMD_ADDR_MODE = 0x20
    protected static final int CMD_COL_RANGE = 0x21
    protected static final int CMD_PAGE_RANGE = 0x22
    protected static final int CMD_START_LINE = 0x40;   // | A[5:0]
    protected static final int CMD_CONTRAST = 0x81
    protected static final int CMD_BRIGHTNESS = 0x82
    protected static final int CMD_LUT = 0x91
    protected static final int CMD_BANK_1_16 = 0x92
    protected static final int CMD_BANK_17_32 = 0x93
    protected static final int CMD_SEG_REMAP = 0xA0;    // | ADC
    protected static final int CMD_SCROLL_AREA = 0xA3
    protected static final int CMD_ALL_ON = 0xA4;       // | 1
    protected static final int CMD_INVERT = 0xA6;       // | 1
    protected static final int CMD_MULTIPLEX = 0xA8
    protected static final int CMD_DIM_MODE = 0xAB
    protected static final int CMD_DIM_ON = 0xAC
    protected static final int CMD_MASTER = 0xAD
    protected static final int CMD_DISPLAY = 0xAE;      // | 1
    protected static final int CMD_PAGE = 0xB0;         // | A[2:0]
    protected static final int CMD_SCAN = 0xC0;         // | (scan << 3)
    protected static final int CMD_OFFSET = 0xD3
    protected static final int CMD_CLOCK = 0xD5
    protected static final int CMD_AREA_COLOR = 0xD8
    protected static final int CMD_PRECHARGE = 0xD9
    protected static final int CMD_COM = 0xDA
    protected static final int CMD_VCOMH = 0xDB

    /** Horizontal addressing: the column pointer advances first. */
    static final int ADDR_HORIZONTAL = 0
    /** Vertical addressing: the page pointer advances first. */
    static final int ADDR_VERTICAL = 1
    /** Page addressing (reset default). */
    static final int ADDR_PAGE = 2

    protected final SSD1305Bus bus
    protected final int width
    protected final int height
    protected final int pages
    protected final int offset
    protected final byte[] fb
    protected int mode = ADDR_PAGE
    protected int adc
    protected int scan

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection   connection at 0x3C or 0x3D
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring)
     * @param columnOffset first RAM column shown (4 for 128-pixel modules, 0 for a true 132-column panel)
     * @throws IllegalArgumentException if width, height or columnOffset is out of range
     * @throws IOException              if a transfer fails
     */
    SSD1305Minimal(Connection connection, int width, int height, int columnOffset)
            throws IOException {
        this(new SSD1305I2cBus(connection), width, height, columnOffset)
    }

    /** Construct with the defaults 128 x 64, column offset 4. */
    SSD1305Minimal(Connection connection) throws IOException {
        this(connection, 128, HEIGHT, 4)
    }

    /** Construct on an explicit transport (used by the SPI classes). */
    protected SSD1305Minimal(SSD1305Bus bus, int width, int height, int columnOffset)
            throws IOException {
        if (width < 1 || width > WIDTH) throw new IllegalArgumentException("width must be 1..132")
        if (height < 16 || height > HEIGHT || height % 8 != 0) {
            throw new IllegalArgumentException("height must be a multiple of 8, 16..64")
        }
        if (columnOffset < 0 || columnOffset + width > WIDTH) {
            throw new IllegalArgumentException("columnOffset + width must be at most 132")
        }
        this.bus = bus
        this.width = width
        this.height = height
        this.pages = height.intdiv(8)
        this.offset = columnOffset
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
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page + column command
     * stream, then {@code width} data bytes. Horizontal / vertical addressing: column and page range commands, then
     * one data stream.
     *
     * @throws IOException if a transfer fails
     */
    void show() throws IOException {
        showAll()
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

    protected void showAll() throws IOException {
        if (mode == ADDR_PAGE) {
            for (int page = 0; page < pages; page++) showPage0(page)
            return
        }
        command(CMD_COL_RANGE, offset, offset + width - 1, CMD_PAGE_RANGE, 0, pages - 1)
        if (mode == ADDR_HORIZONTAL) {
            bus.writeData(fb, 0, width * pages)
            return
        }
        byte[] out = new byte[width * pages]
        int n = 0
        for (int x = 0; x < width; x++) {
            for (int p = 0; p < pages; p++) out[n++] = fb[p * width + x]
        }
        bus.writeData(out, 0, out.length)
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    private void initSequence() throws IOException {
        bus.hardwareReset()
        sleepMs(1)
        command(CMD_DISPLAY,
                CMD_CLOCK, 0x70,
                CMD_MULTIPLEX, height - 1,
                CMD_OFFSET, 0x00,
                CMD_START_LINE,
                CMD_SEG_REMAP | adc,
                CMD_SCAN | (scan << 3),
                CMD_COM, height > 32 ? 0x12 : 0x02,
                CMD_CONTRAST, 0x80,
                CMD_PRECHARGE, 0x22,
                CMD_VCOMH, 0x34,
                CMD_MASTER, 0x8E,
                CMD_ALL_ON,
                CMD_INVERT,
                CMD_ADDR_MODE, 0x02)
        byte[] blank = new byte[WIDTH]
        for (int page = 0; page < 8; page++) {
            bus.writeCommands(addressBytes(page, 0))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY | 1)
        sleepMs(100)
    }
}
