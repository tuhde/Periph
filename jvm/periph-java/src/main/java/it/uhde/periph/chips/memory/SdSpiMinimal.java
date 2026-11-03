package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.SPIExtensions;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;

import java.io.IOException;

/**
 * SD / SDHC / SDXC / microSD card in SPI mode (minimal driver) — {@code specs/memory/sd_spi.md}.
 *
 * <p>Runs the SPI-mode init sequence (CMD0, CMD8, ACMD41, CMD58, ...) at construction and
 * offers single 512-byte block reads and writes. Default configuration: init at 400 kHz,
 * then 12 MHz; CRC16 off.
 *
 * <p>The connection must implement {@link SPIExtensions} ({@code idleClocks}, {@code setSpeed},
 * {@code select}/{@code deselect}, {@code readInto}) and clock {@code 0xFF} on MOSI while
 * reading — on Linux that is an {@code SPIConnection} built with a GPIO chip select. Cards
 * are 3.3 V only.
 */
public class SdSpiMinimal {

    /** Block size of an SD card in SPI mode. */
    public static final int BLOCK_SIZE = 512;

    /** Card generation detected during init. */
    public enum CardType { NONE, SD_V1, SD_V2, SDHC }

    protected static final long INIT_TIMEOUT_MS  = 1000;
    protected static final long TOKEN_TIMEOUT_MS = 200;
    protected static final long BUSY_TIMEOUT_MS  = 500;
    protected static final long ERASE_TIMEOUT_MS = 30_000;

    private static final int[] CRC16_T = {
        0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF};

    protected final SPIExtensions spi;
    private final int speedHz;
    private final boolean wantCrc;
    protected boolean crcOn;
    protected CardType type = CardType.NONE;
    protected boolean blockAddressing;
    protected long blockCount;
    protected byte[] csd = new byte[16];
    private final byte[] one = new byte[1];

