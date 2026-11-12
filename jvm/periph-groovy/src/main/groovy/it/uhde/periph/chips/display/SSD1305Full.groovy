package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.util.Arrays

/**
 * SSD1305 OLED controller-driver — full driver.
 *
 * <p>Extends {@link SSD1305Minimal} with drawing primitives, contrast, brightness, dim mode, sleep, invert, all-on
 * test, flip, start line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH
 * level, addressing mode, hardware scrolling, area-colour mode with LUT / bank colours, status and RAM reads (I2C
 * only) and raw commands.
 */
@CompileStatic
class SSD1305Full extends SSD1305Minimal {

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection   connection at 0x3C or 0x3D
     * @param width        visible columns, 1..132
     * @param height       rows, a multiple of 8, 16..64
     * @param columnOffset first RAM column shown (4 for 128-pixel modules)
     * @throws IllegalArgumentException if width, height or columnOffset is out of range
     * @throws IOException              if a transfer fails
     */
    SSD1305Full(Connection connection, int width, int height, int columnOffset)
            throws IOException {
        super(connection, width, height, columnOffset)
    }

    /** Construct with the defaults 128 x 64, column offset 4. */
    SSD1305Full(Connection connection) throws IOException {
        super(connection)
    }

    /** Construct on an explicit transport (used by {@link SSD1305SpiFull}). */
    protected SSD1305Full(SSD1305Bus bus, int width, int height, int columnOffset)
            throws IOException {
        super(bus, width, height, columnOffset)
    }

    // ---- framebuffer -----------------------------------------------------------------------

