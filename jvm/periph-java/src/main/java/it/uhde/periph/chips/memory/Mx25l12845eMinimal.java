package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;

import java.io.IOException;
import java.util.Arrays;

/**
 * Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash — minimal driver
 * (specs/memory/mx25l12845e.md).
 *
 * <p>A byte-addressable store with transparent erase: {@link #readBytes}, an erase-aware
 * {@link #writeBytes} (read-modify-erase-write per 4 KiB sector, programming only what changed)
 * and {@link #eraseSector}. Plain single-I/O SPI Mode 0 over a bare {@code Connection}
 * ({@code write} and {@code writeRead}; CS is held low for each frame by the connection).
 *
 * <p>Construction runs the init sequence (RDP, RDID check C2 20 18, wait until not busy).
 */
public class Mx25l12845eMinimal {

    /** Capacity in bytes. */
    public static final int SIZE = 16 * 1024 * 1024;
    /** Page-program size in bytes. */
    public static final int PAGE_SIZE = 256;
    /** Smallest erase unit in bytes. */
    public static final int SECTOR_SIZE = 4096;

    protected static final int WREN = 0x06;
    protected static final int RDID = 0x9F;
    protected static final int RES = 0xAB;
    protected static final int REMS = 0x90;
    protected static final int RDSR = 0x05;
    protected static final int WRSR = 0x01;
    protected static final int READ = 0x03;
    protected static final int FAST_READ = 0x0B;
    protected static final int PP = 0x02;
    protected static final int SE = 0x20;
    protected static final int BE32K = 0x52;
    protected static final int BE64K = 0xD8;
    protected static final int CE = 0xC7;
    protected static final int DP = 0xB9;
    protected static final int RDP = 0xAB;
    protected static final int RDSFDP = 0x5A;
    protected static final int ENSO = 0xB1;
    protected static final int EXSO = 0xC1;
    protected static final int RDSCUR = 0x2B;
    protected static final int WRSCUR = 0x2F;
    protected static final int CLSR = 0x30;

    protected static final int SR_WIP = 0x01;
    protected static final int SR_WEL = 0x02;
    protected static final int SR_BP_MASK = 0x3C;
    protected static final int SR_BP_SHIFT = 2;
    protected static final int SCUR_P_FAIL = 0x20;
    protected static final int SCUR_E_FAIL = 0x40;

    protected static final long T_WRSR_MS = 150;
    protected static final long T_PP_MS = 10;
    protected static final long T_SE_MS = 400;
    protected static final long T_BE_MS = 3000;
    protected static final long T_CE_MS = 250_000;
    private static final int READ_CHUNK = 4096;

    protected final Connection connection;

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection SPI connection ({@code write}, {@code writeRead})
     * @throws BlockDeviceException {@code NOT_READY} if the JEDEC ID did not match, {@code TIMEOUT} if the chip stayed busy
     * @throws IOException          on a bus error
     */
    public Mx25l12845eMinimal(Connection connection) throws IOException {
        this(connection, T_CE_MS);
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection    SPI connection ({@code write}, {@code writeRead})
     * @param initTimeoutMs how long init waits for a chip that is still busy (a reset during a chip erase
     *                      can leave it busy for up to 200 s)
     * @throws BlockDeviceException {@code NOT_READY} if the JEDEC ID did not match, {@code TIMEOUT} if the chip stayed busy
     * @throws IOException          on a bus error
     */
    public Mx25l12845eMinimal(Connection connection, long initTimeoutMs) throws IOException {
        this.connection = connection;
        connection.write(new byte[] {(byte) RDP});   // leave deep power-down if a previous session left it there
        sleepMs(1);                                  // tRES2 = 100 us
        byte[] id = jedecId();
        if ((id[0] & 0xFF) != 0xC2 || (id[1] & 0xFF) != 0x20 || (id[2] & 0xFF) != 0x18) {
            throw new BlockDeviceException(Kind.NOT_READY, String.format("MX25L12845E not found (JEDEC ID %02x%02x%02x)",
                    id[0] & 0xFF, id[1] & 0xFF, id[2] & 0xFF));
        }
        waitReady(initTimeoutMs);                    // a reset during an erase can leave the chip busy
    }

    // ---- bus primitives --------------------------------------------------

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] header(int cmd, int address) {
        return new byte[] {(byte) cmd, (byte) (address >> 16), (byte) (address >> 8), (byte) address};
    }

    protected int status() throws IOException {
        return connection.writeRead(new byte[] {(byte) RDSR}, 1)[0] & 0xFF;
    }

    protected void wren() throws IOException {
        connection.write(new byte[] {(byte) WREN});
        if ((status() & SR_WEL) == 0) {
            throw new BlockDeviceException(Kind.IO, "WEL not set after WREN");   // the next command would be ignored
        }
    }

    /**
     * Wait until WIP = 0.
     *
     * @param timeoutMs give up after this many milliseconds
     * @throws BlockDeviceException {@code TIMEOUT}
     */
    protected void waitReady(long timeoutMs) throws IOException {
        long start = System.nanoTime();
        while ((status() & SR_WIP) != 0) {
            if ((System.nanoTime() - start) / 1_000_000 >= timeoutMs) {
                throw new BlockDeviceException(Kind.TIMEOUT, "chip busy");
            }
            if (timeoutMs > 20) sleepMs(1);   // long operations: do not hammer the bus
        }
    }

