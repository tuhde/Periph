package it.uhde.periph.chips.memory;

import it.uhde.periph.connection.SPIExtensions;
import it.uhde.periph.storage.BlockDevice;
import it.uhde.periph.storage.BlockDeviceException;
import it.uhde.periph.storage.BlockDeviceException.Kind;
import it.uhde.periph.storage.BlockInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SD card in SPI mode (full driver) — extends {@link SdSpiMinimal} with multi-block transfers
 * (CMD18/CMD25), erase, CID/CSD/OCR access and CRC checking (on by default), and implements
 * {@link BlockDevice}.
 */
public class SdSpiFull extends SdSpiMinimal implements BlockDevice {

    private String name = "";

    /**
     * Construct the driver and run the init sequence (12 MHz data clock, CRC16 on).
     *
     * @param spi SPI connection with the optional extensions
     */
    public SdSpiFull(SPIExtensions spi) throws IOException {
        this(spi, 12_000_000, true);
    }

    /**
     * Construct the driver and run the init sequence.
     *
     * @param spi     SPI connection with the optional extensions
     * @param speedHz data-phase clock after init
     * @param crc     CMD59 and CRC16 checking
     */
    public SdSpiFull(SPIExtensions spi, int speedHz, boolean crc) throws IOException {
        super(spi, speedHz, crc);
        try {
            this.name = new String(readRegister(10), 3, 5, StandardCharsets.ISO_8859_1);   // PNM = CID[103:64]
        } catch (IOException e) {
            this.name = "";
        }
    }

    @Override public int blockSize() { return BLOCK_SIZE; }
    @Override public long blockCount() { return blockCount; }
    @Override public int eraseSize() { return BLOCK_SIZE; }
    @Override public boolean needsErase() { return false; }

    private void checkRange(long block, long count) throws BlockDeviceException {
        if (block < 0 || block + count > blockCount) {
            throw new BlockDeviceException(Kind.OUT_OF_RANGE, "blocks " + block + ".." + (block + count));
        }
    }

    /** Read {@code count} consecutive blocks (CMD18 for {@code count > 1}). */
    @Override
    public byte[] read(long block, int count) throws IOException {
        if (count < 1) return new byte[0];
        checkRange(block, count);
        if (count == 1) return readBlock(block);
        byte[] out = new byte[count * BLOCK_SIZE];
        spi.select();
        try {
            if (command(18, address(block)) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD18 failed");
            try {
                for (int i = 0; i < count; i++) readData(out, i * BLOCK_SIZE, BLOCK_SIZE);
            } finally {
                command(12, 0);   // stop transmission, also after an error
                waitReady(BUSY_TIMEOUT_MS);
            }
        } finally {
            release();
        }
        return out;
    }

    /** Program {@code data.length / 512} consecutive blocks (CMD25 for more than one). */
    @Override
    public void write(long block, byte[] data) throws IOException {
        int count = data.length / BLOCK_SIZE;
        if (count < 1 || data.length % BLOCK_SIZE != 0) throw new IllegalArgumentException("data must be a multiple of 512 bytes");
        checkRange(block, count);
        if (count == 1) {
            writeBlock(block, data);
            return;
        }
        spi.select();
        try {
            appCommand(23, count);   // pre-erase hint; cards may ignore it
            if (command(25, address(block)) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD25 failed");
            IOException err = null;
            try {
                for (int i = 0; i < count; i++) writeData(0xFC, data, i * BLOCK_SIZE);
            } catch (IOException e) {
                err = e;
            }
            waitReady(BUSY_TIMEOUT_MS);
            writeData(0xFD, null, 0);
            rx();   // stuff byte after the stop token
            waitReady(BUSY_TIMEOUT_MS);
            if (err != null) throw err;
        } finally {
            release();
        }
    }

    /** Erase {@code count} blocks (CMD32/33/38); contents afterwards are card dependent. */
    @Override
    public void erase(long block, int count) throws IOException {
        if (count < 1) return;
        checkRange(block, count);
        spi.select();
        try {
            if (command(32, address(block)) != 0 || command(33, address(block + count - 1)) != 0 || command(38, 0) != 0) {
                throw new BlockDeviceException(Kind.NOT_READY, "erase command failed");
            }
            waitReady(ERASE_TIMEOUT_MS);
        } finally {
            release();
        }
    }

    /** Wait until the card has finished programming. */
    @Override
    public void sync() throws IOException {
        spi.select();
        try {
            waitReady(BUSY_TIMEOUT_MS);
        } finally {
            release();
        }
    }

    @Override
    public BlockInfo info() {
        return new BlockInfo(BLOCK_SIZE, blockCount, BLOCK_SIZE, false, false, false, name);
    }

    /** @return the 16-byte CID register (manufacturer, name, revision, serial, date) */
    public byte[] cid() throws IOException {
        return readRegister(10);
    }

    /** @return the 16-byte CSD register as read during init */
    public byte[] csd() {
        return csd.clone();
    }

    /** @return the OCR word (CMD58) */
    public int ocr() throws IOException {
        spi.select();
        try {
            if (command(58, 0) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD58 failed");
            byte[] o = new byte[4];
            spi.readInto(o);
            return ((o[0] & 0xFF) << 24) | ((o[1] & 0xFF) << 16) | ((o[2] & 0xFF) << 8) | (o[3] & 0xFF);
        } finally {
            release();
        }
    }

    /**
     * Enable/disable CMD59 and CRC16 checking.
     *
     * @param enabled true to enable
     */
    public void setCrc(boolean enabled) throws IOException {
        spi.select();
        try {
            if (command(59, enabled ? 1 : 0) != 0) throw new BlockDeviceException(Kind.NOT_READY, "CMD59 failed");
        } finally {
            release();
        }
        crcOn = enabled;
    }

    /**
     * Change the SCK frequency after init.
     *
     * @param hz clock frequency in Hz
     */
    public void setSpeed(int hz) throws IOException {
        spi.setSpeed(hz);
    }

    /** @return true if a card responds to CMD13 */
    public boolean isPresent() throws IOException {
        spi.select();
        try {
            int r = command(13, 0);
            if (r != 0xFF) rx();   // R2 second byte
            return r != 0xFF;
        } finally {
            release();
        }
    }
}
