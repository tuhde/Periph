package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.storage.BlockDevice;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;
import it.uhde.periph.storage.BlockInfo;

import java.io.IOException;
import java.util.Arrays;

/**
 * Macronix MX25L12845E — full driver: raw program / erase commands, status and block protection,
 * deep power-down, SFDP and the secured OTP; implements {@link BlockDevice} (512-byte blocks,
 * 4 KiB erase units, {@code needsErase() == true}).
 */
public class Mx25l12845eFull extends Mx25l12845eMinimal implements BlockDevice {

    private static final int BLOCK_SIZE = 512;

    private final boolean autoErase;

    /**
     * Construct the driver and run the init sequence ({@code autoErase} off).
     *
     * @param connection SPI connection
     */
    public Mx25l12845eFull(Connection connection) throws IOException {
        this(connection, false, T_CE_MS);
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection SPI connection
     * @param autoErase  {@link #write} erases sectors itself when a 0-&gt;1 transition is needed; otherwise
     *                   it throws {@code NOT_ERASED}
     */
    public Mx25l12845eFull(Connection connection, boolean autoErase) throws IOException {
        this(connection, autoErase, T_CE_MS);
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection    SPI connection
     * @param autoErase     see {@link #Mx25l12845eFull(Connection, boolean)}
     * @param initTimeoutMs see {@link Mx25l12845eMinimal#Mx25l12845eMinimal(Connection, long)}
     */
    public Mx25l12845eFull(Connection connection, boolean autoErase, long initTimeoutMs) throws IOException {
        super(connection, initTimeoutMs);
        this.autoErase = autoErase;
    }

    private static BlockDeviceException outOfRange(String what) {
        return new BlockDeviceException(Kind.OUT_OF_RANGE, what);
    }

    // ---- raw operations --------------------------------------------------

    /** FAST_READ (0Bh) of {@code length} bytes. */
    public byte[] fastRead(int address, int length) throws IOException {
        if (address < 0 || length < 0 || (long) address + length > SIZE) throw outOfRange("read beyond end");
        return readRaw(FAST_READ, address, true, length);
    }

    /** One PP of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased. */
    public void pageProgram(int address, byte[] data) throws IOException {
        int n = data.length;
        if (n == 0 || n > PAGE_SIZE || address < 0 || (long) address + n > SIZE) throw outOfRange("bad page program range");
        if ((address & (PAGE_SIZE - 1)) + n > PAGE_SIZE) throw outOfRange("page program would wrap inside the page");
        program(address, data, 0, n);
    }

    /** Erase the 32 KiB block containing {@code address} (BE32K, 52h). */
    public void eraseBlock32k(int address) throws IOException {
        if (address < 0 || address >= SIZE) throw outOfRange("address beyond end");
        eraseCmd(BE32K, address, T_BE_MS);
    }

    /** Erase the 64 KiB block containing {@code address} (BE, D8h). */
    public void eraseBlock64k(int address) throws IOException {
        if (address < 0 || address >= SIZE) throw outOfRange("address beyond end");
        eraseCmd(BE64K, address, T_BE_MS);
    }

    /** Erase the whole chip (CE, C7h); 80 s typical, 200 s max; needs BP3:0 == 0. */
    public void chipErase() throws IOException {
        wren();
        sendCommand(CE);
        waitReady(T_CE_MS);
        checkFail();
    }

    // ---- status / protection ---------------------------------------------

    /** @return the status register */
    public int readStatus() throws IOException {
        return status();
    }

    /** WREN + WRSR; waits for tW. */
    public void writeStatus(int value) throws IOException {
        wren();
        connection.write(new byte[] {(byte) WRSR, (byte) value});
        waitReady(T_WRSR_MS);
    }

    /** @return BP3:0 (0..15) from the status register */
    public int protection() throws IOException {
        return (status() & SR_BP_MASK) >> SR_BP_SHIFT;
    }

    /** Set BP3:0 (non-volatile); QE and SRWD are preserved. */
    public void setProtection(int bp) throws IOException {
        if (bp < 0 || bp > 15) throw outOfRange("BP must be 0..15");
        writeStatus((status() & ~SR_BP_MASK) | (bp << SR_BP_SHIFT));
    }

    /** @return true while a program, erase or WRSR is running (WIP) */
    public boolean isBusy() throws IOException {
        return (status() & SR_WIP) != 0;
    }

    /**
     * Wait until WIP = 0.
     *
     * @throws BlockDeviceException {@code TIMEOUT}
     */
    public void waitUntilReady(long timeoutMs) throws IOException {
        waitReady(timeoutMs);
    }

    /** @return the security register (RDSCUR) */
    public int readSecurity() throws IOException {
        return connection.writeRead(new byte[] {(byte) RDSCUR}, 1)[0] & 0xFF;
    }

    /** CLSR: clear P_FAIL / E_FAIL. */
    public void clearFailFlags() throws IOException {
        sendCommand(CLSR);
    }

    // ---- power / identification ------------------------------------------

    /** DP: enter deep power-down (only RDP / RES are accepted afterwards). */
    public void deepPowerDown() throws IOException {
        sendCommand(DP);
    }

    /** RDP: leave deep power-down and wait tRES2. */
    public void releaseDeepPowerDown() throws IOException {
        sendCommand(RDP);
        sleepMs(1);
    }

    /** @return the RES electronic ID (17h) */
    public int electronicId() throws IOException {
        return connection.writeRead(new byte[] {(byte) RES, 0, 0, 0}, 1)[0] & 0xFF;
    }

    /** @return REMS: {@code {manufacturer, device}} = {0xC2, 0x17} */
    public int[] deviceIds() throws IOException {
        byte[] r = connection.writeRead(new byte[] {(byte) REMS, 0, 0, 0}, 2);
        return new int[] {r[0] & 0xFF, r[1] & 0xFF};
    }

    /** RDSFDP (5Ah): {@code length} bytes of the SFDP table from {@code address}. */
    public byte[] readSfdp(int address, int length) throws IOException {
        return readRaw(RDSFDP, address, true, length);
    }

    // ---- secured OTP (4 Kbit) --------------------------------------------

    /** Read {@code length} bytes at OTP offset {@code offset} (0..511). */
    public byte[] readOtp(int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > 512) throw outOfRange("OTP range");
        sendCommand(ENSO);
        try {
            return readRaw(READ, offset, false, length);
        } finally {
            sendCommand(EXSO);
        }
    }

    /** Program OTP bytes at {@code offset} (16..511 only: 0..15 are the ESN area); one-time programmable. */
    public void programOtp(int offset, byte[] data) throws IOException {
        if (offset < 16 || data.length == 0 || offset + data.length > 512) throw outOfRange("OTP range (0..15 is the ESN area)");
        sendCommand(ENSO);
        try {
            int pos = 0;
            while (pos < data.length) {
                int n = Math.min(PAGE_SIZE - (offset & (PAGE_SIZE - 1)), data.length - pos);
                program(offset, data, pos, n);
                offset += n;
                pos += n;
            }
        } finally {
            sendCommand(EXSO);
        }
    }

    /** Lock the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE. */
    public void lockOtp() throws IOException {
        wren();
        sendCommand(WRSCUR);
        waitReady(T_WRSR_MS);
    }

    /** @return OTP 000h-00Fh (the factory ESN when {@code readSecurity() & 1}; blank 0xFF otherwise) */
    public byte[] serialNumber() throws IOException {
        return readOtp(0, 16);
    }

    // ---- BlockDevice -----------------------------------------------------

    @Override public int blockSize() { return BLOCK_SIZE; }
    @Override public long blockCount() { return SIZE / BLOCK_SIZE; }
    @Override public int eraseSize() { return SECTOR_SIZE; }
    @Override public boolean needsErase() { return true; }

    /** Read {@code count} blocks of 512 bytes. */
    @Override
    public byte[] read(long block, int count) throws IOException {
        if (block < 0 || count < 0 || block + count > blockCount()) throw outOfRange("read beyond end");
        return readRaw(READ, (int) block * BLOCK_SIZE, false, count * BLOCK_SIZE);
    }

    /**
     * Program {@code data.length / 512} blocks. With auto-erase off a block that needs a 0-&gt;1
     * transition throws {@code NOT_ERASED} (nothing is erased).
     */
    @Override
    public void write(long block, byte[] data) throws IOException {
        long count = data.length / BLOCK_SIZE;
        if (count == 0 || data.length % BLOCK_SIZE != 0 || block < 0 || block + count > blockCount()) throw outOfRange("write range");
        int address = (int) block * BLOCK_SIZE;
        if (autoErase) {
            writeBytes(address, data);
            return;
        }
        int pos = 0;
        while (pos < data.length) {   // program page by page, only where no 0 -> 1 transition is needed
            int n = Math.min(PAGE_SIZE - (address & (PAGE_SIZE - 1)), data.length - pos);
            byte[] old = readRaw(READ, address, false, n);
            for (int i = 0; i < n; i++) {
                if ((old[i] & data[pos + i] & 0xFF) != (data[pos + i] & 0xFF)) {
                    throw new BlockDeviceException(Kind.NOT_ERASED, "block needs an erase before it can be programmed");
                }
            }
            if (!Arrays.equals(old, 0, n, data, pos, pos + n)) program(address, data, pos, n);
            address += n;
            pos += n;
        }
    }

    /** Erase {@code count} blocks; the range must be 4 KiB-aligned (multiples of 8 blocks). */
    @Override
    public void erase(long block, int count) throws IOException {
        if (block % 8 != 0 || count % 8 != 0 || block < 0 || count < 0 || block + count > blockCount()) {
            throw outOfRange("erase range must be 4 KiB aligned");
        }
        int address = (int) block * BLOCK_SIZE;
        int left = count * BLOCK_SIZE;
        while (left > 0) {
            int step;
            if ((address & 0xFFFF) == 0 && left >= 65536) {
                eraseBlock64k(address);
                step = 65536;
            } else if ((address & 0x7FFF) == 0 && left >= 32768) {
                eraseBlock32k(address);
                step = 32768;
            } else {
                eraseSector(address);
                step = SECTOR_SIZE;
            }
            address += step;
            left -= step;
        }
    }

    /** Wait until the chip is idle. */
    @Override
    public void sync() throws IOException {
        waitReady(T_CE_MS);
    }

    /** @return geometry and capabilities; {@code readOnly} reflects BP3:0 &gt;= 8 */
    @Override
    public BlockInfo info() {
        boolean readOnly;
        try {
            readOnly = protection() >= 8;
        } catch (IOException e) {
            readOnly = false;
        }
        return new BlockInfo(BLOCK_SIZE, blockCount(), SECTOR_SIZE, true, readOnly, false, "MX25L12845E");
    }
}
