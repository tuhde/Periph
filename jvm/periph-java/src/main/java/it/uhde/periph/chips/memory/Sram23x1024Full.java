package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * 23X1024 — full driver. Extends the minimal driver with block access,
 * operating modes and I/O reset.
 *
 * <p>{@code read}/{@code write}/{@code fill} have linear-address semantics in
 * every mode: transfers are split into transactions of at most {@code maxChunk}
 * data bytes and, in Page/Byte mode, at the 32-byte page / single-byte boundaries
 * the hardware would otherwise wrap or truncate at. Ranges that would leave the
 * array throw {@code IllegalArgumentException} instead of silently wrapping.
 */
public class Sram23x1024Full extends Sram23x1024Minimal {

    /** Operating mode: each READ/WRITE transfers exactly one data byte. */
    public static final int MODE_BYTE = 0x00;
    /** Operating mode: auto-increment wraps within the 32-byte page. */
    public static final int MODE_PAGE = 0x80;
    /** Operating mode: auto-increment across the whole array (power-on default). */
    public static final int MODE_SEQUENTIAL = 0x40;

    private static final int PAGE_SIZE = 32;
    private static final int MAX_CHUNK_LIMIT = 256;

    private int mode = MODE_SEQUENTIAL;
    private int maxChunkBytes = MAX_CHUNK_LIMIT;

    /**
     * Construct the full driver.
     *
     * @param connection SPI connection (Mode 0, MSB first, CS managed by the connection)
     * @throws IOException on bus error or a MODE register mismatch
     */
    public Sram23x1024Full(Connection connection) throws IOException {
        super(connection);
    }

    private int chunkLen(int address, int remaining) {
        int n = Math.min(remaining, maxChunkBytes);
        if (mode == MODE_BYTE) return 1;
        if (mode == MODE_PAGE) n = Math.min(n, PAGE_SIZE - (address % PAGE_SIZE));
        return n;
    }

    private static void checkRange(int address, int length) {
        if (address < 0 || length < 0 || (long) address + length > SIZE) {
            throw new IllegalArgumentException("range leaves the 128 KiB array");
        }
    }

    /**
     * Block read.
     *
     * @param address start address; address + length must be &lt;= 131072
     * @param length  number of bytes
     * @return the bytes read
     * @throws IOException on bus error
     */
    public byte[] read(int address, int length) throws IOException {
        checkRange(address, length);
        byte[] out = new byte[length];
        int pos = 0;
        while (pos < length) {
            int n = chunkLen(address, length - pos);
            byte[] part = readRaw(address, n);
            System.arraycopy(part, 0, out, pos, n);
            address += n;
            pos += n;
        }
        return out;
    }

    /**
     * Block write (same range rule as {@link #read(int, int)}).
     *
     * @param address start address
     * @param data    bytes to write
     * @throws IOException on bus error
     */
    public void write(int address, byte[] data) throws IOException {
        checkRange(address, data.length);
        int pos = 0;
        while (pos < data.length) {
            int n = chunkLen(address, data.length - pos);
            writeRaw(address, data, pos, n);
            address += n;
            pos += n;
        }
    }

    /**
     * Write {@code value} over {@code length} bytes starting at {@code address} (chunked).
     *
     * @param address start address
     * @param length  number of bytes
     * @param value   byte value 0-255
     * @throws IOException on bus error
     */
    public void fill(int address, int length, int value) throws IOException {
        checkRange(address, length);
        byte[] block = new byte[PAGE_SIZE];
        java.util.Arrays.fill(block, (byte) value);
        while (length > 0) {
            int n = Math.min(chunkLen(address, length), block.length);
            writeRaw(address, block, 0, n);
            address += n;
            length -= n;
        }
    }

    /** Array size in bytes. @return 131072 */
    public int size() {
        return SIZE;
    }

    /**
     * Select the operating mode (WRMR) and verify it with RDMR.
     *
     * @param mode {@link #MODE_BYTE}, {@link #MODE_PAGE} or {@link #MODE_SEQUENTIAL}
     * @throws IOException on bus error or read-back mismatch
     * @throws IllegalArgumentException for any other value
     */
    public void setMode(int mode) throws IOException {
        if (mode != MODE_BYTE && mode != MODE_PAGE && mode != MODE_SEQUENTIAL) {
            throw new IllegalArgumentException("mode must be MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL");
        }
        writeMode(mode);
        int got = readMode();
        if (got != mode) throw new IOException(String.format("23X1024 MODE read-back 0x%02X, expected 0x%02X", got, mode));
        this.mode = mode;
    }

    /**
     * Read the operating mode (RDMR bits 7:6).
     *
     * @return one of the MODE_* constants (0xC0 if the reserved value is read)
     * @throws IOException on bus error
     */
    public int getMode() throws IOException {
        return readMode() & MODE_MASK;
    }

    /**
     * Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO,
     * then resynchronise the cached mode with the chip.
     *
     * @throws IOException on bus error
     */
    public void resetIo() throws IOException {
        connection.write(new byte[] {(byte) CMD_RSTIO});
        this.mode = getMode();
    }

    /** Maximum data bytes per bus transaction. @return 1-256 (default 256) */
    public int maxChunk() {
        return maxChunkBytes;
    }

    /**
     * Set the maximum data bytes per bus transaction.
     *
     * @param n 1-256
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxChunk(int n) {
        if (n < 1 || n > MAX_CHUNK_LIMIT) throw new IllegalArgumentException("maxChunk must be 1-256");
        this.maxChunkBytes = n;
    }
}