    protected void checkFail() throws IOException {
        int scur = connection.writeRead(new byte[] {(byte) RDSCUR}, 1)[0] & 0xFF;
        if ((scur & (SCUR_P_FAIL | SCUR_E_FAIL)) != 0) {
            connection.write(new byte[] {(byte) CLSR});
            if ((status() & SR_BP_MASK) != 0) {
                throw new BlockDeviceException(Kind.WRITE_PROTECTED, "program/erase hit the block-protected area");
            }
            throw new BlockDeviceException(Kind.IO, String.format("program/erase failed (security register 0x%02X)", scur));
        }
    }

    protected void eraseCmd(int cmd, int address, long timeoutMs) throws IOException {
        wren();
        connection.write(header(cmd, address));
        waitReady(timeoutMs);
        checkFail();
    }

    protected void program(int address, byte[] data, int off, int len) throws IOException {
        wren();
        byte[] frame = new byte[4 + len];
        System.arraycopy(header(PP, address), 0, frame, 0, 4);
        System.arraycopy(data, off, frame, 4, len);
        connection.write(frame);
        waitReady(T_PP_MS);
        checkFail();
    }

    protected void sendCommand(int cmd) throws IOException {
        connection.write(new byte[] {(byte) cmd});
    }

    protected byte[] readRaw(int cmd, int address, boolean dummy, int length) throws IOException {
        byte[] out = new byte[length];
        int done = 0;
        while (done < length) {
            int n = Math.min(READ_CHUNK, length - done);
            byte[] h = header(cmd, address + done);
            byte[] frame = dummy ? Arrays.copyOf(h, 5) : h;
            System.arraycopy(connection.writeRead(frame, n), 0, out, done, n);
            done += n;
        }
        return out;
    }

    // ---- Minimal API -----------------------------------------------------

    /** @return the three RDID bytes (manufacturer, memory type, density) */
    public byte[] jedecId() throws IOException {
        return connection.writeRead(new byte[] {(byte) RDID}, 3);
    }

    /** @return capacity in bytes (16 777 216) */
    public int size() {
        return SIZE;
    }

    /**
     * Read {@code length} bytes starting at {@code address} (READ, 03h), any length and alignment.
     *
     * @throws BlockDeviceException {@code OUT_OF_RANGE} if the range leaves the array
     */
    public byte[] readBytes(int address, int length) throws IOException {
        if (address < 0 || length < 0 || (long) address + length > SIZE) {
            throw new BlockDeviceException(Kind.OUT_OF_RANGE, "read beyond end");
        }
        return readRaw(READ, address, false, length);
    }

    /**
     * Erase the 4 KiB sector containing {@code address} (SE, 20h).
     *
     * @throws BlockDeviceException {@code OUT_OF_RANGE}
     */
    public void eraseSector(int address) throws IOException {
        if (address < 0 || address >= SIZE) throw new BlockDeviceException(Kind.OUT_OF_RANGE, "address beyond end");
        eraseCmd(SE, address, T_SE_MS);
    }

    /**
     * Erase-aware write; returns when the chip is ready. Per 4 KiB sector: if only 1-&gt;0 transitions
     * are needed just the changed pages are programmed, otherwise the sector is erased and rewritten
     * with the merged contents.
     *
     * @throws BlockDeviceException {@code OUT_OF_RANGE} if the range leaves the array, {@code WRITE_PROTECTED}
     *                              if the chip reported a failure while block-protect bits are set
     */
    public void writeBytes(int address, byte[] data) throws IOException {
        int length = data.length;
        if (address < 0 || (long) address + length > SIZE) throw new BlockDeviceException(Kind.OUT_OF_RANGE, "write beyond end");
        int pos = 0;
        while (pos < length) {
            int base = address & ~(SECTOR_SIZE - 1);
            int off = address - base;
            int n = Math.min(SECTOR_SIZE - off, length - pos);
            byte[] old = readRaw(READ, base, false, SECTOR_SIZE);
            boolean needsErase = false;
            for (int i = 0; i < n; i++) {
                if ((old[off + i] & data[pos + i] & 0xFF) != (data[pos + i] & 0xFF)) {   // would need a 0 -> 1 transition
                    needsErase = true;
                    break;
                }
            }
            if (!needsErase) {
                int done = 0;
                while (done < n) {
                    int a = address + done;
                    int part = Math.min(PAGE_SIZE - (a & (PAGE_SIZE - 1)), n - done);
                    if (!Arrays.equals(old, off + done, off + done + part, data, pos + done, pos + done + part)) {
                        program(a, data, pos + done, part);
                    }
                    done += part;
                }
            } else {
                byte[] merged = old.clone();
                System.arraycopy(data, pos, merged, off, n);
                eraseCmd(SE, base, T_SE_MS);
                for (int page = 0; page < SECTOR_SIZE; page += PAGE_SIZE) {
                    boolean blank = true;
                    for (int i = 0; i < PAGE_SIZE; i++) {
                        if (merged[page + i] != (byte) 0xFF) { blank = false; break; }
                    }
                    if (!blank) program(base + page, merged, page, PAGE_SIZE);   // blank pages need no program
                }
            }
            address += n;
            pos += n;
        }
    }
}
