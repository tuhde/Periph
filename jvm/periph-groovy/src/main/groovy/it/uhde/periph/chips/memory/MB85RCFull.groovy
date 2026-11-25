package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * MB85RC256V — full driver. Extends {@link MB85RCMinimal} with sequential
 * read/write split into bus-sized chunks, range fill, the array size and the
 * 3-byte Device ID read through the reserved slave address 0x7C.
 *
 * Ranges that would run past 0x7FFF are rejected instead of silently
 * wrapping to 0x0000 as the hardware would do.
 */
@CompileStatic
class MB85RCFull extends MB85RCMinimal {

    private static final int DEVICE_ID_BASE = 0xA0

    private int maxChunk = 30

    /**
     * Construct the full driver.
     *
     * @param connection I²C connection with regBytes = 2, bound to 0x50-0x57
     */
    MB85RCFull(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Maximum data bytes per bus transaction (default 30).
     *
     * @return the chunk size
     */
    int getMaxChunk() { maxChunk }

    /**
     * Set the maximum data bytes per bus transaction (Arduino Wire limits a
     * transfer to 32 bytes including the 2 address bytes).
     *
     * @param maxChunk chunk size, at least 1
     */
    void setMaxChunk(int maxChunk) {
        if (maxChunk < 1) throw new IllegalArgumentException('maxChunk must be >= 1')
        this.maxChunk = maxChunk
    }

    private static void checkRange(int address, int length) {
        if (length < 0 || address < 0 || (long) address + length > SIZE) {
            throw new IllegalArgumentException('range outside 0-32767')
        }
    }

    /**
     * Array size in bytes.
     *
     * @return 32768
     */
    int size() { SIZE }

    /**
     * Sequential read, split into chunks of maxChunk bytes.
     *
     * @param address starting address 0-32767
     * @param length  number of bytes to read
     * @return bytes read from the device
     */
    byte[] read(int address, int length) {
        checkRange(address, length)
        byte[] out = new byte[length]
        int done = 0
        while (done < length) {
            int n = Math.min(maxChunk, length - done)
            byte[] part = connection.read(address + done, n)
            System.arraycopy(part, 0, out, done, n)
            done += n
        }
        out
    }

    /**
     * Sequential write, split into chunks of maxChunk bytes; each chunk is a
     * fresh transaction. No delay between chunks.
     *
     * @param address starting address 0-32767
     * @param data    bytes to write
     */
    void write(int address, byte[] data) {
        checkRange(address, data.length)
        int done = 0
        while (done < data.length) {
            int n = Math.min(maxChunk, data.length - done)
            byte[] chunk = new byte[n]
            System.arraycopy(data, done, chunk, 0, n)
            connection.write(address + done, chunk)
            done += n
        }
    }

    /**
     * Write value over a range, in chunks.
     *
     * @param address starting address 0-32767
     * @param length  number of bytes to fill
     * @param value   byte value 0-255 (default 0)
     */
    void fill(int address, int length, int value = 0) {
        checkRange(address, length)
        int done = 0
        while (done < length) {
            int n = Math.min(maxChunk, length - done)
            byte[] chunk = new byte[n]
            Arrays.fill(chunk, (byte) value)
            connection.write(address + done, chunk)
            done += n
        }
    }

    /**
     * Read the Device ID via the reserved slave address 0x7C.
     *
     * The device address word is 0xA0 + (A2A1A0 * 2).
     *
     * @param idConnection connection (regBytes = 1) bound to I²C address 0x7C
     * @param address      7-bit address of the FRAM (0x50-0x57), default 0x50
     * @return [manufacturer, density, product]; [0x00A, 0x5, 0x10] for the MB85RC256V
     */
    int[] readDeviceId(RegisterConnection idConnection, int address = 0x50) {
        int word = DEVICE_ID_BASE + ((address & 0x07) << 1)
        byte[] raw = idConnection.read(word, 3)
        int b0 = raw[0] & 0xFF
        int b1 = raw[1] & 0xFF
        int b2 = raw[2] & 0xFF
        [(b0 << 4) | (b1 >> 4), b1 & 0x0F, b2] as int[]
    }
}
