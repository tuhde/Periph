package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.Arrays

/**
 * AT24C02N — full driver. Extends {@link AT24C02NMinimal} with sequential read, page-boundary-aware
 * write, single page write, range fill, the array and page size and explicit ACK polling.
 *
 * <p>Writes never cross a 8-byte page boundary (that would wrap inside the page and corrupt
 * data) and every chunk is ACK-polled. Ranges that would run past 0x00FF are rejected instead of
 * wrapping to 0x0000 as the hardware would do.
 */
@CompileStatic
class AT24C02NFull extends AT24C02NMinimal {

    private int maxChunk = 30;

    /**
     * Construct the full driver.
     *
     * @param connection I²C connection with {@code regBytes} = 1, bound to 0x50-0x57
     */
    AT24C02NFull(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Maximum data bytes per bus transaction (default 30).
     *
     * @return the chunk size
     */
    int getMaxChunk() { return maxChunk; }

    /**
     * Set the maximum data bytes per bus transaction (Arduino Wire limits a transfer to 32 bytes
     * including the address byte).
     *
     * @param maxChunk chunk size, at least 1
     * @throws IllegalArgumentException if less than 1
     */
    void setMaxChunk(int maxChunk) {
        if (maxChunk < 1) throw new IllegalArgumentException("maxChunk must be >= 1");
        this.@maxChunk = maxChunk
    }

    private static void checkRange(int address, int length) {
        if (length < 0 || address < 0 || (long) address + length > SIZE) {
            throw new IllegalArgumentException("range outside 0-255");
        }
    }

    private int chunkLen(int address, int remaining) {
        return Math.min(Math.min(PAGE - address % PAGE, maxChunk), remaining);
    }

    /**
     * Array size in bytes.
     *
     * @return 256
     */
    int size() { return SIZE; }

    /**
     * Write page size in bytes.
     *
     * @return 8
     */
    int pageSize() { return PAGE; }

    /**
     * ACK-poll until the chip answers its device address again.
     *
     * @param timeoutMs maximum time to poll in milliseconds
     * @return true if the chip responded within the timeout
     */
    boolean waitReady(int timeoutMs) { return pollReady(timeoutMs); }

    /**
     * Sequential read, split into transactions of at most {@link #getMaxChunk()} bytes.
     *
     * @param address starting address 0-255
     * @param length  number of bytes
     * @return the bytes read
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if address…address+length is outside 0x00-0x00FF
     */
    byte[] read(int address, int length) throws IOException {
        checkRange(address, length);
        byte[] out = new byte[length];
        int off = 0;
        while (off < length) {
            int n = Math.min(maxChunk, length - off);
            System.arraycopy(connection.read(address + off, n), 0, out, off, n);
            off += n;
        }
        return out;
    }

    /**
     * Write {@code data} starting at {@code address}, split at 8-byte page boundaries and
     * {@link #getMaxChunk()}; the write cycle is ACK-polled after every chunk.
     *
     * @param address starting address 0-255
     * @param data    bytes to write
     * @throws IOException on I²C error or if a write cycle does not complete
     * @throws IllegalArgumentException if address…address+data.length is outside 0x00-0x00FF
     */
    void write(int address, byte[] data) throws IOException {
        checkRange(address, data.length);
        int off = 0;
        while (off < data.length) {
            int n = chunkLen(address + off, data.length - off);
            connection.write(address + off, Arrays.copyOfRange(data, off, off + n));
            commit();
            off += n;
        }
    }

    /**
     * Write data that lies inside one 8-byte page (sent in {@link #getMaxChunk()} pieces), ACK-polled.
     *
     * @param address starting address 0-255
     * @param data    1-8 bytes, all inside the same page
     * @throws IOException on I²C error or if a write cycle does not complete
     * @throws IllegalArgumentException if the data crosses a page boundary or runs outside 0x00-0x00FF
     */
    void writePage(int address, byte[] data) throws IOException {
        checkRange(address, data.length);
        if (data.length > PAGE || address % PAGE + data.length > PAGE) {
            throw new IllegalArgumentException("write crosses a page boundary");
        }
        write(address, data);
    }

    /**
     * Write {@code value} over a range, page by page.
     *
     * @param address starting address 0-255
     * @param length  number of bytes to fill
     * @param value   byte value 0-255
     * @throws IOException on I²C error or if a write cycle does not complete
     * @throws IllegalArgumentException if address…address+length is outside 0x00-0x00FF
     */
    void fill(int address, int length, int value) throws IOException {
        checkRange(address, length);
        int done = 0;
        while (done < length) {
            int n = chunkLen(address + done, length - done);
            byte[] chunk = new byte[n];
            Arrays.fill(chunk, (byte) value);
            connection.write(address + done, chunk);
            commit();
            done += n;
        }
    }

    /**
     * Write zero over a range, page by page.
     *
     * @param address starting address 0-255
     * @param length  number of bytes to fill
     * @throws IOException on I²C error or if a write cycle does not complete
     * @throws IllegalArgumentException if address…address+length is outside 0x00-0x00FF
     */
    void fill(int address, int length) throws IOException {
        fill(address, length, 0);
    }
}
