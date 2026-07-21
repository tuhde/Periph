package it.uhde.periph.chips.rfid;

import java.io.IOException;

/**
 * MFRC522 — 13.56 MHz contactless reader/writer (NXP). Full driver.
 *
 * <p>Extends {@link Mfrc522Minimal} with configuration, antenna control,
 * self test, MIFARE Classic authenticated read/write/value operations,
 * and MIFARE Ultralight / NTAG page read/write.
 */
public class Mfrc522Full extends Mfrc522Minimal {

    public static final int KEY_A = 0x60;
    public static final int KEY_B = 0x61;

    public static final int RX_GAIN_18_DB = 0x00;
    public static final int RX_GAIN_23_DB = 0x10;
    public static final int RX_GAIN_33_DB = 0x40;
    public static final int RX_GAIN_38_DB = 0x50;
    public static final int RX_GAIN_43_DB = 0x60;
    public static final int RX_GAIN_48_DB = 0x70;

    /**
     * Construct the MFRC522 full driver.
     *
     * @param transport I²C/SPI/UART transport bound to the device.
     * @param busType bus type — one of {@link #BUS_I2C}, {@link #BUS_SPI} (default),
     *                {@link #BUS_UART}.
     */
    public Mfrc522Full(Transport transport, int busType) {
        super(transport, busType);
    }

    public Mfrc522Full(Transport transport) {
        super(transport, BUS_SPI);
    }

    /** Re-run SoftReset and the full initialization sequence. */
    public void reset() throws IOException { initChip(); }

    /** Enable the antenna driver (TX1 + TX2). */
    public void antennaOn() throws IOException { setBits(REG_TX_CONTROL, 0x03); }

    /** Disable the antenna driver (TX1 + TX2). */
    public void antennaOff() throws IOException { clearBits(REG_TX_CONTROL, 0x03); }

    /**
     * Set the receiver gain.
     *
     * @param dB one of 18, 23, 33, 38, 43, 48.
     */
    public void setAntennaGain(int dB) throws IOException {
        int gain;
        switch (dB) {
            case 18: gain = RX_GAIN_18_DB; break;
            case 23: gain = RX_GAIN_23_DB; break;
            case 33: gain = RX_GAIN_33_DB; break;
            case 38: gain = RX_GAIN_38_DB; break;
            case 43: gain = RX_GAIN_43_DB; break;
            case 48: gain = RX_GAIN_48_DB; break;
            default: return;
        }
        int cur = readReg(REG_RF_CFG) & 0x8F;
        writeReg(REG_RF_CFG, cur | gain);
    }

    /**
     * Read the currently configured receiver gain.
     * @return gain in dB (one of 18, 23, 33, 38, 43, 48), or 0 if unknown.
     */
    public int antennaGain() throws IOException {
        int cur = readReg(REG_RF_CFG) & 0x70;
        switch (cur) {
            case RX_GAIN_18_DB: return 18;
            case RX_GAIN_23_DB: return 23;
            case RX_GAIN_33_DB: return 33;
            case RX_GAIN_38_DB: return 38;
            case RX_GAIN_43_DB: return 43;
            case RX_GAIN_48_DB: return 48;
            default: return 0;
        }
    }

    /**
     * Read the version register and decode it.
     *
     * @return two-element array: {chipType, version}. For MFRC522, chipType=0x09.
     */
    public int[] version() throws IOException {
        int raw = readReg(REG_VERSION);
        return new int[] { (raw >> 4) & 0x0F, raw & 0x0F };
    }

    /**
     * Run the datasheet-defined digital self test.
     *
     * @return true if all 64 FIFO bytes match the version-specific reference.
     */
    public boolean selfTest() throws IOException {
        int[] refV10 = {
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        };
        int[] refV20 = {
            0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
            0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
            0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
            0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
            0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
            0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
            0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
            0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
        };
        int[] v = version();
        int[] ref = (v[1] == 1) ? refV10 : refV20;
        writeReg(REG_AUTO_TEST, 0x09);
        writeReg(REG_FIFO_LEVEL, FIFO_FLUSH);
        writeReg(REG_COMMAND, CMD_IDLE);
        for (int i = 0; i < 255; i++) {
            if (readReg(REG_FIFO_LEVEL) >= 64) break;
            writeReg(REG_COMMAND, CMD_CALC_CRC);
            sleep(1);
        }
        writeReg(REG_AUTO_TEST, 0x00);
        writeReg(REG_COMMAND, CMD_SOFT_RESET);
        sleep(50);
        initChip();
        int[] got = readFifo(64);
        for (int i = 0; i < 64; i++) {
            if (got[i] != ref[i]) return false;
        }
        return true;
    }

