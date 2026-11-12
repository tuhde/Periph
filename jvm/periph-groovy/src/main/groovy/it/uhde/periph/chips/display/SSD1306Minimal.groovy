package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection

import java.io.IOException

import groovy.transform.CompileStatic

/**
 * SSD1306 128 x 64 OLED controller-driver — minimal driver (I2C).
 *
 * <p>Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I2C at {@code 0x3C}
 * (SA0 = low) or {@code 0x3D} (SA0 = high). The chip has an exact 128-column RAM, so there is no column offset.
 *
 * <p>All drawing goes into a {@link MonoFramebuffer} of {@code width * height / 8} bytes (page-major, bit 0 = top
 * pixel of the page); {@link #show()} sends it page by page (page addressing, the reset default). The
 * initialisation follows the datasheet and the charge-pump application note: setup stream with the display off
 * (charge pump, page addressing), RAM cleared, display on + 100 ms. With {@code externalVcc = false} (default) the
 * internal charge pump makes the OLED supply VCC from VBAT.
 *
 * <p>The same driver runs over 4-wire SPI as {@link SSD1306SpiMinimal} / {@link SSD1306SpiFull}.
 */
@CompileStatic
class SSD1306Minimal {

    static final int WIDTH = 128
    static final int HEIGHT = 64
    /** Largest write in bytes including the control byte (the 32-byte limit of some I2C layers). */
    static final int I2C_CHUNK = MonoOledI2cBus.CHUNK

    protected static final int CMD_ADDR_MODE = 0x20
    protected static final int CMD_COL_RANGE = 0x21
    protected static final int CMD_PAGE_RANGE = 0x22
    protected static final int CMD_START_LINE = 0x40;   // | A[5:0]
    protected static final int CMD_CONTRAST = 0x81
    protected static final int CMD_CHARGE_PUMP = 0x8D
    protected static final int CMD_SEG_REMAP = 0xA0;    // | ADC
    protected static final int CMD_SCROLL_AREA = 0xA3
    protected static final int CMD_ALL_ON = 0xA4;       // | 1
    protected static final int CMD_INVERT = 0xA6;       // | 1
    protected static final int CMD_MULTIPLEX = 0xA8
    protected static final int CMD_DISPLAY = 0xAE;      // | 1
    protected static final int CMD_PAGE = 0xB0;         // | A[2:0]
    protected static final int CMD_SCAN = 0xC0;         // | (scan << 3)
    protected static final int CMD_OFFSET = 0xD3
    protected static final int CMD_CLOCK = 0xD5
    protected static final int CMD_PRECHARGE = 0xD9
    protected static final int CMD_COM = 0xDA
    protected static final int CMD_VCOMH = 0xDB

    /** Horizontal addressing: the column pointer advances first. */
    static final int ADDR_HORIZONTAL = 0
    /** Vertical addressing: the page pointer advances first. */
    static final int ADDR_VERTICAL = 1
    /** Page addressing (reset default). */
    static final int ADDR_PAGE = 2

    protected final MonoOledBus bus
    protected final MonoFramebuffer canvas
    protected final boolean externalVcc
    protected int mode = ADDR_PAGE
    protected boolean scrolling
    protected int adc
    protected int scan

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection  connection at 0x3C or 0x3D
     * @param width       visible columns, 1..128
     * @param height      rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring)
     * @param externalVcc true when the board supplies VCC (the charge pump stays off)
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException              if a transfer fails
     */
    SSD1306Minimal(Connection connection, int width, int height, boolean externalVcc) throws IOException {
        this(new MonoOledI2cBus(connection), width, height, externalVcc)
    }

    /** Construct with the defaults 128 x 64 and the internal charge pump. */
    SSD1306Minimal(Connection connection) throws IOException {
        this(connection, 128, HEIGHT, false)
    }

    /** Construct on an explicit transport (used by the SPI classes). */
    protected SSD1306Minimal(MonoOledBus bus, int width, int height, boolean externalVcc) throws IOException {
        if (width < 1 || width > WIDTH) throw new IllegalArgumentException("width must be 1..128")
        if (height < 16 || height > HEIGHT || height % 8 != 0) {
            throw new IllegalArgumentException("height must be a multiple of 8, 16..64")
        }
        this.bus = bus
        this.canvas = new MonoFramebuffer(width, height)
        this.externalVcc = externalVcc
        initSequence()
    }

    /** @return the visible columns of the framebuffer */
    int width() {
        return canvas.width()
    }

    /** @return the rows of the framebuffer */
    int height() {
        return canvas.height()
    }

    /** Zero the framebuffer. Call {@link #show()} to update the panel. */
    void clear() {
        canvas.clear()
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x  column, 0 .. width - 1
     * @param y  row, 0 .. height - 1
     * @param on true to set the pixel, false to clear it
     */
    void setPixel(int x, int y, boolean on) {
        canvas.setPixel(x, y, on)
    }

    /** Set one pixel in the framebuffer. */
    void setPixel(int x, int y) {
        canvas.setPixel(x, y, true)
    }

    /**
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page + column command
     * stream, then {@code width} data bytes. Horizontal / vertical addressing: column and page range commands, then
     * one data stream.
     *
     * @throws IllegalStateException while a hardware scroll is active (call {@code scrollStop()} first)
     * @throws IOException           if a transfer fails
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

    protected byte[] addressBytes(int page) {
        return new byte[] {(byte) (CMD_PAGE | (page & 7)), 0x00, 0x10}
    }

    protected void checkRamAccess() {
        if (scrolling) throw new IllegalStateException("RAM access while a scroll is active; call scrollStop() first")
    }

    protected void showPage0(int page) throws IOException {
        bus.writeCommands(addressBytes(page))
        bus.writeData(canvas.data(), page * canvas.width(), canvas.width())
    }

    protected void showAll() throws IOException {
        checkRamAccess()
        if (mode == ADDR_PAGE) {
            for (int page = 0; page < canvas.pages(); page++) showPage0(page)
            return
        }
        command(CMD_COL_RANGE, 0, canvas.width() - 1, CMD_PAGE_RANGE, 0, canvas.pages() - 1)
        byte[] out = mode == ADDR_HORIZONTAL ? canvas.data() : canvas.transposed()
        bus.writeData(out, 0, out.length)
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    private void initSequence() throws IOException {
        bus.hardwareReset()
        sleepMs(1)
        command(CMD_DISPLAY,
                CMD_CLOCK, 0x80,
                CMD_MULTIPLEX, canvas.height() - 1,
                CMD_OFFSET, 0x00,
                CMD_START_LINE,
                CMD_CHARGE_PUMP, externalVcc ? 0x10 : 0x14,
                CMD_ADDR_MODE, 0x02,
                CMD_SEG_REMAP | adc,
                CMD_SCAN | (scan << 3),
                CMD_COM, canvas.height() > 32 ? 0x12 : 0x02,
                CMD_CONTRAST, 0x7F,
                CMD_PRECHARGE, 0x22,
                CMD_VCOMH, 0x20,
                CMD_ALL_ON,
                CMD_INVERT)
        byte[] blank = new byte[WIDTH]
        for (int page = 0; page < 8; page++) {
            bus.writeCommands(addressBytes(page))
            bus.writeData(blank, 0, WIDTH)
        }
        command(CMD_DISPLAY | 1)
        sleepMs(100)
    }
}
