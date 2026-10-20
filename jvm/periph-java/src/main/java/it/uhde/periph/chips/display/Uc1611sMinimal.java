package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * UC1611s graphic LCD controller-driver — minimal driver.
 *
 * <p>Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white mode over I2C. The chip
 * carries the command/data bit in its I2C address, so it answers on two consecutive 7-bit addresses:
 * {@code 0x38 + 2n} for commands and {@code 0x39 + 2n} for display RAM data (n = A3 &lt;&lt; 1 | A2, the two address
 * pins). The driver therefore takes two connections on the same bus.
 *
 * <p>All drawing goes into a framebuffer of {@code width * height / 8} bytes (page-major, bit 0 = top pixel of the
 * page, 3840 bytes at 240 x 128); {@link #show()} sends it to the display RAM. The initialisation follows the DOG
 * module's bottom-view example: System Reset, temperature compensation -0.10 %/degC, COM end 127, potentiometer
 * (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
 *
 * <p>Error handling: the chip occasionally NACKs a transfer (seen on long cables and for about 6 ms while the
 * charge pump restarts). Every transfer is retried up to {@link #MAX_ATTEMPTS} times, {@link #RETRY_DELAY_MS}
 * apart: a command stream is repeated and a RAM page is rewritten from its address command (a partial write cannot
 * be continued). Repeated attempts are counted ({@link #retryCount()}); a transfer that still fails throws its
 * {@link IOException} and is counted in {@link #errorCount()}.
 */
public class Uc1611sMinimal {

    public static final int WIDTH = 240;
    public static final int HEIGHT = 128;
    /** Largest data write in bytes (the 32-byte limit of some I2C layers). */
    public static final int I2C_CHUNK = 32;
    /** Attempts per transfer before it is counted as failed. */
    public static final int MAX_ATTEMPTS = 8;
    /** Wait between two attempts in milliseconds. */
    public static final int RETRY_DELAY_MS = 2;

    protected static final int PAGES_RAM = 20;   // RAM pages cleared at init (160 rows / 8)
    protected static final int CMD_TEMP = 0x24;          // | TC[1:0]
    protected static final int CMD_PANEL = 0x28;         // | PC[1:0]
    protected static final int CMD_PUMP = 0x2C;          // | PC[3:2]
    protected static final int CMD_SCROLL_LO = 0x40;     // | SL[3:0]
    protected static final int CMD_SCROLL_HI = 0x50;     // | SL[7:4]
    protected static final int CMD_PAGE_LO = 0x60;       // | PA[3:0]
    protected static final int CMD_PAGE_HI = 0x70;       // | PA[6:4]
    protected static final int CMD_POTENTIOMETER = 0x81;
    protected static final int CMD_ISOLATION = 0x82;
    protected static final int CMD_PARTIAL = 0x84;       // | LC[9:8]
    protected static final int CMD_RAM_ADDR = 0x88;      // | AC[2:0]
    protected static final int CMD_FIXED = 0x90;         // | FL[3:0]
    protected static final int CMD_LINE_RATE = 0xA0;     // | LC[5:4]
    protected static final int CMD_ALL_ON = 0xA4;        // | DC1
    protected static final int CMD_INVERSE = 0xA6;       // | DC0
    protected static final int CMD_DISPLAY = 0xA8;       // | DC[4:2]
    protected static final int CMD_MAPPING = 0xC0;
    protected static final int CMD_NLINE = 0xC8;
    protected static final int CMD_PATTERN = 0xD0;       // | DC[7:5]
    protected static final int CMD_RESET = 0xE2;
    protected static final int CMD_NOP = 0xE3;
    protected static final int CMD_BIAS = 0xE8;          // | BR[1:0]
    protected static final int CMD_COM_END = 0xF1;
    protected static final int CMD_PART_START = 0xF2;
    protected static final int CMD_PART_END = 0xF3;

    /** Functional interface for one retried transfer. */
    protected interface Transfer<T> {
        T run() throws IOException;
    }

    protected final Connection cmd;
    protected final Connection data;
    protected final int width;
    protected final int height;
    protected final int pages;
    protected final byte[] fb;
    protected int mx;
    protected int my;
    protected int pattern;
    protected int contrastValue;
    private int retries;
    private int errors;

    /** Count one failed transfer (used by the raw data write of {@link Uc1611sFull}). */
    protected void incrementErrors() {
        errors++;
    }

    /**
     * Construct the driver and run the initialisation sequence (about 400 ms).
     *
     * @param command connection at the command address (0x38, 0x3A, 0x3C or 0x3E)
     * @param data    connection at the data address (command address + 1)
     * @param width   columns, 1..240
     * @param height  rows, a multiple of 8, 8..128
     * @throws IllegalArgumentException if width or height is out of range
     * @throws IOException              if the chip does not acknowledge the System Reset
     */
    public Uc1611sMinimal(Connection command, Connection data, int width, int height) throws IOException {
        this(command, data, width, height, false, 0x8F);
    }

    /** Construct with the default size 240 x 128. */
    public Uc1611sMinimal(Connection command, Connection data) throws IOException {
        this(command, data, WIDTH, HEIGHT, false, 0x8F);
    }

    protected Uc1611sMinimal(Connection command, Connection data, int width, int height, boolean topView,
                             int contrast) throws IOException {
        if (width < 1 || width > WIDTH) throw new IllegalArgumentException("width must be 1..240");
        if (height < 8 || height > HEIGHT || height % 8 != 0) {
            throw new IllegalArgumentException("height must be a multiple of 8, 8..128");
        }
        this.cmd = command;
        this.data = data;
        this.width = width;
        this.height = height;
        this.pages = height / 8;
        this.fb = new byte[width * pages];
        this.mx = topView ? 0 : 1;
        this.my = topView ? 1 : 0;
        this.contrastValue = contrast & 0xFF;
        initSequence();
    }

    /** @return the columns of the framebuffer */
    public int width() { return width; }

    /** @return the rows of the framebuffer */
    public int height() { return height; }

    /** @return true if no transfer has failed after all retries since construction or {@link #clearErrors()} */
    public boolean ok() { return errors == 0; }

    /** @return the number of transfer attempts that had to be repeated */
    public int retryCount() { return retries; }

    /** @return the number of transfers that still failed after {@link #MAX_ATTEMPTS} attempts */
    public int errorCount() { return errors; }

    /** Reset the retry and error counters. */
    public void clearErrors() {
        retries = 0;
        errors = 0;
    }

    /** Zero the framebuffer. Call {@link #show()} to update the panel. */
    public void clear() {
        java.util.Arrays.fill(fb, (byte) 0);
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     *
     * @param x  column, 0 .. width - 1
     * @param y  row, 0 .. height - 1
     * @param on true to set the pixel, false to clear it
     */
    public void setPixel(int x, int y, boolean on) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int i = (y >> 3) * width + x;
        if (on) {
            fb[i] |= (byte) (1 << (y & 7));
        } else {
            fb[i] &= (byte) ~(1 << (y & 7));
        }
    }

    /**
     * Write the whole framebuffer to the display RAM (page + column, then width data bytes per page).
     *
     * @throws IOException if a page still fails after all attempts
     */
    public void show() throws IOException {
        for (int page = 0; page < pages; page++) {
            showPage0(page);
        }
    }

    // ---- internals ------------------------------------------------------------------------

    protected static void sleepMs(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Run {@code fn} up to MAX_ATTEMPTS times (RETRY_DELAY_MS apart); count retries and the final failure. */
    protected <T> T retry(Transfer<T> fn) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return fn.run();
            } catch (IOException e) {
                last = e;
                retries++;
                sleepMs(RETRY_DELAY_MS);
            }
        }
        errors++;
        throw last;
    }

    /** Send bytes as one command transaction on the command address, retried on failure. */
    protected void command(int... values) throws IOException {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        retry(() -> {
            cmd.write(bytes);
            return null;
        });
    }

    /** One pass over the data in chunks of at most I2C_CHUNK bytes (throws on a NACK). */
    protected void writeChunks(byte[] buf, int off, int len) throws IOException {
        for (int i = 0; i < len; i += I2C_CHUNK) {
            int n = Math.min(I2C_CHUNK, len - i);
            byte[] chunk = new byte[n];
            System.arraycopy(buf, off + i, chunk, 0, n);
            data.write(chunk);
        }
    }

    protected byte[] addressBytes(int page, int column) {
        int pa = (pattern << 5) | (page & 0x1F);
        return new byte[] {
            (byte) (CMD_PAGE_LO | (pa & 0x0F)), (byte) (CMD_PAGE_HI | ((pa >> 4) & 0x07)),
            (byte) (column & 0x0F), (byte) (0x10 | (column >> 4)),
        };
    }

    /** Write one page: address command, then the data; a failure repeats both. */
    protected void writePage(int page, byte[] buf, int off, int len) throws IOException {
        retry(() -> {
            cmd.write(addressBytes(page, 0));
            writeChunks(buf, off, len);
            return null;
        });
    }

    protected void showPage0(int page) throws IOException {
        writePage(page, fb, page * width, width);
    }

    /** Read Status1..3; a NOP write first restarts the chip's status cycle (it advances by n + 1 per read). */
    protected byte[] readStatus0() throws IOException {
        return retry(() -> {
            cmd.write(new byte[] {(byte) CMD_NOP});
            return cmd.read(3);
        });
    }

    /** Poll Status1.DE until the pump is up; NACKs while it starts are expected and not counted. */
    protected void waitDisplayEnabled(int timeoutMs) throws IOException {
        for (int waited = 0; waited <= timeoutMs; waited += RETRY_DELAY_MS) {
            try {
                cmd.write(new byte[] {(byte) CMD_NOP});
                byte[] s = cmd.read(3);
                if ((s[0] & 0x08) != 0) return;
            } catch (IOException e) {
                // expected while the charge pump restarts
            }
            sleepMs(RETRY_DELAY_MS);
        }
    }

    protected int mapping() {
        return (my << 2) | (mx << 1);
    }

    /** System Reset in its own transaction, then the conservative 150 ms wait. */
    protected void resetChip() throws IOException {
        command(CMD_RESET);
        sleepMs(150);
    }

    /** Configuration stream of the DOG bottom-view example (the display stays off). */
    protected void configure() throws IOException {
        int last = height - 1;
        command(CMD_TEMP | 1,
                CMD_MAPPING, mapping(),
                CMD_LINE_RATE | 3,
                CMD_COM_END, last,
                CMD_PART_START, 0,
                CMD_PART_END, last,
                CMD_POTENTIOMETER, contrastValue,
                CMD_RAM_ADDR | 1,
                CMD_PATTERN | 1);
    }

    protected void enable() throws IOException {
        command(CMD_DISPLAY | 1);
    }

    private void initSequence() throws IOException {
        sleepMs(150);
        resetChip();
        configure();
        byte[] zeros = new byte[width];
        for (int page = 0; page < PAGES_RAM; page++) {
            writePage(page, zeros, 0, width);
        }
        enable();
        waitDisplayEnabled(200);
    }
}
