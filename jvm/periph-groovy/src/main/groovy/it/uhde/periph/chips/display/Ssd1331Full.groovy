package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * SSD1331 96 x 64 RGB OLED controller — full driver.
 *
 * <p>Extends {@link Ssd1331Minimal} with software drawing into the framebuffer,
 * partial transfers, the controller's graphic acceleration commands (line,
 * rectangle with fill, copy, dim and clear window, scrolling — these act on the
 * display RAM only, not on the host framebuffer), and the electrical settings
 * (contrast, master current, flip, display mode, power save, dim mode, gray
 * table).
 */
@CompileStatic
class Ssd1331Full extends Ssd1331Minimal {

    static final int MODE_NORMAL = 0xA4
    static final int MODE_ALL_ON = 0xA5
    static final int MODE_ALL_OFF = 0xA6
    static final int MODE_INVERSE = 0xA7

    /**
     * Construct the driver and run the initialisation sequence.
     *
     * @param connection display connection (no BUSY pin)
     */
    Ssd1331Full(DisplayConn connection) {
        super(connection)
    }

    // ---- framebuffer ------------------------------------------------------

    /**
     * RGB565 colour of one framebuffer pixel.
     *
     * @param x column
     * @param y row
     * @return the colour, 0 when out of range
     */
    int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return 0
        int i = (y * WIDTH + x) * 2
        ((fb[i] & 0xFF) << 8) | (fb[i + 1] & 0xFF)
    }

    /**
     * Bresenham line into the framebuffer.
     *
     * @param x0    start column
     * @param y0    start row
     * @param x1    end column
     * @param y1    end row
     * @param color RGB565 colour
     */
    void drawLine(int x0, int y0, int x1, int y1, int color) {
        int cx = x0
        int cy = y0
        int dx = Math.abs(x1 - x0)
        int dy = -Math.abs(y1 - y0)
        int sx = x0 < x1 ? 1 : -1
        int sy = y0 < y1 ? 1 : -1
        int err = dx + dy
        while (true) {
            setPixel(cx, cy, color)
            if (cx == x1 && cy == y1) break
            int e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
    }

    /**
     * Rectangle outline or filled rectangle into the framebuffer.
     *
     * @param x     left column
     * @param y     top row
     * @param w     width in pixels
     * @param h     height in pixels
     * @param color RGB565 colour
     * @param fill  {@code true} fills the rectangle
     */
    void drawRect(int x, int y, int w, int h, int color, boolean fill) {
        if (w <= 0 || h <= 0) return
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (fill || i == 0 || j == 0 || i == w - 1 || j == h - 1) setPixel(x + i, y + j, color)
            }
        }
    }

    /**
     * Copy a row-major RGB565 bitmap into the framebuffer.
     *
     * @param x      left column
     * @param y      top row
     * @param pixels {@code w * h} RGB565 values
     * @param w      bitmap width in pixels
     * @param h      bitmap height in pixels
     */
    void blit(int x, int y, int[] pixels, int w, int h) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = j * w + i
                if (k < pixels.length) setPixel(x + i, y + j, pixels[k])
            }
        }
    }

    /**
     * Send only a rectangle of the framebuffer (one window, row by row). The
     * region is clipped to the panel; an empty region sends nothing.
     *
     * @param x left column
     * @param y top row
     * @param w width in pixels
     * @param h height in pixels
     */
    void showRegion(int x, int y, int w, int h) {
        int cx = x
        int cy = y
        int cw = w
        int ch = h
        if (cx < 0) { cw += cx; cx = 0 }
        if (cy < 0) { ch += cy; cy = 0 }
        cw = Math.min(cw, WIDTH - cx)
        ch = Math.min(ch, HEIGHT - cy)
        if (cw <= 0 || ch <= 0) return
        window(cx, cy, cx + cw - 1, cy + ch - 1)
        for (int j = 0; j < ch; j++) {
            int start = ((cy + j) * WIDTH + cx) * 2
            connection.sendData(Arrays.copyOfRange(fb, start, start + cw * 2))
        }
    }

    // ---- graphic acceleration (display RAM only) --------------------------

    private static int[] colorOps(int c) {
        [((c >> 11) & 0x1F) << 1, (c >> 5) & 0x3F, (c & 0x1F) << 1] as int[]
    }

    /**
     * Hardware line ({@code 0x21}) on the display RAM.
     *
     * @param x0    start column 0..95
     * @param y0    start row 0..63
     * @param x1    end column
     * @param y1    end row
     * @param color RGB565 colour
     */
    void hwLine(int x0, int y0, int x1, int y1, int color) {
        int[] c = colorOps(color)
        cmd(CMD_LINE, x0, y0, x1, y1, c[0], c[1], c[2])
    }

    /**
     * Hardware rectangle ({@code 0x26} fill enable + {@code 0x22}) on the
     * display RAM; nothing is sent when {@code w} or {@code h} is below 1.
     *
     * @param x         left column
     * @param y         top row
     * @param w         width
     * @param h         height
     * @param lineColor outline RGB565
     * @param fillColor fill RGB565 (used when {@code fill})
     * @param fill      fill the interior
     */
    void hwRect(int x, int y, int w, int h, int lineColor, int fillColor, boolean fill) {
        if (w <= 0 || h <= 0) return
        cmd(CMD_FILL, fill ? 1 : 0)
        int[] l = colorOps(lineColor)
        int[] f = colorOps(fillColor)
        cmd(CMD_RECT, x, y, x + w - 1, y + h - 1, l[0], l[1], l[2], f[0], f[1], f[2])
    }

    /** Clear a window of the display RAM ({@code 0x25}). */
    void hwClear(int x0, int y0, int x1, int y1) { cmd(CMD_CLEAR, x0, y0, x1, y1) }

    /** Dim a window of the display RAM ({@code 0x24}). */
    void hwDim(int x0, int y0, int x1, int y1) { cmd(CMD_DIM, x0, y0, x1, y1) }

    /** Copy a window of the display RAM to ({@code x}, {@code y}) ({@code 0x23}). */
    void hwCopy(int x0, int y0, int x1, int y1, int x, int y) { cmd(CMD_COPY, x0, y0, x1, y1, x, y) }

    /**
     * Configure and start continuous scrolling ({@code 0x27}, {@code 0x2F}).
     *
     * @param horizontal columns per step 0..95 (0 = none)
     * @param startRow   first scrolled row
     * @param rows       number of scrolled rows
     * @param vertical   rows per step 0..63 (0 = none)
     * @param interval   0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step
     */
    void scroll(int horizontal, int startRow, int rows, int vertical, int interval) {
        cmd(CMD_SCROLL_SETUP, horizontal, startRow, rows, vertical, interval & 3, CMD_SCROLL_ON)
    }

    /** Stop scrolling ({@code 0x2E}); the display RAM must be rewritten afterwards. */
    void scrollStop() { cmd(CMD_SCROLL_OFF) }

    // ---- settings ---------------------------------------------------------

    /** Contrast of colour channels A, B, C ({@code 0x81..0x83}), 0..255. */
    void setContrast(int a, int b, int c) {
        cmd(CMD_CONTRAST_A, a, CMD_CONTRAST_B, b, CMD_CONTRAST_C, c)
    }

    /** Master current ({@code 0x87}), n/16 for n = 1..16 (clamped). */
    void setMasterCurrent(int n) { cmd(CMD_MASTER_CURRENT, Math.min(16, Math.max(1, n)) - 1) }

    /**
     * Display mode.
     *
     * @param mode {@link #MODE_NORMAL}, {@link #MODE_ALL_ON}, {@link #MODE_ALL_OFF} or {@link #MODE_INVERSE}
     */
    void setDisplayMode(int mode) { cmd(mode) }

    /** Raw remap and colour depth byte ({@code 0xA0}). */
    void setRemap(int flags) {
        this.@remap = flags & 0xFF
        cmd(CMD_REMAP, this.@remap)
    }

    /** Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4). */
    void setFlip(boolean horizontal, boolean vertical) {
        int r = this.@remap & ~0x12
        if (horizontal) r |= 0x02
        if (vertical) r |= 0x10
        setRemap(r)
    }

    /** Power save mode ({@code 0xB0}): {@code true} = {@code 0x1A} enable, {@code false} = {@code 0x0B} disable. */
    void setPowerSave(boolean on) { cmd(CMD_POWER_SAVE, on ? 0x1A : 0x0B) }

    /** Dim mode ({@code 0xAB} + {@code 0xAC}): contrasts A/B/C and pre-charge voltage 0..31. */
    void dimMode(int a, int b, int c, int precharge) {
        cmd(CMD_DIM_SETTING, 0x00, a, b, c, precharge & 0x1F, CMD_DISPLAY_DIM)
    }

    /** Restore the linear gray scale table ({@code 0xB9}). */
    void grayLinear() { cmd(CMD_GRAY_LINEAR) }

    /**
     * Send any command with parameters (all with D/C# low).
     *
     * @param command command byte
     * @param params  parameter bytes
     */
    void sendCommand(int command, int... params) {
        cmd(command)
        cmd(params)
    }
}