    /** WUPA — wake a HALTed card. Same as {@link #isCardPresent} but with WUPA. */
    public boolean wakeupCard() throws IOException {
        writeReg(REG_BIT_FRAMING, 0x07);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        int[] back = transceive(new int[] { PICC_WUPA });
        return back != null && back.length == 2;
    }

    /**
     * Run anticollision / Select only — leaves the card active for further ops.
     *
     * @return UID bytes, or {@code null} if no card answered.
     */
    public byte[] selectCard() throws IOException {
        if (!wakeupCard()) return null;
        int[] uidArr = selectCard();
        if (uidArr == null) return null;
        byte[] out = new byte[uidArr.length];
        for (int i = 0; i < uidArr.length; i++) out[i] = (byte) uidArr[i];
        return out;
    }

    /** Send HLTA — put the currently selected card into HALT state. */
    public void haltCard() throws IOException { super.haltCard(); }

    /**
     * Run MIFARE Classic Crypto1 authentication.
     *
     * @param blockAddress block number to authenticate against.
     * @param keyType {@link #KEY_A} (0x60) or {@link #KEY_B} (0x61).
     * @param key 6-byte key.
     * @param uid 4-byte UID.
     * @return true on success.
     */
    public boolean authenticate(int blockAddress, int keyType, byte[] key, byte[] uid) throws IOException {
        if (key.length != 6 || uid.length != 4) return false;
        int[] buf = new int[12];
        buf[0] = keyType;
        buf[1] = blockAddress & 0xFF;
        for (int i = 0; i < 6; i++) buf[2 + i] = key[i] & 0xFF;
        for (int i = 0; i < 4; i++) buf[8 + i] = uid[i] & 0xFF;
        writeReg(REG_COM_IRQ, IRQ_ALL);
        writeReg(REG_STATUS_2, 0x00);
        flushFifo();
        writeFifo(buf);
        writeReg(REG_COMMAND, CMD_MFAUTHENT);
        for (int i = 0; i < 200; i++) {
            if ((readReg(REG_STATUS_2) & STATUS_2_CRYPTO1ON) != 0) return true;
            sleep(1);
        }
        return false;
    }

    /** Clear Status2Reg.MFCrypto1On. */
    public void stopCrypto() throws IOException { clearBits(REG_STATUS_2, STATUS_2_CRYPTO1ON); }

