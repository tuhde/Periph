package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;
import java.io.IOException;

/**
 * SSD1331 96 x 64 RGB OLED controller — minimal driver.
 *
 * <p>Drives the Waveshare 0.95inch RGB OLED over 4-wire SPI. The picture is
 * kept in a 96 x 64 RGB565 framebuffer (12 288 bytes, high byte first) and sent
 * to the display RAM by {@link #show()}.
 *
 * <p>The connection is a {@link DisplayConn} with no BUSY pin (see {@code
 * specs/transport_display.md}); SIMPLEX wiring is enough. The SSD1331 takes
 * command parameters with D/C# low, so the driver sends every byte of a command
 * as its own {@code sendCommand(byte, empty)}; only pixel data uses {@code
 * sendData}.
 */
public class Ssd1331Minimal {

    public static final int WIDTH = 96;
    public static final int HEIGHT = 64;
    public static final int BUFFER_SIZE = WIDTH * HEIGHT * 2;

    public static final int BLACK = 0x0000;
    public static final int WHITE = 0xFFFF;
    public static final int RED = 0xF800;
    public static final int GREEN = 0x07E0;
    public static final int BLUE = 0x001F;
    public static final int YELLOW = 0xFFE0;
    public static final int CYAN = 0x07FF;
    public static final int MAGENTA = 0xF81F;

    protected static final int CMD_COLUMN = 0x15;
    protected static final int CMD_ROW = 0x75;
    protected static final int CMD_CONTRAST_A = 0x81;
    protected static final int CMD_CONTRAST_B = 0x82;
    protected static final int CMD_CONTRAST_C = 0x83;
    protected static final int CMD_MASTER_CURRENT = 0x87;
    protected static final int CMD_PRECHARGE_A = 0x8A;
    protected static final int CMD_PRECHARGE_B = 0x8B;
    protected static final int CMD_PRECHARGE_C = 0x8C;
    protected static final int CMD_REMAP = 0xA0;
    protected static final int CMD_START_LINE = 0xA1;
    protected static final int CMD_OFFSET = 0xA2;
    protected static final int CMD_MODE_NORMAL = 0xA4;
    protected static final int CMD_MUX = 0xA8;
    protected static final int CMD_DIM_SETTING = 0xAB;
    protected static final int CMD_MASTER_CONFIG = 0xAD;
    protected static final int CMD_DISPLAY_DIM = 0xAC;
    protected static final int CMD_DISPLAY_OFF = 0xAE;
    protected static final int CMD_DISPLAY_ON = 0xAF;
    protected static final int CMD_POWER_SAVE = 0xB0;
    protected static final int CMD_PHASE = 0xB1;
    protected static final int CMD_CLOCK = 0xB3;
    protected static final int CMD_GRAY_LINEAR = 0xB9;
    protected static final int CMD_PRECHARGE_LEVEL = 0xBB;
    protected static final int CMD_VCOMH = 0xBE;
    protected static final int CMD_LINE = 0x21;
    protected static final int CMD_RECT = 0x22;
    protected static final int CMD_COPY = 0x23;
    protected static final int CMD_DIM = 0x24;
    protected static final int CMD_CLEAR = 0x25;
    protected static final int CMD_FILL = 0x26;
    protected static final int CMD_SCROLL_SETUP = 0x27;
    protected static final int CMD_SCROLL_OFF = 0x2E;
    protected static final int CMD_SCROLL_ON = 0x2F;

    protected final DisplayConn connection;
    protected final byte[] fb = new byte[BUFFER_SIZE];
    protected int remap = 0x72;

