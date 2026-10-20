package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

import java.util.Arrays

/**
 * UC1611s graphic LCD controller-driver — full driver.
 *
 * <p>Extends {@link Uc1611sMinimal} with drawing primitives, contrast, sleep, invert, flip / top view, hardware
 * scroll with fixed lines, bias ratio, temperature compensation, line rate, charge pump / external VLCD, panel
 * loading, partial display, N-line inversion, isolation clock, the four RAM patterns, status and RAM reads, and
 * raw commands.
 */
@CompileStatic
class Uc1611sFull extends Uc1611sMinimal {

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
    Uc1611sFull(Connection command, Connection data, int width, int height, boolean topView, int contrast)
            throws IOException {
        super(command, data, width, height, topView, contrast)
    }

    /** Construct with the default size 240 x 128, bottom view and contrast 0x8F. */
    Uc1611sFull(Connection command, Connection data) throws IOException {
        super(command, data, WIDTH, HEIGHT, false, 0x8F)
    }

    /** Construct on an explicit transport (used by {@link Uc1611sSpiFull}). */
    protected Uc1611sFull(Uc1611sBus bus, int width, int height, boolean topView, int contrast) throws IOException {
        super(bus, width, height, topView, contrast)
    }

    // ---- framebuffer ----------------------------------------------------------------------