    /**
     * Read a 16-byte MIFARE Classic block.
     *
     * @param blockAddress block number.
     * @return 16 data bytes, or {@code null} on failure.
     */
    public byte[] readBlock(int blockAddress) throws IOException {
        int[] cmd = { 0x30, blockAddress & 0xFF };
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(cmd);
        int[] full = { cmd[0], cmd[1], crc[0], crc[1] };
        int[] back = transceive(full);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        if (back == null || back.length != 16) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) back[i];
        return out;
    }

    /**
     * Write a 16-byte MIFARE Classic block.
     *
     * @param blockAddress block number.
     * @param data 16 bytes to write.
     * @return true on success.
     */
    public boolean writeBlock(int blockAddress, byte[] data) throws IOException {
        if (data.length != 16) return false;
        // Phase 1: 0xA0 + block_address
        int[] c = { 0xA0, blockAddress & 0xFF };
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(c);
        int[] full = { c[0], c[1], crc[0], crc[1] };
        int[] back = transceive(full);
        if (back == null || back.length < 1 || (back[0] & 0x0F) != 0x0A) {
            writeReg(REG_TX_MODE, 0x00);
            writeReg(REG_RX_MODE, 0x00);
            return false;
        }
        // Phase 2: 16 data bytes
        int[] dataArr = new int[16];
        for (int i = 0; i < 16; i++) dataArr[i] = data[i] & 0xFF;
        int[] crc2 = calcCrc(dataArr);
        int[] buf = new int[18];
        System.arraycopy(dataArr, 0, buf, 0, 16);
        buf[16] = crc2[0];
        buf[17] = crc2[1];
        int[] back2 = transceive(buf);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        return back2 != null && back2.length >= 1 && (back2[0] & 0x0F) == 0x0A;
    }

    private boolean valueOp(int cmd, int blockAddress, long delta, boolean dummy) throws IOException {
        int[] c = { cmd, blockAddress & 0xFF };
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(c);
        int[] full = { c[0], c[1], crc[0], crc[1] };
        int[] back = transceive(full);
        if (back == null || back.length < 1 || (back[0] & 0x0F) != 0x0A) {
            writeReg(REG_TX_MODE, 0x00);
            writeReg(REG_RX_MODE, 0x00);
            return false;
        }
        int[] data = new int[4];
        if (dummy) {
            for (int i = 0; i < 4; i++) data[i] = 0;
        } else {
            data[0] = (int) (delta & 0xFF);
            data[1] = (int) ((delta >> 8) & 0xFF);
            data[2] = (int) ((delta >> 16) & 0xFF);
            data[3] = (int) ((delta >> 24) & 0xFF);
        }
        int[] crc2 = calcCrc(data);
        int[] buf = { data[0], data[1], data[2], data[3], crc2[0], crc2[1] };
        int[] back2 = transceive(buf);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        return back2 != null && back2.length >= 1 && (back2[0] & 0x0F) == 0x0A;
    }

    private boolean transfer(int blockAddress) throws IOException {
        int[] c = { 0xB0, blockAddress & 0xFF };
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(c);
        int[] full = { c[0], c[1], crc[0], crc[1] };
        int[] back = transceive(full);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        return back != null && back.length >= 1 && (back[0] & 0x0F) == 0x0A;
    }

    /** Increment the value block at blockAddress by delta and transfer it back. */
    public boolean incrementValue(int blockAddress, long delta) throws IOException {
        if (!valueOp(0xC1, blockAddress, delta, false)) return false;
        return transfer(blockAddress);
    }

    /** Decrement the value block at blockAddress by delta and transfer it back. */
    public boolean decrementValue(int blockAddress, long delta) throws IOException {
        if (!valueOp(0xC0, blockAddress, delta, false)) return false;
        return transfer(blockAddress);
    }

    /** Restore (re-read) the value block at blockAddress into the internal data register. */
    public boolean restoreValue(int blockAddress) throws IOException {
        if (!valueOp(0xC2, blockAddress, 0, true)) return false;
        return transfer(blockAddress);
    }

    /** Commit the internal data register to destinationBlock. */
    public boolean transferValue(int destinationBlock) throws IOException { return transfer(destinationBlock); }

    /**
     * Read 4 consecutive pages (16 bytes) starting at pageAddress.
     */
    public byte[] readUltralightPage(int pageAddress) throws IOException {
        int[] cmd = { 0x30, pageAddress & 0xFF };
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(cmd);
        int[] full = { cmd[0], cmd[1], crc[0], crc[1] };
        int[] back = transceive(full);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        if (back == null || back.length != 16) return null;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) back[i];
        return out;
    }

    /**
     * Write a 4-byte page (MIFARE Ultralight / NTAG).
     */
    public boolean writeUltralightPage(int pageAddress, byte[] data) throws IOException {
        if (data.length != 4) return false;
        int[] buf = new int[8];
        buf[0] = 0xA2;
        buf[1] = pageAddress & 0xFF;
        for (int i = 0; i < 4; i++) buf[2 + i] = data[i] & 0xFF;
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(new int[] { buf[0], buf[1], buf[2], buf[3], buf[4], buf[5] });
        buf[6] = crc[0];
        buf[7] = crc[1];
        int[] back = transceive(buf);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        return back != null && back.length >= 1 && (back[0] & 0x0F) == 0x0A;
    }

    /** Run the Generate RandomID command and return the 10-byte ID. */
    public byte[] generateRandomId() throws IOException {
        writeReg(REG_COMMAND, CMD_IDLE);
        writeReg(REG_COM_IRQ, IRQ_ALL);
        writeReg(REG_DIV_IRQ, 0x14);
        writeReg(REG_COMMAND, CMD_RANDOM_ID);
        for (int i = 0; i < 50; i++) {
            if ((readReg(REG_COM_IRQ) & 0x10) != 0) break;
            sleep(1);
        }
        writeReg(REG_COMMAND, CMD_IDLE);
        int[] got = readFifo(10);
        byte[] out = new byte[10];
        for (int i = 0; i < 10; i++) out[i] = (byte) got[i];
        return out;
    }
}
