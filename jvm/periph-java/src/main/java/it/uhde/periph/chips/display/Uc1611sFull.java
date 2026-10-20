package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * UC1611s graphic LCD controller-driver — full driver.
 *
 * <p>Extends {@link Uc1611sMinimal} with drawing primitives, contrast, sleep, invert, flip / top view, hardware
 * scroll with fixed lines, bias ratio, temperature compensation, line rate, charge pump / external VLCD, panel
 * loading, partial display, N-line inversion, isolation clock, the four RAM patterns, status and RAM reads, and
 * raw commands.
 */
public class Uc1611sFull extends Uc1611sMinimal {

    /**
     * Construct the driver and run the initialisation sequence.
     *
     * @param command  connection at the command address (0x38, 0x3A, 0x3C or 0x3E)
     * @param data     connection at the data address (command address + 1)
     * @param width    columns, 1..240
     * @param height   rows, a multiple of 8, 8..128
     * @param topView  true for the 12:00 viewing direction (MY = 1, MX = 0)
     * @param contrast potentiometer PM 0..255 (the DOG example value is 0x8F)
     * @throws IOException if the chip does not acknowledge the System Reset
     */
    public Uc1611sFull(Connection command, Connection data, int width, int height, boolean topView, int contrast)
            throws IOException {
        super(command, data, width, height, topView, contrast);
    }

    /** Construct with the default size 240 x 128, bottom view and contrast 0x8F. */
    public Uc1611sFull(Connection command, Connection data) throws IOException {
        super(command, data, WIDTH, HEIGHT, false, 0x8F);
    }

