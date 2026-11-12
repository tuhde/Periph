package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * SSD1306 OLED controller-driver — full driver.
 *
 * <p>Extends {@link SSD1306Minimal} with drawing primitives, contrast, sleep, invert, all-on test, flip, start line,
 * display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH level, charge pump,
 * addressing mode, hardware scrolling and raw commands. The SSD1306 cannot be read over its serial interfaces.
 */
public class SSD1306Full extends SSD1306Minimal {

    /**
     * Construct the driver and run the initialisation sequence (about 100 ms).
     *
     * @param connection  connection at 0x3C or 0x3D
     * @param width       visible columns, 1..128
     * @param height      rows, a multiple of 8, 16..64
     * @param externalVcc true when the board supplies VCC (the charge pump stays off)
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException              if a transfer fails
     */
    public SSD1306Full(Connection connection, int width, int height, boolean externalVcc) throws IOException {
        super(connection, width, height, externalVcc);
    }

    /** Construct with the defaults 128 x 64 and the internal charge pump. */
    public SSD1306Full(Connection connection) throws IOException {
        super(connection);
    }

    /** Construct on an explicit transport (used by {@link SSD1306SpiFull}). */
    protected SSD1306Full(MonoOledBus bus, int width, int height, boolean externalVcc) throws IOException {
        super(bus, width, height, externalVcc);
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
        return canvas.getPixel(x, y);
    }