    /** Return a framebuffer pixel; out-of-range coordinates read as false. */
    boolean getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        (fb[(y >> 3) * width + x] & (1 << (y & 7))) != 0
    }

    /** Set (or clear) the whole framebuffer. */
    void fill(boolean on) {
        Arrays.fill(fb, (byte) (on ? 0xFF : 0))
    }

    /** Draw a Bresenham line into the framebuffer. */
    void drawLine(int x0, int y0, int x1, int y1, boolean on) {
        int x = x0
        int y = y0
        int dx = Math.abs(x1 - x0)
        int dy = -Math.abs(y1 - y0)
        int sx = x0 < x1 ? 1 : -1
        int sy = y0 < y1 ? 1 : -1
        int err = dx + dy
        while (true) {
            setPixel(x, y, on)
            if (x == x1 && y == y1) break
            int e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
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
     * {@code p} ({@code n = columns.length / heightPages}). The bitmap is clipped to the framebuffer.
     *
     * @throws IllegalArgumentException if y is not a multiple of 8 or heightPages is 0
     */
    void blit(int x, int y, byte[] columns, int heightPages) {
        if (y % 8 != 0 || heightPages <= 0) throw new IllegalArgumentException('y must be a multiple of 8')
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
     * Write one framebuffer page to the display RAM (cheaper partial update). Out-of-range pages are ignored.
     *
     * @throws IOException if the page still fails after all attempts
     */
    void showPage(int page) throws IOException {
        if (page >= 0 && page < pages) showPage0(page)
    }

    // ---- display control ------------------------------------------------------------------

    /** Set the potentiometer PM that adjusts VLCD (contrast), 0..255. */
    void setContrast(int value) throws IOException {
        this.@contrastValue = value & 0xFF
        command(CMD_POTENTIOMETER, contrastValue)
    }

    /** Switch the display on, or into sleep (registers and RAM kept, below 1 uA). */
    void setDisplay(boolean on) throws IOException {
        command(CMD_DISPLAY | (on ? 1 : 0))
    }

    /** Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active. */
    void sleep() throws IOException {
        setDisplay(false)
    }

    /**
     * Leave sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump restarts, so
     * the driver polls DE (at most 50 ms) instead of sleeping.
     */
    void wake() throws IOException {
        setDisplay(true)
        waitDisplayEnabled(50)
    }

    /** Invert the displayed image without changing RAM. */
    void setInvert(boolean on) throws IOException {
        command(CMD_INVERSE | (on ? 1 : 0))
    }

    /** Force all pixels on (test mode, RAM unchanged). */
    void setAllOn(boolean on) throws IOException {
        command(CMD_ALL_ON | (on ? 1 : 0))
    }

    /**
     * Mirror columns (MX) and/or rows (MY). MY acts at once; MX is applied to RAM writes, so call {@link #show()}
     * afterwards to re-send the framebuffer.
     */
    void setFlip(boolean mirrorX, boolean mirrorY) throws IOException {
        this.@mx = mirrorX ? 1 : 0
        this.@my = mirrorY ? 1 : 0
        command(CMD_MAPPING, mapping())
    }

    /** Scroll the image up by {@code line} rows (hardware scroll, wraps at 160 rows); 0 .. 159 - 2 * fixed lines. */
    void setScrollLine(int line) throws IOException {
        command(CMD_SCROLL_LO | (line & 0x0F), CMD_SCROLL_HI | ((line >> 4) & 0x0F))
    }

    /** Keep the first {@code 2 * lines} rows out of the scroll (FL, 0..15). */
    void setFixedLines(int lines) throws IOException {
        command(CMD_FIXED | (lines & 0x0F))
    }

    /** Select the LCD bias ratio: 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12. */
    void setBiasRatio(int ratio) throws IOException {
        command(CMD_BIAS | (ratio & 3))
    }

    /** Select the VBIAS temperature compensation: 0 = -0.05 %/degC, 1 = -0.10, 2 = -0.15, 3 = 0.00. */
    void setTemperatureCompensation(int curve) throws IOException {
        command(CMD_TEMP | (curve & 3))
    }

    /** Select the line rate (On/Off mode): 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second. */
    void setLineRate(int rate) throws IOException {
        command(CMD_LINE_RATE | (rate & 3))
    }

    /** Choose the internal 11x charge pump (true, reset) or an external VLCD supply (false). */
    void setPump(boolean internal) throws IOException {
        command(CMD_PUMP | (internal ? 3 : 0))
    }

    /** Tell the chip the panel capacitance: true = 33..55 nF (reset), false = up to 33 nF. */
    void setPanelLoading(boolean high) throws IOException {
        command(CMD_PANEL | (high ? 3 : 0))
    }

    /**
     * Scan only rows {@code start..end} (the rest sleeps), or return to full scan.
     *
     * @param end last scanned row (DEN); a negative value selects height - 1
     */
    void setPartialDisplay(boolean on, int start, int end) throws IOException {
        if (!on) {
            command(CMD_PARTIAL)
            return
        }
        int last = end < 0 ? height - 1 : end
        command(CMD_PART_START, start & 0xFF, CMD_PART_END, last & 0xFF, CMD_PARTIAL | 3)
    }

    /** Set N-line inversion (cross-talk tuning): {@code lines} 0 (off) .. 63, optional XOR mode. */
    void setNLineInversion(int lines, boolean xor) throws IOException {
        command(CMD_NLINE, (xor ? 0x40 : 0) | (lines & 0x3F))
    }

    /** Set the isolation clock before (front) and after (back) the COM pulse, 0..15 each. */
    void setIsolationClock(int front, int back) throws IOException {
        command(CMD_ISOLATION, 0x13, front & 0x0F)
        command(CMD_ISOLATION, 0x14, back & 0x0F)
    }

    /** Select which of the four RAM screens (patterns, 0..3) is displayed. */
    void setDisplayPattern(int value) throws IOException {
        command(CMD_PATTERN | ((value & 3) << 1) | 1)
    }

    /** Select the RAM screen (0..3) that {@link #show()} / {@link #showPage(int)} write to (PA[6:5]). */
    void setWritePattern(int value) {
        this.@pattern = value & 3
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the three status bytes (a NOP is sent first to restart the chip's status cycle): Status1 (Ver, MX, MY,
     * WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]), Status3 (product code 1 in bits 7..4, ESD flag in bit 0).
     *
     * @throws IOException if the read fails after all attempts
     */
    byte[] readStatus() throws IOException {
        readStatus0()
    }

    /**
     * Read {@code n} bytes of display RAM (the dummy byte after an address change is discarded).
     *
     * @throws IOException if address or read fail after all attempts
     */
    byte[] readRam(int page, int column, int n) throws IOException {
        if (!bus.canRead()) throw new UnsupportedOperationException('the SPI interface of the module is write-only')
        IOException last = null
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                bus.writeCommand(addressBytes(page, column))
                return bus.readRam(n)
            } catch (IOException e) {
                last = e
                noteRetry()
                sleepMs(RETRY_DELAY_MS)
            }
        }
        incrementErrors()
        throw last
    }

    /** System Reset and reconfigure with the cached contrast and mapping (RAM is kept). */
    void reset() throws IOException {
        resetChip()
        configure()
        enable()
        waitDisplayEnabled(50)
    }

    /** Send raw bytes as one command transaction (retried on NACK). */
    void sendCommand(int... bytes) throws IOException {
        command(bytes)
    }

    /**
     * Send raw bytes to the display RAM (chunked) at the current RAM address. A failed chunk is not retried (the
     * partial write cannot be continued); the failure is counted.
     */
    void sendData(byte[] bytes) throws IOException {
        try {
            writeChunks(bytes, 0, bytes.length)
        } catch (IOException e) {
            incrementErrors()
            throw e
        }
    }
}
