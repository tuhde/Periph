package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * CAT24C512 — 512 Kbit (64 KiB) I²C EEPROM (minimal driver).
 *
 * <p>Writes are self-timed (5 ms max) and completed by ACK polling: after every write the driver
 * polls the device address until the chip answers again, so a returned write is always committed.
 * The chip has no configuration registers; it is ready within 1 ms of power-up.
 *
 * <p>I²C address 0x50-0x57, set by the A2:A0 pins. The memory address is two bytes (16 bits,
 * big-endian), so the connection must be created with {@code regBytes} = 2.
 *
 * <h2>Memory layout</h2>
 * <ul>
 *   <li>0x0000-0xFFFF — 65,536 bytes in 512 pages of 128 bytes</li>
 * </ul>
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>The full 64 KiB is addressable as raw bytes</li>
 *   <li>{@link #writeByte(int, int)} returns after the write cycle has finished</li>
 *   <li>Writes are ignored while the WP pin is high (hardware only; the driver cannot detect it)</li>
 * </ul>
 */
public class CAT24C512Minimal {

    protected static final int SIZE = 0x10000;
    protected static final int PAGE = 128;
    /** tWR = 5 ms max, plus margin. */
    protected static final long WRITE_TIMEOUT_MS = 10;

    protected final RegisterConnection connection;

    /**
     * Construct the driver. No I²C traffic occurs at construction.
     *
     * @param connection I²C connection with {@code regBytes} = 2, bound to 0x50-0x57
     */
    public CAT24C512Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    /**
     * Validate a single address.
     *
     * @param address memory address
     * @throws IllegalArgumentException if outside 0-65535
     */
    protected static void checkAddress(int address) {
        if (address < 0 || address >= SIZE) {
            throw new IllegalArgumentException("address out of range 0-65535");
        }
    }

    /**
     * ACK-poll: an address-only write is acknowledged once the write cycle has ended.
     *
     * @param timeoutMs maximum time to poll
     * @return true if the chip answered within the timeout
     */
    protected boolean pollReady(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            try {
                connection.write(0, new byte[0]);
                return true;
            } catch (IOException nack) {
                // not acknowledged while the write cycle is running
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(0, 500_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Wait for the write cycle that was just started; throws if it does not finish. */
    protected void commit() throws IOException {
        if (!pollReady(WRITE_TIMEOUT_MS)) {
            throw new IOException("CAT24C512 write cycle did not complete");
        }
    }

    /**
     * Read a single byte (random read).
     *
     * @param address memory address 0-65535
     * @return byte value 0-255
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the address is out of range
     */
    public int readByte(int address) throws IOException {
        checkAddress(address);
        return connection.read(address, 1)[0] & 0xFF;
    }

    /**
     * Write a single byte, then ACK-poll until the write cycle completes.
     *
     * @param address memory address 0-65535
     * @param value   byte value 0-255
     * @throws IOException on I²C error or if the write cycle does not complete in time
     * @throws IllegalArgumentException if the address is out of range
     */
    public void writeByte(int address, int value) throws IOException {
        checkAddress(address);
        connection.write(address, new byte[]{(byte) value});
        commit();
    }
}