    /**
     * Return a framebuffer pixel; out-of-range coordinates read as false.
     *
     * @param x column
     * @param y row
     * @return true if the pixel is set
     */
    boolean getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return (fb[(y >> 3) * width + x] & (1 << (y & 7))) != 0
    }

    /**
     * Set (or clear) the whole framebuffer.
     *
     * @param on true to set every pixel
     */
    void fill(boolean on) {
        Arrays.fill(fb, on ? (byte) 0xFF : (byte) 0)
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
    void drawLine(int x0, int y0, int x1, int y1, boolean on) {
        int dx = Math.abs(x1 - x0)
        int dy = -Math.abs(y1 - y0)
        int sx = x0 < x1 ? 1 : -1
        int sy = y0 < y1 ? 1 : -1
        int err = dx + dy
        for (;;) {
            setPixel(x0, y0, on)
            if (x0 == x1 && y0 == y1) break
            int e2 = 2 * err
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
    void drawRect(int x, int y, int w, int h, boolean on, boolean fill) {
        if (w <= 0 || h <= 0) return
        if (fill) {
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) setPixel(xx, yy, on)
            }
            return
        }
        drawLine(x, y, x + w - 1, y, on)
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, on)
        drawLine(x, y, x, y + h - 1, on)
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, on)
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
    void blit(int x, int y, byte[] columns, int heightPages) {
        if (y % 8 != 0) throw new IllegalArgumentException("y must be a multiple of 8")
        int n = columns.length.intdiv(heightPages)
        for (int p = 0; p < heightPages; p++) {
            int page = y.intdiv(8) + p
            if (page < 0 || page >= pages) continue
            for (int c = 0; c < n; c++) {
                int xx = x + c
                if (xx >= 0 && xx < width) fb[page * width + xx] = columns[p * n + c]
            }
        }
    }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
     *
     * @param page page 0 .. height / 8 - 1
     * @throws IOException if a transfer fails
     */
    void showPage(int page) throws IOException {
        if (page < 0 || page >= pages) return
        if (mode == ADDR_PAGE) {
            showPage0(page)
            return
        }
        command(CMD_COL_RANGE, offset, offset + width - 1, CMD_PAGE_RANGE, page, page)
        bus.writeData(fb, page * width, width)
    }

    // ---- display control -------------------------------------------------------------------

    /**
     * Set the segment output current (contrast).
     *
     * @param value 0..255 (reset 0x80)
     * @throws IOException if a transfer fails
     */
    void setContrast(int value) throws IOException {
        command(CMD_CONTRAST, value & 0xFF)
    }

    /**
     * Switch the display on or off (RAM and settings are kept).
     *
     * @param on true for display on
     * @throws IOException if a transfer fails
     */
    void setDisplay(boolean on) throws IOException {
        command(CMD_DISPLAY | (on ? 1 : 0))
    }

    /**
     * Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards.
     *
     * @throws IOException if a transfer fails
     */
    void sleep() throws IOException {
        setDisplay(false)
    }

    /**
     * Display on, then wait 100 ms (tAF) for SEG / COM to turn on.
     *
     * @throws IOException if a transfer fails
     */
    public void wake() throws IOException {
        setDisplay(true)
        sleepMs(100)
    }

    /**
     * Switch between dim mode (0xAC) and normal display on (0xAF); configure the levels with
     * {@link #setDimMode(int, int)} first.
     *
     * @param on true for display on in dim mode
     * @throws IOException if a transfer fails
     */
    public void setDim(boolean on) throws IOException {
        command(on ? CMD_DIM_ON : CMD_DISPLAY | 1)
    }

    /**
     * Configure the BANK0 contrast and colour-bank brightness used in dim mode.
     *
     * @param contrast   0..255
     * @param brightness 0..255
     * @throws IOException if a transfer fails
     */
    public void setDimMode(int contrast, int brightness) throws IOException {
        command(CMD_DIM_MODE, 0x00, contrast & 0xFF, brightness & 0xFF)
    }

    /**
     * Invert the displayed image without changing RAM.
     *
     * @param on true to invert
     * @throws IOException if a transfer fails
     */
    void setInvert(boolean on) throws IOException {
        command(CMD_INVERT | (on ? 1 : 0))
    }

    /**
     * Force all pixels on (test mode, RAM unchanged).
     *
     * @param on true to light every pixel
     * @throws IOException if a transfer fails
     */
    void setAllOn(boolean on) throws IOException {
        command(CMD_ALL_ON | (on ? 1 : 0))
    }

    /**
     * Mirror columns (segment re-map) and/or rows (COM scan direction); the framebuffer is unchanged.
     *
     * @param horizontal true to reverse the segment mapping (ADC = 1)
     * @param vertical   true to reverse the COM scan direction (0xC8)
     * @throws IOException if a transfer fails
     */
    void setFlip(boolean horizontal, boolean vertical) throws IOException {
        adc = horizontal ? 1 : 0
        scan = vertical ? 1 : 0
        command(CMD_SEG_REMAP | adc, CMD_SCAN | (scan << 3))
    }

    /**
     * Set the RAM line shown at COM0 (hardware vertical scroll).
     *
     * @param line 0..63
     * @throws IOException if a transfer fails
     */
    void setStartLine(int line) throws IOException {
        command(CMD_START_LINE | (line & 0x3F))
    }

    /**
     * Shift the COM mapping.
     *
     * @param offset 0..63
     * @throws IOException if a transfer fails
     */
    void setDisplayOffset(int offset) throws IOException {
        command(CMD_OFFSET, offset & 0x3F)
    }

    /**
     * Set the multiplex ratio.
     *
     * @param ratio 16..64
     * @throws IOException if a transfer fails
     */
    void setMultiplex(int ratio) throws IOException {
        command(CMD_MULTIPLEX, (ratio - 1) & 0x3F)
    }

    /**
     * Set the clock divide ratio and oscillator frequency.
     *
     * @param divide divide ratio, 1..16
     * @param osc    oscillator setting, 0..15 (7 = reset value)
     * @throws IOException if a transfer fails
     */
    void setClock(int divide, int osc) throws IOException {
        command(CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F))
    }

    /**
     * Set the pre-charge phase 1 and phase 2 periods in display clocks.
     *
     * @param phase1 1..15
     * @param phase2 1..15
     * @throws IOException if a transfer fails
     */
    public void setPrecharge(int phase1, int phase2) throws IOException {
        command(CMD_PRECHARGE, ((phase2 & 0x0F) << 4) | (phase1 & 0x0F))
    }

    /**
     * Select the COM pin hardware configuration.
     *
     * @param alternative true for alternative (reset), false for sequential
     * @param remap       true to enable the COM left / right remap
     * @throws IOException if a transfer fails
     */
    public void setComConfig(boolean alternative, boolean remap) throws IOException {
        command(CMD_COM, 0x02 | (alternative ? 0x10 : 0) | (remap ? 0x20 : 0))
    }

    /**
     * Set the VCOMH deselect level (only bits 5:2 are used).
     *
     * @param value 0x00..0x3C (reset 0x34)
     * @throws IOException if a transfer fails
     */
    public void setVcomh(int value) throws IOException {
        command(CMD_VCOMH, value & 0x3C)
    }

    /**
     * Select how {@link #show()} writes the display RAM.
     *
     * @param newMode {@link #ADDR_PAGE} (reset default), {@link #ADDR_HORIZONTAL} or {@link #ADDR_VERTICAL}
     * @throws IllegalArgumentException if the mode is not one of the three constants
     * @throws IOException              if a transfer fails
     */
    public void setAddressingMode(int newMode) throws IOException {
        if (newMode < ADDR_HORIZONTAL || newMode > ADDR_PAGE) throw new IllegalArgumentException("invalid addressing mode")
        mode = newMode
        command(CMD_ADDR_MODE, newMode)
    }

    // ---- hardware scrolling ----------------------------------------------------------------

    /**
     * Set up a continuous horizontal scroll (start it with {@link #scrollStart()}). Do not call while scrolling.
     *
     * @param right     true to scroll right
     * @param startPage 0..7
     * @param endPage   startPage..7
     * @param interval  table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames per step
     * @param columns   columns per step, 1..4
     * @throws IOException if a transfer fails
     */
    public void setScrollHorizontal(boolean right, int startPage, int endPage, int interval, int columns)
            throws IOException {
        command(right ? 0x26 : 0x27, columns & 7, startPage & 7, Math.min(interval, 6), endPage & 7)
    }

    /**
     * Set up a continuous vertical + horizontal scroll (start it with {@link #scrollStart()}).
     *
     * @param right     true to scroll right
     * @param startPage 0..7
     * @param endPage   startPage..7
     * @param interval  table index 0..6
     * @param columns   horizontal columns per step, 0..4
     * @param rows      vertical rows per step, 1..63
     * @throws IOException if a transfer fails
     */
    public void setScrollVertical(boolean right, int startPage, int endPage, int interval, int columns, int rows)
            throws IOException {
        command(right ? 0x29 : 0x2A, columns & 7, startPage & 7, Math.min(interval, 6), endPage & 7, rows & 0x3F)
    }

    /**
     * Set the vertical scroll area.
     *
     * @param topFixed   rows in the fixed top area, 0..63
     * @param scrollRows rows in the scrolling area, 1..64
     * @throws IOException if a transfer fails
     */
    public void setScrollArea(int topFixed, int scrollRows) throws IOException {
        command(CMD_SCROLL_AREA, topFixed & 0x3F, scrollRows & 0x7F)
    }

    /**
     * Activate the configured scroll (0x2F).
     *
     * @throws IOException if a transfer fails
     */
    public void scrollStart() throws IOException {
        command(0x2F)
    }

    /**
     * Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires.
     *
     * @throws IOException if a transfer fails
     */
    public void scrollStop() throws IOException {
        command(0x2E)
        showAll()
    }

    // ---- area colour -----------------------------------------------------------------------

    /**
     * Enable the area-colour mode and/or the low-power display mode.
     *
     * @param enable   true to enable area colour
     * @param lowPower true for the low-power display mode
     * @throws IOException if a transfer fails
     */
    public void setAreaColor(boolean enable, boolean lowPower) throws IOException {
        command(CMD_AREA_COLOR, (enable ? 0x30 : 0) | (lowPower ? 0x05 : 0))
    }

    /**
     * Set the colour-bank brightness.
     *
     * @param value 0..255 (reset 0x80)
     * @throws IOException if a transfer fails
     */
    public void setBrightness(int value) throws IOException {
        command(CMD_BRIGHTNESS, value & 0xFF)
    }

    /**
     * Set the drive pulse width codes (31..63 = 32..64 clocks) of BANK0 and colours A, B, C.
     *
     * @param bank0 BANK0 code
     * @param a     colour A code
     * @param b     colour B code
     * @param c     colour C code
     * @throws IOException if a transfer fails
     */
    public void setLut(int bank0, int a, int b, int c) throws IOException {
        command(CMD_LUT, bank0 & 0x3F, a & 0x3F, b & 0x3F, c & 0x3F)
    }

    /**
     * Assign colours A..D (2 bits per bank) to the area-colour banks.
     *
     * @param banks1to16  4 bytes for banks 1..16
     * @param banks17to32 4 bytes for banks 17..32, or null to skip
     * @throws IllegalArgumentException if a group is not exactly 4 bytes
     * @throws IOException              if a transfer fails
     */
    public void setBankColors(byte[] banks1to16, byte[] banks17to32) throws IOException {
        if (banks1to16.length != 4 || (banks17to32 != null && banks17to32.length != 4)) {
            throw new IllegalArgumentException("bank colour groups need 4 bytes")
        }
        command(CMD_BANK_1_16, banks1to16[0] & 0xFF, banks1to16[1] & 0xFF, banks1to16[2] & 0xFF, banks1to16[3] & 0xFF)
        if (banks17to32 != null) {
            command(CMD_BANK_17_32, banks17to32[0] & 0xFF, banks17to32[1] & 0xFF, banks17to32[2] & 0xFF,
                    banks17to32[3] & 0xFF)
        }
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the status byte (I2C only).
     *
     * @return bit 6 = 1 when the display is off; the other bits are reserved
     * @throws IOException                   if the read fails
     * @throws UnsupportedOperationException on SPI (write-only)
     */
    int readStatus() throws IOException {
        if (!bus.canRead()) throw new UnsupportedOperationException("the SPI interface is write-only")
        return bus.readStatus()
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
    byte[] readPage(int page, int column, int n) throws IOException {
        if (!bus.canRead()) throw new UnsupportedOperationException("the SPI interface is write-only")
        bus.writeCommands(addressBytes(page, column))
        return bus.readRam(n)
    }

    /**
     * Send raw bytes as one command stream.
     *
     * @param bytes command and argument bytes
     * @throws IOException if a transfer fails
     */
    void sendCommand(int... bytes) throws IOException {
        command(bytes)
    }
}