    // ---- framebuffer ----------------------------------------------------------------------

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
        java.util.Arrays.fill(fb, (byte) (on ? 0xFF : 0));
    }

    /** Draw a Bresenham line into the framebuffer. */
    public void drawLine(int x0, int y0, int x1, int y1, boolean on) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
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
     * {@code p} ({@code n = columns.length / heightPages}). The bitmap is clipped to the framebuffer.
     *
     * @param x           left column
     * @param y           top row, a multiple of 8
     * @param columns     bitmap bytes
     * @param heightPages number of 8-pixel-high rows of the bitmap
     * @throws IllegalArgumentException if y is not a multiple of 8 or heightPages is 0
     */
    public void blit(int x, int y, byte[] columns, int heightPages) {
        if (y % 8 != 0 || heightPages <= 0) throw new IllegalArgumentException("y must be a multiple of 8");
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
     * Write one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages are ignored.
     *
     * @param page page 0 .. height / 8 - 1
     * @throws IOException if the page still fails after all attempts
     */
    public void showPage(int page) throws IOException {
        if (page >= 0 && page < pages) showPage0(page);
    }

    // ---- display control ------------------------------------------------------------------

    /**
     * Set the potentiometer PM that adjusts VLCD (contrast).
     *
     * @param value PM, 0..255
     * @throws IOException on I2C error
     */
    public void setContrast(int value) throws IOException {
        contrastValue = value & 0xFF;
        command(CMD_POTENTIOMETER, contrastValue);
    }

    /**
     * Switch the display on, or into sleep (registers and RAM kept, below 1 uA).
     *
     * @param on true for display on (On/Off mode), false for sleep
     * @throws IOException on I2C error
     */
    public void setDisplay(boolean on) throws IOException {
        command(CMD_DISPLAY | (on ? 1 : 0));
    }

    /**
     * Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active.
     *
     * @throws IOException on I2C error
     */
    public void sleep() throws IOException {
        setDisplay(false);
    }

    /**
     * Leave sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump restarts, so
     * the driver polls DE (at most 50 ms) instead of sleeping.
     *
     * @throws IOException on I2C error
     */
    public void wake() throws IOException {
        setDisplay(true);
        waitDisplayEnabled(50);
    }

    /**
     * Invert the displayed image without changing RAM.
     *
     * @param on true to invert
     * @throws IOException on I2C error
     */
    public void setInvert(boolean on) throws IOException {
        command(CMD_INVERSE | (on ? 1 : 0));
    }

    /**
     * Force all pixels on (test mode, RAM unchanged).
     *
     * @param on true to light every pixel
     * @throws IOException on I2C error
     */
    public void setAllOn(boolean on) throws IOException {
        command(CMD_ALL_ON | (on ? 1 : 0));
    }

    /**
     * Mirror columns (MX) and/or rows (MY). MY acts at once; MX is applied to RAM writes, so call {@link #show()}
     * afterwards to re-send the framebuffer.
     *
     * @param mirrorX true to mirror the columns (SEG order; the bottom-view default)
     * @param mirrorY true to mirror the rows (COM order)
     * @throws IOException on I2C error
     */
    public void setFlip(boolean mirrorX, boolean mirrorY) throws IOException {
        mx = mirrorX ? 1 : 0;
        my = mirrorY ? 1 : 0;
        command(CMD_MAPPING, mapping());
    }

    /**
     * Scroll the image up by {@code line} rows (hardware scroll, wraps at 160 rows).
     *
     * @param line SL, 0 .. 159 - 2 * fixed lines
     * @throws IOException on I2C error
     */
    public void setScrollLine(int line) throws IOException {
        command(CMD_SCROLL_LO | (line & 0x0F), CMD_SCROLL_HI | ((line >> 4) & 0x0F));
    }

    /**
     * Keep the first {@code 2 * lines} rows out of the scroll (soft-icon area).
     *
     * @param lines FL, 0..15
     * @throws IOException on I2C error
     */
    public void setFixedLines(int lines) throws IOException {
        command(CMD_FIXED | (lines & 0x0F));
    }

    /**
     * Select the LCD bias ratio.
     *
     * @param ratio 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
     * @throws IOException on I2C error
     */
    public void setBiasRatio(int ratio) throws IOException {
        command(CMD_BIAS | (ratio & 3));
    }

    /**
     * Select the VBIAS temperature compensation coefficient.
     *
     * @param curve 0 = -0.05 %/degC, 1 = -0.10, 2 = -0.15, 3 = 0.00
     * @throws IOException on I2C error
     */
    public void setTemperatureCompensation(int curve) throws IOException {
        command(CMD_TEMP | (curve & 3));
    }

    /**
     * Select the line rate (On/Off mode).
     *
     * @param rate 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second
     * @throws IOException on I2C error
     */
    public void setLineRate(int rate) throws IOException {
        command(CMD_LINE_RATE | (rate & 3));
    }

    /**
     * Choose the internal 11x charge pump or an external VLCD supply.
     *
     * @param internal true for the internal charge pump (reset), false for external VLCD
     * @throws IOException on I2C error
     */
    public void setPump(boolean internal) throws IOException {
        command(CMD_PUMP | (internal ? 3 : 0));
    }

    /**
     * Tell the chip the panel capacitance.
     *
     * @param high true for 33..55 nF (reset), false for up to 33 nF
     * @throws IOException on I2C error
     */
    public void setPanelLoading(boolean high) throws IOException {
        command(CMD_PANEL | (high ? 3 : 0));
    }

    /**
     * Scan only rows {@code start..end} (the rest sleeps), or return to full scan.
     *
     * @param on    true to enable partial display
     * @param start first scanned row (DST)
     * @param end   last scanned row (DEN); a negative value selects height - 1
     * @throws IOException on I2C error
     */
    public void setPartialDisplay(boolean on, int start, int end) throws IOException {
        if (!on) {
            command(CMD_PARTIAL);
            return;
        }
        int last = end < 0 ? height - 1 : end;
        command(CMD_PART_START, start & 0xFF, CMD_PART_END, last & 0xFF, CMD_PARTIAL | 3);
    }

    /**
     * Set N-line inversion (cross-talk tuning).
     *
     * @param lines number of lines, 0 (off) .. 63
     * @param xor   true for XOR mode
     * @throws IOException on I2C error
     */
    public void setNLineInversion(int lines, boolean xor) throws IOException {
        command(CMD_NLINE, (xor ? 0x40 : 0) | (lines & 0x3F));
    }

    /**
     * Set the isolation clock before and after the COM pulse.
     *
     * @param front ISOF, 0..15 (reset 1)
     * @param back  ISOB, 0..15 (reset 0)
     * @throws IOException on I2C error
     */
    public void setIsolationClock(int front, int back) throws IOException {
        command(CMD_ISOLATION, 0x13, front & 0x0F);
        command(CMD_ISOLATION, 0x14, back & 0x0F);
    }

    /**
     * Select which of the four RAM screens (patterns) is displayed.
     *
     * @param pattern 0..3
     * @throws IOException on I2C error
     */
    public void setDisplayPattern(int pattern) throws IOException {
        command(CMD_PATTERN | ((pattern & 3) << 1) | 1);
    }

    /**
     * Select the RAM screen that {@link #show()} / {@link #showPage(int)} write to (PA[6:5]).
     *
     * @param pattern 0..3
     */
    public void setWritePattern(int pattern) {
        this.pattern = pattern & 3;
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the three status bytes (a NOP is sent first to restart the chip's status cycle).
     *
     * @return Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]), Status3 (product code 1 in
     *         bits 7..4, ESD flag in bit 0)
     * @throws IOException if the read fails after all attempts
     */
    public byte[] readStatus() throws IOException {
        return readStatus0();
    }

    /**
     * Read {@code n} bytes of display RAM (the dummy byte after an address change is discarded).
     *
     * @param page   RAM page
     * @param column start column
     * @param n      number of bytes
     * @return the RAM bytes
     * @throws IOException if address or read fail after all attempts
     */
    public byte[] readRam(int page, int column, int n) throws IOException {
        byte[] raw = retry(() -> {
            cmd.write(addressBytes(page, column));
            return data.read(n + 1);
        });
        byte[] out = new byte[n];
        System.arraycopy(raw, 1, out, 0, n);
        return out;
    }

    /**
     * System Reset and reconfigure with the cached contrast and mapping (RAM is kept).
     *
     * @throws IOException on I2C error
     */
    public void reset() throws IOException {
        resetChip();
        configure();
        enable();
        waitDisplayEnabled(50);
    }

    /**
     * Send raw bytes as one command transaction (retried on NACK).
     *
     * @param bytes command and argument bytes
     * @throws IOException on I2C error
     */
    public void sendCommand(int... bytes) throws IOException {
        command(bytes);
    }

    /**
     * Send raw bytes to the display RAM (chunked) at the current RAM address. A failed chunk is not retried (the
     * partial write cannot be continued); the failure is counted.
     *
     * @param bytes data bytes
     * @throws IOException on I2C error
     */
    public void sendData(byte[] bytes) throws IOException {
        try {
            writeChunks(bytes, 0, bytes.length);
        } catch (IOException e) {
            incrementErrors();
            throw e;
        }
    }
}
