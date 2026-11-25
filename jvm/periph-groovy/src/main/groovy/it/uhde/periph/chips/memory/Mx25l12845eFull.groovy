package it.uhde.periph.chips.memory

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.storage.BlockDevice
import it.uhde.periph.storage.BlockDeviceException
import it.uhde.periph.storage.BlockDeviceException.Kind
import it.uhde.periph.storage.BlockInfo

/**
 * Macronix MX25L12845E — full driver: raw program / erase commands, status and block protection,
 * deep power-down, SFDP and the secured OTP; implements {@link BlockDevice} (512-byte blocks,
 * 4 KiB erase units, {@code needsErase() == true}).
 */
@CompileStatic
class Mx25l12845eFull extends Mx25l12845eMinimal implements BlockDevice {

    private static final int BLOCK_SIZE = 512

    private final boolean autoErase

    /**
     * Construct the driver and run the init sequence ({@code autoErase} off).
     *
     * @param connection SPI connection
     */
    Mx25l12845eFull(Connection connection) throws IOException {
        this(connection, false, T_CE_MS)
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection SPI connection
     * @param autoErase  {@link #write} erases sectors itself when a 0-&gt;1 transition is needed; otherwise
     *                   it throws {@code NOT_ERASED}
     */
    Mx25l12845eFull(Connection connection, boolean autoErase) throws IOException {
        this(connection, autoErase, T_CE_MS)
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param connection    SPI connection
     * @param autoErase     see {@link #Mx25l12845eFull(Connection, boolean)}
     * @param initTimeoutMs see {@link Mx25l12845eMinimal#Mx25l12845eMinimal(Connection, long)}
     */
    Mx25l12845eFull(Connection connection, boolean autoErase, long initTimeoutMs) throws IOException {
        super(connection, initTimeoutMs)
        this.autoErase = autoErase
    }

    private static BlockDeviceException outOfRange(String what) {
        return new BlockDeviceException(Kind.OUT_OF_RANGE, what)
    }

    // ---- raw operations --------------------------------------------------

    /** FAST_READ (0Bh) of {@code length} bytes. */
    byte[] fastRead(int address, int length) {
        if (address < 0 || length < 0 || (long) address + length > SIZE) throw outOfRange('read beyond end')
        return readRaw(FAST_READ, address, true, length)
    }

    /** One PP of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased. */
    void pageProgram(int address, byte[] data) {
        int n = data.length
        if (n == 0 || n > PAGE_SIZE || address < 0 || (long) address + n > SIZE) throw outOfRange('bad page program range')
        if ((address & (PAGE_SIZE - 1)) + n > PAGE_SIZE) throw outOfRange('page program would wrap inside the page')
        program(address, data, 0, n)
    }

    /** Erase the 32 KiB block containing {@code address} (BE32K, 52h). */
    void eraseBlock32k(int address) {
        if (address < 0 || address >= SIZE) throw outOfRange('address beyond end')
        eraseCmd(BE32K, address, T_BE_MS)
    }

    /** Erase the 64 KiB block containing {@code address} (BE, D8h). */
    void eraseBlock64k(int address) {
        if (address < 0 || address >= SIZE) throw outOfRange('address beyond end')
        eraseCmd(BE64K, address, T_BE_MS)
    }

    /** Erase the whole chip (CE, C7h); 80 s typical, 200 s max; needs BP3:0 == 0. */
    void chipErase() {
        wren()
        sendCommand(CE)
        waitReady(T_CE_MS)
        checkFail()
    }

    // ---- status / protection ---------------------------------------------

    /** @return the status register */
    int readStatus() {
        return status()
    }

    /** WREN + WRSR; waits for tW. */
    void writeStatus(int value) {
        wren()
        connection.write([(byte) WRSR, (byte) value] as byte[])
        waitReady(T_WRSR_MS)
    }

    /** @return BP3:0 (0..15) from the status register */
    int protection() {
        return (status() & SR_BP_MASK) >> SR_BP_SHIFT
    }

    /** Set BP3:0 (non-volatile); QE and SRWD are preserved. */
    void setProtection(int bp) {
        if (bp < 0 || bp > 15) throw outOfRange('BP must be 0..15')
        writeStatus((status() & ~SR_BP_MASK) | (bp << SR_BP_SHIFT))
    }

    /** @return true while a program, erase or WRSR is running (WIP) */
    boolean isBusy() {
        return (status() & SR_WIP) != 0
    }

    /**
     * Wait until WIP = 0.
     *
     * @throws BlockDeviceException {@code TIMEOUT}
     */
    void waitUntilReady(long timeoutMs) {
        waitReady(timeoutMs)
    }

    /** @return the security register (RDSCUR) */
    int readSecurity() {
        return connection.writeRead([(byte) RDSCUR] as byte[], 1)[0] & 0xFF
    }

    /** CLSR: clear P_FAIL / E_FAIL. */
    void clearFailFlags() {
        sendCommand(CLSR)
    }

    // ---- power / identification ------------------------------------------

    /** DP: enter deep power-down (only RDP / RES are accepted afterwards). */
    void deepPowerDown() {
        sendCommand(DP)
    }

    /** RDP: leave deep power-down and wait tRES2. */
    void releaseDeepPowerDown() {
        sendCommand(RDP)
        sleepMs(1L)
    }

    /** @return the RES electronic ID (17h) */
    int electronicId() {
        return connection.writeRead([(byte) RES, (byte) 0, (byte) 0, (byte) 0] as byte[], 1)[0] & 0xFF
    }

    /** @return REMS: {@code [manufacturer, device]} = {@code [0xC2, 0x17]} */
    int[] deviceIds() {
        byte[] r = connection.writeRead([(byte) REMS, (byte) 0, (byte) 0, (byte) 0] as byte[], 2)
        return [r[0] & 0xFF, r[1] & 0xFF] as int[]
    }

    /** RDSFDP (5Ah): {@code length} bytes of the SFDP table from {@code address}. */
    byte[] readSfdp(int address, int length) {
        return readRaw(RDSFDP, address, true, length)
    }

    // ---- secured OTP (4 Kbit) --------------------------------------------

    /** Read {@code length} bytes at OTP offset {@code offset} (0..511). */
    byte[] readOtp(int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > 512) throw outOfRange('OTP range')
        sendCommand(ENSO)
        try {
            return readRaw(READ, offset, false, length)
        } finally {
            sendCommand(EXSO)
        }
    }

    /** Program OTP bytes at {@code offset} (16..511 only: 0..15 are the ESN area); one-time programmable. */
    void programOtp(int offset, byte[] data) {
        if (offset < 16 || data.length == 0 || offset + data.length > 512) throw outOfRange('OTP range (0..15 is the ESN area)')
        sendCommand(ENSO)
        try {
            int off = offset
            int pos = 0
            while (pos < data.length) {
                int n = Math.min(PAGE_SIZE - (off & (PAGE_SIZE - 1)), data.length - pos)
                program(off, data, pos, n)
                off += n
                pos += n
            }
        } finally {
            sendCommand(EXSO)
        }
    }

    /** Lock the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE. */
    void lockOtp() {
        wren()
        sendCommand(WRSCUR)
        waitReady(T_WRSR_MS)
    }

    /** @return OTP 000h-00Fh (the factory ESN when {@code readSecurity() & 1}; blank 0xFF otherwise) */
    byte[] serialNumber() {
        return readOtp(0, 16)
    }

    // ---- BlockDevice -----------------------------------------------------

    int blockSize() { BLOCK_SIZE }
    long blockCount() { (long) (SIZE.intdiv(BLOCK_SIZE)) }
    int eraseSize() { SECTOR_SIZE }
    boolean needsErase() { true }

    /** Read {@code count} blocks of 512 bytes. */
    byte[] read(long block, int count) throws IOException {
        if (block < 0 || count < 0 || block + count > blockCount()) throw outOfRange('read beyond end')
        return readRaw(READ, (int) (block * BLOCK_SIZE), false, count * BLOCK_SIZE)
    }

    /**
     * Program {@code data.length / 512} blocks. With auto-erase off a block that needs a 0-&gt;1
     * transition throws {@code NOT_ERASED} (nothing is erased).
     */
    void write(long block, byte[] data) throws IOException {
        long count = data.length.intdiv(BLOCK_SIZE)
        if (count == 0L || data.length % BLOCK_SIZE != 0 || block < 0 || block + count > blockCount()) throw outOfRange('write range')
        int address = (int) (block * BLOCK_SIZE)
        if (autoErase) {
            writeBytes(address, data)
            return
        }
        int pos = 0
        while (pos < data.length) {   // program page by page, only where no 0 -> 1 transition is needed
            int n = Math.min(PAGE_SIZE - (address & (PAGE_SIZE - 1)), data.length - pos)
            byte[] old = readRaw(READ, address, false, n)
            for (int i = 0; i < n; i++) {
                int o = old[i] & 0xFF
                int d = data[pos + i] & 0xFF
                if ((o & d) != d) throw new BlockDeviceException(Kind.NOT_ERASED, 'block needs an erase before it can be programmed')
            }
            if (!Arrays.equals(old, 0, n, data, pos, pos + n)) program(address, data, pos, n)
            address += n
            pos += n
        }
    }

    /** Erase {@code count} blocks; the range must be 4 KiB-aligned (multiples of 8 blocks). */
    void erase(long block, int count) throws IOException {
        if (block % 8L != 0L || count % 8 != 0 || block < 0 || count < 0 || block + count > blockCount()) {
            throw outOfRange('erase range must be 4 KiB aligned')
        }
        int address = (int) (block * BLOCK_SIZE)
        int left = count * BLOCK_SIZE
        while (left > 0) {
            int step
            if ((address & 0xFFFF) == 0 && left >= 65536) {
                eraseBlock64k(address)
                step = 65536
            } else if ((address & 0x7FFF) == 0 && left >= 32768) {
                eraseBlock32k(address)
                step = 32768
            } else {
                eraseSector(address)
                step = SECTOR_SIZE
            }
            address += step
            left -= step
        }
    }

    /** Wait until the chip is idle. */
    void sync() throws IOException {
        waitReady(T_CE_MS)
    }

    /** @return geometry and capabilities; {@code readOnly} reflects BP3:0 &gt;= 8 */
    BlockInfo info() {
        boolean readOnly
        try {
            readOnly = protection() >= 8
        } catch (IOException e) {
            readOnly = false
        }
        return new BlockInfo(BLOCK_SIZE, blockCount(), SECTOR_SIZE, true, readOnly, false, 'MX25L12845E')
    }
}