    /**
     * Pack 8-bit R, G, B into RGB565.
     *
     * @param r red 0..255
     * @param g green 0..255
     * @param b blue 0..255
     * @return the RGB565 value
     */
    public static int rgb(int r, int g, int b) {
        return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xFF) >> 3);
    }

    /**
     * Construct the driver and run the vendor initialisation sequence: hardware
     * reset (100 ms settle), display off, contrast {@code FF FF FF}, master
     * current 7/16, 65k colours with column remap and reversed COM scan, VCC
     * external, power save off, 100 ms VCC wait, scroll off, display RAM
     * cleared, display on. The framebuffer is cleared to black.
     *
     * @param connection display connection (no BUSY pin)
     * @throws IOException if the bus fails
     */
    public Ssd1331Minimal(DisplayConn connection) throws IOException {
        this.connection = connection;
        initSequence();
    }

    /** @return panel width in pixels (96) */
    public int width() { return WIDTH; }

    /** @return panel height in pixels (64) */
    public int height() { return HEIGHT; }

    /**
     * Fill the framebuffer with one RGB565 colour. The display is unchanged
     * until {@link #show()}.
     *
     * @param color RGB565 colour
     */
    public void clear(int color) {
        byte hi = (byte) (color >> 8);
        byte lo = (byte) color;
        for (int i = 0; i < BUFFER_SIZE; i += 2) {
            fb[i] = hi;
            fb[i + 1] = lo;
        }
    }

    /**
     * Set one framebuffer pixel. Out-of-range coordinates are ignored.
     *
     * @param x     column 0..95
     * @param y     row 0..63
     * @param color RGB565 colour
     */
    public void setPixel(int x, int y, int color) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return;
        int i = (y * WIDTH + x) * 2;
        fb[i] = (byte) (color >> 8);
        fb[i + 1] = (byte) color;
    }

    /**
     * Send the whole framebuffer to the display RAM: window 0..95 x 0..63 and
     * 12 288 bytes in 192-byte (one row) transfers.
     *
     * @throws IOException if the bus fails
     */
    public void show() throws IOException {
        window(0, 0, WIDTH - 1, HEIGHT - 1);
        for (int off = 0; off < BUFFER_SIZE; off += 2 * WIDTH) {
            connection.sendData(java.util.Arrays.copyOfRange(fb, off, off + 2 * WIDTH));
        }
    }

    /**
     * Switch the display on ({@code 0xAF}); the RAM content is shown.
     *
     * @throws IOException if the bus fails
     */
    public void displayOn() throws IOException { cmd(CMD_DISPLAY_ON); }

    /**
     * Switch the display off ({@code 0xAE}); the RAM content is kept.
     *
     * @throws IOException if the bus fails
     */
    public void displayOff() throws IOException { cmd(CMD_DISPLAY_OFF); }

    /** Send every value as its own command byte (all with D/C# low). */
    protected void cmd(int... bytes) throws IOException {
        for (int b : bytes) connection.sendCommand(b & 0xFF, new byte[0]);
    }

    protected void window(int x0, int y0, int x1, int y1) throws IOException {
        cmd(CMD_COLUMN, x0, x1, CMD_ROW, y0, y1);
    }

    private void initSequence() throws IOException {
        connection.reset();
        connection.delay(100);
        cmd(CMD_DISPLAY_OFF);
        cmd(CMD_CONTRAST_A, 0xFF, CMD_CONTRAST_B, 0xFF, CMD_CONTRAST_C, 0xFF);
        cmd(CMD_MASTER_CURRENT, 0x06);
        cmd(CMD_PRECHARGE_A, 0x64, CMD_PRECHARGE_B, 0x78, CMD_PRECHARGE_C, 0x64);
        cmd(CMD_REMAP, remap);
        cmd(CMD_START_LINE, 0x00, CMD_OFFSET, 0x00);
        cmd(CMD_MODE_NORMAL);
        cmd(CMD_MUX, 0x3F, CMD_MASTER_CONFIG, 0x8E);
        cmd(CMD_POWER_SAVE, 0x0B, CMD_PHASE, 0x31, CMD_CLOCK, 0xF0);
        cmd(CMD_PRECHARGE_LEVEL, 0x3A, CMD_VCOMH, 0x3E);
        cmd(CMD_SCROLL_OFF);
        cmd(CMD_CLEAR, 0x00, 0x00, 0x5F, 0x3F);
        connection.delay(100);
        cmd(CMD_DISPLAY_ON);
    }
}