    /**
     * Set (or clear) the whole framebuffer.
     *
     * @param on true to set every pixel
     */
    public void fill(boolean on) {
        canvas.fill(on);
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
        canvas.drawLine(x0, y0, x1, y1, on);
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
        canvas.drawRect(x, y, w, h, on, fill);
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer; clipped.
     *
     * @param x           left column of the bitmap
     * @param y           top row, a multiple of 8
     * @param columns     bitmap bytes, {@code heightPages} groups of columns page by page
     * @param heightPages number of 8-pixel-high rows of the bitmap
     * @throws IllegalArgumentException if y is not a multiple of 8
     */
    public void blit(int x, int y, byte[] columns, int heightPages) {
        canvas.blit(x, y, columns, heightPages);
    }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are ignored.
     *
     * @param page page 0 .. height / 8 - 1
     * @throws IllegalStateException while a hardware scroll is active (call {@link #scrollStop()} first)
     * @throws IOException           if a transfer fails
     */
    public void showPage(int page) throws IOException {
        checkRamAccess();
        if (page < 0 || page >= canvas.pages()) return;
        if (mode == ADDR_PAGE) {
            showPage0(page);
            return;
        }
        command(CMD_COL_RANGE, 0, canvas.width() - 1, CMD_PAGE_RANGE, page, page);
        bus.writeData(canvas.data(), page * canvas.width(), canvas.width());
    }

    // ---- display control -------------------------------------------------------------------

    /**
     * Set the segment output current (contrast).
     *
     * @param value 0..255 (reset 0x7F)
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
     * Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards.
     *
     * @throws IOException if a transfer fails
     */
    public void sleep() throws IOException {
        setDisplay(false);
    }

    /**
     * Display on, then wait 100 ms (tAF) for SEG / COM to turn on.
     *
     * @throws IOException if a transfer fails
     */
    public void wake() throws IOException {
        setDisplay(true);
        sleepMs(100);
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
     * @param ratio 16..64
     * @throws IOException if a transfer fails
     */
    public void setMultiplex(int ratio) throws IOException {
        command(CMD_MULTIPLEX, (ratio - 1) & 0x3F);
    }

    /**
     * Set the clock divide ratio and oscillator frequency.
     *
     * @param divide divide ratio, 1..16
     * @param osc    oscillator setting, 0..15 (8 = reset value)
     * @throws IOException if a transfer fails
     */
    public void setClock(int divide, int osc) throws IOException {
        command(CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F));
    }

    /**
     * Set the pre-charge phase 1 and phase 2 periods in display clocks.
     *
     * @param phase1 1..15
     * @param phase2 1..15
     * @throws IOException if a transfer fails
     */
    public void setPrecharge(int phase1, int phase2) throws IOException {
        command(CMD_PRECHARGE, ((phase2 & 0x0F) << 4) | (phase1 & 0x0F));
    }

    /**
     * Select the COM pin hardware configuration.
     *
     * @param alternative true for alternative (reset), false for sequential
     * @param remap       true to enable the COM left / right remap
     * @throws IOException if a transfer fails
     */
    public void setComConfig(boolean alternative, boolean remap) throws IOException {
        command(CMD_COM, 0x02 | (alternative ? 0x10 : 0) | (remap ? 0x20 : 0));
    }

    /**
     * Set the VCOMH deselect level (only bits 6:4 are used).
     *
     * @param value 0x00..0x70 (reset 0x20)
     * @throws IOException if a transfer fails
     */
    public void setVcomh(int value) throws IOException {
        command(CMD_VCOMH, value & 0x70);
    }

    /**
     * Enable (0x14) or disable (0x10) the internal charge pump; takes effect with the display on.
     *
     * @param enabled true for the internal pump
     * @throws IOException if a transfer fails
     */
    public void setChargePump(boolean enabled) throws IOException {
        command(CMD_CHARGE_PUMP, enabled ? 0x14 : 0x10);
    }

    /**
     * Select how {@link #show()} writes the display RAM.
     *
     * @param newMode {@link #ADDR_PAGE} (reset default), {@link #ADDR_HORIZONTAL} or {@link #ADDR_VERTICAL}
     * @throws IllegalArgumentException if the mode is not one of the three constants
     * @throws IOException              if a transfer fails
     */
    public void setAddressingMode(int newMode) throws IOException {
        if (newMode < ADDR_HORIZONTAL || newMode > ADDR_PAGE) throw new IllegalArgumentException("invalid addressing mode");
        mode = newMode;
        command(CMD_ADDR_MODE, newMode);
    }

    // ---- hardware scrolling ----------------------------------------------------------------

    /**
     * Set up a continuous horizontal scroll, one column per step (start it with {@link #scrollStart()}). Do not call
     * while scrolling is active.
     *
     * @param right     true to scroll right
     * @param startPage 0..7
     * @param endPage   startPage..7
     * @param interval  table index 0..7 = 5, 64, 128, 256, 3, 4, 25, 2 frames per step
     * @throws IOException if a transfer fails
     */
    public void setScrollHorizontal(boolean right, int startPage, int endPage, int interval) throws IOException {
        command(right ? 0x26 : 0x27, 0x00, startPage & 7, interval & 7, endPage & 7, 0x00, 0xFF);
    }

    /**
     * Set up a continuous vertical + horizontal (diagonal) scroll (start it with {@link #scrollStart()}).
     *
     * @param right     true to scroll right
     * @param startPage 0..7
     * @param endPage   startPage..7
     * @param interval  table index 0..7
     * @param rows      vertical rows per step, 1..63
     * @throws IOException if a transfer fails
     */
    public void setScrollVertical(boolean right, int startPage, int endPage, int interval, int rows)
            throws IOException {
        command(right ? 0x29 : 0x2A, 0x00, startPage & 7, interval & 7, endPage & 7, rows & 0x3F);
    }

    /**
     * Set the vertical scroll area.
     *
     * @param topFixed   rows in the fixed top area, 0..63
     * @param scrollRows rows in the scrolling area, 1..64
     * @throws IOException if a transfer fails
     */
    public void setScrollArea(int topFixed, int scrollRows) throws IOException {
        command(CMD_SCROLL_AREA, topFixed & 0x3F, scrollRows & 0x7F);
    }

    /**
     * Activate the configured scroll (0x2F); {@link #show()} and {@link #showPage(int)} are refused until
     * {@link #scrollStop()}.
     *
     * @throws IOException if a transfer fails
     */
    public void scrollStart() throws IOException {
        command(0x2F);
        scrolling = true;
    }

    /**
     * Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires.
     *
     * @throws IOException if a transfer fails
     */
    public void scrollStop() throws IOException {
        command(0x2E);
        scrolling = false;
        showAll();
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