    /**
     * Construct the driver and run the init sequence (12 MHz data clock, CRC16 off).
     *
     * @param spi SPI connection with the optional extensions
     * @throws BlockDeviceException {@code NOT_READY} if no (supported) card answers, {@code TIMEOUT} if it never leaves idle
     */
    public SdSpiMinimal(SPIExtensions spi) throws IOException {
        this(spi, 12_000_000, false);
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param spi     SPI connection with the optional extensions
     * @param speedHz data-phase clock after init (init always runs at 400 kHz)
     * @param crc     enable CMD59 and send/verify CRC16 on data blocks
     * @throws BlockDeviceException {@code NOT_READY} if no (supported) card answers, {@code TIMEOUT} if it never leaves idle
     */
    public SdSpiMinimal(SPIExtensions spi, int speedHz, boolean crc) throws IOException {
        this.spi = spi;
        this.speedHz = speedHz;
        this.wantCrc = crc;
        begin();
    }

    // ---- CRC helpers -----------------------------------------------------

    /**
     * CRC7 (polynomial x^7 + x^3 + 1) of {@code len} bytes, not shifted.
     *
     * @param data bytes
     * @param len  number of bytes
     * @return the 7-bit CRC
     */
    public static int crc7(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                crc = (crc << 1) & 0xFF;
                if (((b ^ crc) & 0x80) != 0) crc ^= 0x09;
                b = (b << 1) & 0xFF;
            }
        }
        return crc & 0x7F;
    }

    /**
     * CRC16-CCITT (polynomial 0x1021, initial value 0) of {@code len} bytes from {@code off}.
     *
     * @param data bytes
     * @param off  start offset
     * @param len  number of bytes
     * @return the 16-bit CRC
     */
    public static int crc16(byte[] data, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            crc = ((crc << 4) & 0xFFFF) ^ CRC16_T[crc >> 12];
            crc = ((crc << 4) & 0xFFFF) ^ CRC16_T[crc >> 12];
        }
        return crc;
    }

    // ---- bus primitives --------------------------------------------------

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    protected int rx() throws IOException {
        spi.readInto(one);
        return one[0] & 0xFF;
    }

    protected void waitReady(long ms) throws IOException {
        long start = nowMs();
        while (true) {
            if (rx() == 0xFF) return;
            if (nowMs() - start >= ms) throw new BlockDeviceException(Kind.TIMEOUT, "card busy");
        }
    }

    protected void release() throws IOException {
        spi.deselect();
        spi.idleClocks(8);   // let the card free MISO
    }

    /** Send a command with CS already selected; returns R1 (0xFF on no response). */
    protected int command(int cmd, int arg) throws IOException {
        if (cmd != 12 && cmd != 0) waitReady(BUSY_TIMEOUT_MS);
        byte[] frame = new byte[6];
        frame[0] = (byte) (0x40 | cmd);
        frame[1] = (byte) (arg >>> 24);
        frame[2] = (byte) (arg >>> 16);
        frame[3] = (byte) (arg >>> 8);
        frame[4] = (byte) arg;
        frame[5] = (byte) ((crc7(frame, 5) << 1) | 1);
        spi.write(frame);
        if (cmd == 12) rx();   // discard the stuff byte that follows CMD12
        for (int i = 0; i < 10; i++) {   // Ncr <= 8 bytes
            int r = rx();
            if ((r & 0x80) == 0) return r;
        }
        return 0xFF;
    }

    protected int appCommand(int cmd, int arg) throws IOException {
        int r = command(55, 0);
        if (r > 1) return r;
        return command(cmd, arg);
    }

    protected void readData(byte[] buf, int off, int len) throws IOException {
        long start = nowMs();
        int token;
        while ((token = rx()) == 0xFF) {
            if (nowMs() - start >= TOKEN_TIMEOUT_MS) throw new BlockDeviceException(Kind.TIMEOUT, "no data token");
        }
        if (token != 0xFE) throw new BlockDeviceException(Kind.NOT_READY, String.format("data error token 0x%02X", token));
        byte[] tmp = off == 0 && len == buf.length ? buf : new byte[len];
        spi.readInto(tmp);
        if (tmp != buf) System.arraycopy(tmp, 0, buf, off, len);
        byte[] crc = new byte[2];
        spi.readInto(crc);
        if (crcOn && (((crc[0] & 0xFF) << 8) | (crc[1] & 0xFF)) != crc16(buf, off, len)) {
            throw new BlockDeviceException(Kind.CRC, "data CRC16 mismatch");
        }
    }

    protected void writeData(int token, byte[] data, int off) throws IOException {
        waitReady(BUSY_TIMEOUT_MS);
        spi.write(new byte[] {(byte) token});
        if (token == 0xFD) return;   // stop-tran token has no payload
        byte[] block = new byte[BLOCK_SIZE];
        System.arraycopy(data, off, block, 0, BLOCK_SIZE);
        spi.write(block);
        if (crcOn) {
            int c = crc16(block, 0, BLOCK_SIZE);
            spi.write(new byte[] {(byte) (c >> 8), (byte) c});
        } else {
            spi.write(new byte[] {(byte) 0xFF, (byte) 0xFF});
        }
        int resp = rx() & 0x1F;
        if (resp == 0x05) return;
        if (resp == 0x0B) throw new BlockDeviceException(Kind.CRC, "write CRC rejected");
        throw new BlockDeviceException(Kind.NOT_READY, String.format("write rejected 0x%02X", resp));
    }

    protected byte[] readRegister(int cmd) throws IOException {
        spi.select();
        try {
            if (command(cmd, 0) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD" + cmd + " failed");
            byte[] out = new byte[16];
            readData(out, 0, 16);
            return out;
        } finally {
            release();
        }
    }

    protected int address(long block) {
        return (int) (blockAddressing ? block : block * BLOCK_SIZE);
    }

    // ---- init --------------------------------------------------------------

    /**
     * Run the SPI-mode init sequence (also usable to re-init a swapped card).
     *
     * @throws BlockDeviceException {@code NOT_READY} if no (supported) card answers, {@code TIMEOUT} if it never leaves idle
     */
    public final void begin() throws IOException {
        type = CardType.NONE;
        blockCount = 0;
        crcOn = false;
        spi.setSpeed(400_000);
        spi.deselect();
        spi.idleClocks(80);

        boolean idle = false;
        for (int i = 0; i < 10 && !idle; i++) {   // CMD0 with CS low enters SPI mode; a fresh card needs a few tries
            spi.select();
            try {
                idle = command(0, 0) == 0x01;
            } finally {
                release();
            }
        }
        if (!idle) throw new BlockDeviceException(Kind.NOT_READY, "no card responded to CMD0");

        spi.select();
        try {
            boolean v2 = false;
            int r = command(8, 0x1AA);
            if (r == 0x01) {
                byte[] r7 = new byte[4];
                spi.readInto(r7);
                if ((r7[2] & 0x0F) != 0x01 || (r7[3] & 0xFF) != 0xAA) {
                    throw new BlockDeviceException(Kind.NOT_READY, "CMD8 echo mismatch");
                }
                v2 = true;
            } else if ((r & 0x04) == 0) {   // anything but "illegal command" (v1 card) is a failure
                throw new BlockDeviceException(Kind.NOT_READY, "CMD8 failed");
            }

            long start = nowMs();
            while (appCommand(41, v2 ? 0x40000000 : 0) != 0x00) {
                if (nowMs() - start >= INIT_TIMEOUT_MS) throw new BlockDeviceException(Kind.TIMEOUT, "ACMD41 never completed");
            }

            type = v2 ? CardType.SD_V2 : CardType.SD_V1;
            blockAddressing = false;
            if (v2 && command(58, 0) == 0x00) {
                byte[] ocr = new byte[4];
                spi.readInto(ocr);
                if ((ocr[0] & 0x40) != 0) {
                    blockAddressing = true;
                    type = CardType.SDHC;
                }
            }
            if (!blockAddressing) command(16, BLOCK_SIZE);
            if (wantCrc && command(59, 1) == 0x00) crcOn = true;
        } catch (IOException | RuntimeException e) {
            type = CardType.NONE;
            throw e;
        } finally {
            release();
        }

        spi.setSpeed(speedHz);
        csd = readRegister(9);
        blockCount = csdBlocks(csd);
    }

    static long csdBlocks(byte[] csd) {
        if (((csd[0] & 0xFF) >> 6) == 1) {   // CSD v2: C_SIZE = CSD[69:48]
            long csize = ((long) (csd[7] & 0x3F) << 16) | ((csd[8] & 0xFFL) << 8) | (csd[9] & 0xFFL);
            return (csize + 1) * 1024;
        }
        int readBlLen = csd[5] & 0x0F;   // CSD v1
        long csize = ((long) (csd[6] & 0x03) << 10) | ((csd[7] & 0xFFL) << 2) | ((csd[8] & 0xFF) >> 6);
        int mult = ((csd[9] & 0x03) << 1) | ((csd[10] & 0xFF) >> 7);
        return ((csize + 1) << (mult + 2) << readBlLen) / BLOCK_SIZE;
    }

    // ---- minimal API -------------------------------------------------------

    /** @return capacity in 512-byte blocks (from the CSD) */
    public long blockCount() {
        return blockCount;
    }

    /** @return alias of {@link #blockCount()} (FatFs terminology) */
    public long sectorCount() {
        return blockCount;
    }

    /** @return the card generation */
    public CardType cardType() {
        return type;
    }

    /**
     * Read one 512-byte block (CMD17).
     *
     * @param block block index
     * @return the 512 data bytes
     * @throws BlockDeviceException {@code OUT_OF_RANGE} if {@code block} is beyond the card
     */
    public byte[] readBlock(long block) throws IOException {
        if (block < 0 || block >= blockCount) throw new BlockDeviceException(Kind.OUT_OF_RANGE, "block " + block);
        spi.select();
        try {
            if (command(17, address(block)) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD17 failed");
            byte[] out = new byte[BLOCK_SIZE];
            readData(out, 0, BLOCK_SIZE);
            return out;
        } finally {
            release();
        }
    }

    /**
     * Write one 512-byte block (CMD24).
     *
     * @param block block index
     * @param data  512 bytes
     * @throws BlockDeviceException {@code OUT_OF_RANGE} if {@code block} is beyond the card
     */
    public void writeBlock(long block, byte[] data) throws IOException {
        if (data.length != BLOCK_SIZE) throw new IllegalArgumentException("data must be 512 bytes");
        if (block < 0 || block >= blockCount) throw new BlockDeviceException(Kind.OUT_OF_RANGE, "block " + block);
        spi.select();
        try {
            if (command(24, address(block)) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD24 failed");
            writeData(0xFE, data, 0);
            waitReady(BUSY_TIMEOUT_MS);
        } finally {
            release();
        }
    }
}
