package it.uhde.periph.chips.rfid;

import it.uhde.periph.connection.RegisterConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ST25DV04K / ST25DV16K / ST25DV64K — dynamic NFC/RFID tag (minimal driver): user memory as a
 * byte store.
 *
 * <p>The chip answers on two I²C addresses, both with 16-bit register addresses: 0x53 (user
 * memory, dynamic registers, fast-transfer-mode mailbox) and 0x57 (system configuration). The
 * driver takes two register connections on the same bus, both created with {@code regBytes} = 2.
 *
 * <p>The constructor reads {@code MEM_SIZE}, {@code BLK_SIZE} and {@code IC_REF} and derives the
 * memory size. No configuration is written; the chip keeps its delivery state (area 1 = the whole
 * memory, no protection, {@code GPO_EN} = 1, field-change interrupt only).
 *
 * <p>Reads and writes are split into transfers of at most 16 bytes aligned to 16-byte boundaries
 * (never crossing a 256-byte page or an area border); every write is completed by ACK polling
 * (5 ms per 4-byte EEPROM page). Reads are retried up to 3 times while an RF reader holds the
 * EEPROM.
 */
public class St25dvMinimal {

    protected static final int SYS_MEM_SIZE = 0x0014;
    protected static final int CHUNK = 16;
    protected static final long WRITE_TIMEOUT_MS = 50;
    protected static final int READ_RETRIES = 3;

    protected final RegisterConnection user;
    protected final RegisterConnection system;
    protected final boolean gpoActiveHigh;
    protected final int size;
    protected final int icRef;

    /**
     * Construct with the -IE default (open-drain, active-low GPO).
     *
     * @param user   connection at 0x53 ({@code regBytes} = 2)
     * @param system connection at 0x57 ({@code regBytes} = 2)
     * @throws IOException on I²C error
     */
    public St25dvMinimal(RegisterConnection user, RegisterConnection system) throws IOException {
        this(user, system, false);
    }

    /**
     * Construct the driver and read the identification.
     *
     * @param user          connection at 0x53 ({@code regBytes} = 2)
     * @param system        connection at 0x57 ({@code regBytes} = 2)
     * @param gpoActiveHigh true for -JF variants (CMOS GPO, rising edge), false for -IE (falling edge)
     * @throws IOException on I²C error
     */
    public St25dvMinimal(RegisterConnection user, RegisterConnection system, boolean gpoActiveHigh) throws IOException {
        this.user = user;
        this.system = system;
        this.gpoActiveHigh = gpoActiveHigh;
        byte[] id = readReg(system, SYS_MEM_SIZE, 4);
        int mem = (id[0] & 0xFF) | ((id[1] & 0xFF) << 8);
        this.size = (mem + 1) * ((id[2] & 0xFF) + 1);
        this.icRef = id[3] & 0xFF;
    }

    /**
     * Register read with up to {@link #READ_RETRIES} attempts, 5 ms apart.
     *
     * @param c      connection
     * @param reg    register address
     * @param length number of bytes
     * @return the bytes read
     * @throws IOException after the last failed attempt
     */
    protected byte[] readReg(RegisterConnection c, int reg, int length) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return c.read(reg, length);
            } catch (IOException e) {
                if (attempt >= READ_RETRIES) throw e;
                sleepMs(5);
            }
        }
    }

    /**
     * ACK-poll: an address-only write is acknowledged once the write cycle has ended.
     *
     * @param c         connection
     * @param timeoutMs maximum time to poll
     * @return true if the chip answered in time
     */
    protected boolean ackPoll(RegisterConnection c, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            try {
                c.write(0, new byte[0]);
                return true;
            } catch (IOException nack) {
                // not acknowledged while the write cycle is running
            }
            if (System.nanoTime() >= deadline) return false;
            sleepMs(1);
        }
    }

    /**
     * ACK-poll and throw if the write cycle does not complete.
     *
     * @param c connection
     * @throws IOException on timeout
     */
    protected void commit(RegisterConnection c) throws IOException {
        if (!ackPoll(c, WRITE_TIMEOUT_MS)) throw new IOException("ST25DV write cycle did not complete");
    }

    /**
     * Sleep, restoring the interrupt flag if interrupted.
     *
     * @param ms milliseconds
     */
    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Validate a user-memory range.
     *
     * @param address start address
     * @param length  number of bytes
     * @throws IllegalArgumentException if outside the memory
     */
    protected void checkRange(int address, int length) {
        if (address < 0 || length < 0 || address + length > size) {
            throw new IllegalArgumentException("range outside the user memory 0-" + (size - 1));
        }
    }

    /**
     * Read user memory.
     *
     * @param address start byte address
     * @param length  number of bytes
     * @return {@code length} bytes
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the range lies outside the memory
     */
    public byte[] read(int address, int length) throws IOException {
        checkRange(address, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        while (length > 0) {
            int n = Math.min(CHUNK - address % CHUNK, length);
            out.writeBytes(readReg(user, address, n));
            address += n;
            length -= n;
        }
        return out.toByteArray();
    }

    /**
     * Write user memory in 16-byte-aligned pieces, ACK-polling after each.
     *
     * @param address start byte address
     * @param data    bytes to write
     * @throws IOException on a NACK (protected area, mailbox active) or a write-cycle timeout
     * @throws IllegalArgumentException if the range lies outside the memory
     */
    public void write(int address, byte[] data) throws IOException {
        checkRange(address, data.length);
        int off = 0;
        while (off < data.length) {
            int a = address + off;
            int n = Math.min(CHUNK - a % CHUNK, data.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            user.write(a, chunk);
            commit(user);
            off += n;
        }
    }
}
