package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * SH1106 OLED controller-driver — full driver.
 *
 * <p>Extends {@link SH1106Minimal} with drawing primitives, contrast, sleep, invert, all-on test, flip, hardware
 * scroll (display start line), display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration,
 * VCOM level, pump voltage, DC-DC control, status and RAM reads (I2C only) and raw commands.
 */
public class SH1106Full extends SH1106Minimal {

    /**
     * Construct the driver and run the initialisation sequence (about 250 ms).
     *
     * @param connection   connection at 0x3C or 0x3D
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 8..64
     * @param columnOffset first RAM column shown (2 for 128-pixel modules)
     * @param internalDcdc true for the internal charge pump, false for an external VPP
     * @throws IllegalArgumentException if width, height or columnOffset is out of range
     * @throws IOException              if a transfer fails
     */
    public SH1106Full(Connection connection, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        super(connection, width, height, columnOffset, internalDcdc);
    }

    /** Construct with the defaults 128 x 64, column offset 2 and the internal DC-DC. */
    public SH1106Full(Connection connection) throws IOException {
        super(connection);
    }

    /** Construct on an explicit transport (used by {@link SH1106SpiFull}). */
    protected SH1106Full(SH1106Bus bus, int width, int height, int columnOffset, boolean internalDcdc)
            throws IOException {
        super(bus, width, height, columnOffset, internalDcdc);
    }

    // ---- framebuffer -----------------------------------------------------------------------

