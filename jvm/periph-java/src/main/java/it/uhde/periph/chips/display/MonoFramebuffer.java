package it.uhde.periph.chips.display;

import java.util.Arrays;

/**
 * Page-major monochrome framebuffer (bit 0 of a byte = top pixel of its page) with drawing primitives, shared by
 * the SSD130x style OLED drivers.
 */
public final class MonoFramebuffer {

    private final int width;
    private final int height;
    private final int pages;
    private final byte[] data;

    /**
     * Create a zeroed framebuffer.
     *
     * @param width  columns
     * @param height rows, a multiple of 8
     */
    public MonoFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.pages = height / 8;
        this.data = new byte[width * pages];
    }

    /** @return the columns */
    public int width() {
        return width;
    }

    /** @return the rows */
    public int height() {
        return height;
    }

    /** @return the number of 8-pixel pages */
    public int pages() {
        return pages;
    }

    /** @return the page-major bytes (live view, {@code width * pages} long) */
    public byte[] data() {
        return data;
    }

    /** @return the framebuffer column-major (for vertical addressing) */
    public byte[] transposed() {
        byte[] out = new byte[data.length];
        int n = 0;
        for (int x = 0; x < width; x++) {
            for (int p = 0; p < pages; p++) out[n++] = data[p * width + x];
        }
        return out;
    }

    /** Zero the framebuffer. */
    public void clear() {
        Arrays.fill(data, (byte) 0);
    }

    /**
     * Set (or clear) every pixel.
     *
     * @param on true to set every pixel
     */
    public void fill(boolean on) {
        Arrays.fill(data, on ? (byte) 0xFF : (byte) 0);
    }

    /**
     * Set or clear one pixel; out-of-range coordinates are ignored.
     *
     * @param x  column
     * @param y  row
     * @param on true to set the pixel, false to clear it
     */
    public void setPixel(int x, int y, boolean on) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int i = (y >> 3) * width + x;
        if (on) {
            data[i] |= (byte) (1 << (y & 7));
        } else {
            data[i] &= (byte) ~(1 << (y & 7));
        }
    }

    /**
     * Return a pixel; out-of-range coordinates read as false.
     *
     * @param x column
     * @param y row
     * @return true if the pixel is set
     */
    public boolean getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        return (data[(y >> 3) * width + x] & (1 << (y & 7))) != 0;
    }

    /**
     * Bresenham line.
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
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Rectangle outline or filled rectangle.
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
            for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) setPixel(xx, yy, on);
            return;
        }
        drawLine(x, y, x + w - 1, y, on);
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, on);
        drawLine(x, y, x, y + h - 1, on);
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, on);
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top); clipped. {@code columns} holds
     * {@code heightPages} groups of columns, page by page: byte {@code p * n + c} is column {@code c} of page row
     * {@code p}.
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
                if (xx >= 0 && xx < width) data[page * width + xx] = columns[p * n + c];
            }
        }
    }
}