    /**
     * Return a framebuffer pixel; out-of-range coordinates read as false.
     *
     * @param x column
     * @param y row
     * @return true if the pixel is set
     */
    public boolean getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        return (fb[(y >> 3) * width + x] & (1 << (y & 7))) != 0;
    }

    /**
     * Set (or clear) the whole framebuffer.
     *
     * @param on true to set every pixel
     */
    public void fill(boolean on) {
        java.util.Arrays.fill(fb, on ? (byte) 0xFF : (byte) 0);
    }

    /**
     * Draw a Bresenham line into the framebuffer.
     *
     * @param x0 start column
     * @param y0 start row
     * @param x1 end column
     * @param y1 end row
     * @param on true to set pixels, false to clear them
     */
    public void drawLine(int x0, int y0, int x1, int y1, boolean on) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        for (;;) {
            setPixel(x0, y0, on);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /**
     * Draw a rectangle outline or a filled rectangle into the framebuffer.
     *
     * @param x    left column
     * @param y    top row
     * @param w    width in pixels
     * @param h    height in pixels
     * @param on   true to set pixels, false to clear them
     * @param fill true for a filled rectangle
     */
    public void drawRect(int x, int y, int w, int h, boolean on, boolean fill) {
        if (w <= 0 || h <= 0) return;
        if (fill) {
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) setPixel(xx, yy, on);
            }
            return;
        }
        drawLine(x, y, x + w - 1, y, on);
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, on);
        drawLine(x, y, x, y + h - 1, on);
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, on);
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. {@code columns} holds
     * {@code heightPages} groups of columns, page by page: byte {@code p * n + c} is column {@code c} of page row
     * {@code p} (n = length / heightPages). The bitmap is clipped to the framebuffer.
     *
     * @param x           left column of the bitmap
     * @param y           top row, a multiple of 8
     * @param columns     bitmap bytes
     * @param heightPages number of 8-pixel-high rows of the bitmap
     * @throws IllegalArgumentException if y is not a multiple of 8
     */
    public void blit(int x, int y, byte[] columns, int heightPages) {
        if (y % 8 != 0) throw new IllegalArgumentException("y must be a multiple of 8");
        int n = columns.length / heightPages;
        for (int p = 0; p < heightPages; p++) {
            int page = y / 8 + p;
            if (page < 0 || page >= pages) continue;
            for (int c = 0; c < n; c++) {
                int xx = x + c;
                if (xx >= 0 && xx < width) fb[page * width + xx] = columns[p * n + c];
            }
        }
    }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
     *
     * @param page page 0 .. height / 8 - 1
     * @throws IOException if a transfer fails
     */
    public void showPage(int page) throws IOException {
        if (page >= 0 && page < pages) showPage0(page);
    }

    // ---- display control -------------------------------------------------------------------

    /**
     * Set the segment output current (contrast).
     *
     * @param value 0..255 (reset 0x80)
     * @throws IOException if a transfer fails
     */
    public void setContrast(int value) throws IOException {
        command(CMD_CONTRAST, value & 0xFF);
    }

    /**
     * Switch the display on or off (RAM and settings are kept).
     *
     * @param on true for display on
     * @throws IOException if a transfer fails
     */
    public void setDisplay(boolean on) throws IOException {
        command(CMD_DISPLAY | (on ? 1 : 0));
    }

    /**
     * Display off; the DC-DC stops, RAM and settings are kept and stay writable.
     *
     * @throws IOException if a transfer fails
     */
    public void sleep() throws IOException {
        setDisplay(false);
    }

    /**
     * Display on, then wait 100 ms for VPP when the internal DC-DC is used.
     *
     * @throws IOException if a transfer fails
     */
    public void wake() throws IOException {
        setDisplay(true);
        sleepMs(dcdc ? 100 : 50);
    }

    /**
     * Invert the displayed image without changing RAM.
     *
     * @param on true to invert
     * @throws IOException if a transfer fails
     */
    public void setInvert(boolean on) throws IOException {
        command(CMD_INVERT | (on ? 1 : 0));
    }

    /**
     * Force all pixels on (test mode, RAM unchanged).
     *
     * @param on true to light every pixel
     * @throws IOException if a transfer fails
     */
    public void setAllOn(boolean on) throws IOException {
        command(CMD_ALL_ON | (on ? 1 : 0));
    }

    /**
     * Mirror columns (segment re-map) and/or rows (COM scan direction); the framebuffer is unchanged.
     *
     * @param horizontal true to reverse the segment mapping (ADC = 1)
     * @param vertical   true to reverse the COM scan direction (0xC8)
     * @throws IOException if a transfer fails
     */
    public void setFlip(boolean horizontal, boolean vertical) throws IOException {
        adc = horizontal ? 1 : 0;
        scan = vertical ? 1 : 0;
        command(CMD_SEG_REMAP | adc, CMD_SCAN | (scan << 3));
    }

    /**
     * Set the RAM line shown at COM0 (hardware vertical scroll).
     *
     * @param line 0..63
     * @throws IOException if a transfer fails
     */
    public void setStartLine(int line) throws IOException {
        command(CMD_START_LINE | (line & 0x3F));
    }

    /**
     * Shift the COM mapping.
     *
     * @param offset 0..63
     * @throws IOException if a transfer fails
     */
    public void setDisplayOffset(int offset) throws IOException {
        command(CMD_OFFSET, offset & 0x3F);
    }

    /**
     * Set the multiplex ratio.
     *
     * @param ratio 1..64
     * @throws IOException if a transfer fails
     */
    public void setMultiplex(int ratio) throws IOException {
        command(CMD_MULTIPLEX, (ratio - 1) & 0x3F);
    }

    /**
     * Set the clock divide ratio and oscillator frequency.
     *
     * @param divide divide ratio, 1..16
     * @param osc    oscillator setting, 0..15 (5 = nominal)
     * @throws IOException if a transfer fails
     */
    public void setClock(int divide, int osc) throws IOException {
        command(CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F));
    }

    /**
     * Set the pre-charge and discharge periods in display clocks.
     *
     * @param precharge 1..15
     * @param discharge 1..15
     * @throws IOException if a transfer fails
     */
    public void setPrecharge(int precharge, int discharge) throws IOException {
        command(CMD_PRECHARGE, ((discharge & 0x0F) << 4) | (precharge & 0x0F));
    }

    /**
     * Select the COM pin hardware configuration.
     *
     * @param alternative true for alternative (0x12, reset), false for sequential (0x02)
     * @throws IOException if a transfer fails
     */
    public void setComConfig(boolean alternative) throws IOException {
        command(CMD_COM, alternative ? 0x12 : 0x02);
    }

    /**
     * Set the VCOM deselect level.
     *
     * @param value 0..255 (reset 0x35)
     * @throws IOException if a transfer fails
     */
    public void setVcomDeselect(int value) throws IOException {
        command(CMD_VCOM, value & 0xFF);
    }

    /**
     * Set the charge pump output voltage VPP.
     *
     * @param level 0 = 7.4 V (reset), 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V
     * @throws IOException if a transfer fails
     */
    public void setPumpVoltage(int level) throws IOException {
        command(CMD_PUMP | (level & 3));
    }

    /**
     * Switch the internal DC-DC on or off (call with the display off).
     *
     * @param on true for the internal DC-DC (0x8B), false for an external VPP (0x8A)
     * @throws IOException if a transfer fails
     */
    public void setDcdc(boolean on) throws IOException {
        dcdc = on;
        command(CMD_DCDC, on ? 0x8B : 0x8A);
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the status byte (I2C only).
     *
     * @return bit 7 BUSY, bit 6 = 1 when the display is off
     * @throws IOException                   if the read fails
     * @throws UnsupportedOperationException on SPI (write-only)
     */
    public int readStatus() throws IOException {
        if (!bus.canRead()) throw new UnsupportedOperationException("the SPI interface is write-only");
        return bus.readStatus();
    }

    /**
     * Read {@code n} bytes (at most 30) of display RAM (I2C only); the dummy byte after an address change is discarded.
     *
     * @param page   RAM page 0..7
     * @param column start RAM column
     * @param n      number of bytes
     * @return the RAM bytes
     * @throws IOException                   if address or read fail
     * @throws UnsupportedOperationException on SPI (write-only)
     */
    public byte[] readPage(int page, int column, int n) throws IOException {
        if (!bus.canRead()) throw new UnsupportedOperationException("the SPI interface is write-only");
        bus.writeCommands(addressBytes(page, column));
        return bus.readRam(n);
    }

    /**
     * Send raw bytes as one command stream.
     *
     * @param bytes command and argument bytes
     * @throws IOException if a transfer fails
     */
    public void sendCommand(int... bytes) throws IOException {
        command(bytes);
    }
}
