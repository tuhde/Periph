package it.uhde.periph.chips.rfid;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MFRC522 — 13.56 MHz contactless reader/writer (NXP). Minimal driver.
 *
 * <p>Detects an ISO/IEC 14443 Type A card in the field and reads its UID.
 * No configuration beyond the transport and bus type is required.
 *
 * <p>Supports three host transports — I²C, SPI, and UART — all of which
 * expose the same 64-register internal bank; the address-byte framing
 * differs per transport. The driver selects the correct framing from the
 * {@code busType} parameter.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)</li>
 *   <li>Force100ASK modulation</li>
 *   <li>ISO/IEC 14443-3 CRC_A preset (0x6363)</li>
 *   <li>Antenna enabled</li>
 *   <li>106 kBd, 33 dB RX gain (reset default)</li>
 * </ul>
 *
 * <p>Default I²C address: 0x28 (EA=LOW, all ADR pins LOW); 0x28–0x2F for
 * EA=LOW with the lower 3 address bits set by pins ADR_0–ADR_2.
 */
public class Mfrc522Minimal {

    public static final int BUS_I2C  = 0;
    public static final int BUS_SPI  = 1;
    public static final int BUS_UART = 2;

    // Command set
    protected static final int CMD_IDLE            = 0x00;
    protected static final int CMD_RANDOM_ID       = 0x02;
    protected static final int CMD_CALC_CRC        = 0x03;
    protected static final int CMD_TRANSCEIVE      = 0x0C;
    protected static final int CMD_MFAUTHENT       = 0x0E;
    protected static final int CMD_SOFT_RESET      = 0x0F;

    // Registers
    protected static final int REG_COMMAND        = 0x01;
    protected static final int REG_COM_IRQ        = 0x04;
    protected static final int REG_DIV_IRQ        = 0x05;
    protected static final int REG_ERROR          = 0x06;
    protected static final int REG_STATUS_2       = 0x08;
    protected static final int REG_FIFO_DATA      = 0x09;
    protected static final int REG_FIFO_LEVEL     = 0x0A;
    protected static final int REG_BIT_FRAMING    = 0x0D;
    protected static final int REG_TX_MODE        = 0x12;
    protected static final int REG_RX_MODE        = 0x13;
    protected static final int REG_TX_CONTROL     = 0x14;
    protected static final int REG_TX_ASK         = 0x15;
    protected static final int REG_MODE           = 0x11;
    protected static final int REG_CRC_RESULT_H   = 0x21;
    protected static final int REG_CRC_RESULT_L   = 0x22;
    protected static final int REG_RF_CFG         = 0x26;
    protected static final int REG_T_MODE         = 0x2A;
    protected static final int REG_T_PRESCALER    = 0x2B;
    protected static final int REG_T_RELOAD_H     = 0x2C;
    protected static final int REG_T_RELOAD_L     = 0x2D;
    protected static final int REG_AUTO_TEST      = 0x36;
    protected static final int REG_VERSION        = 0x37;

    // IRQ bits
    protected static final int IRQ_RX    = 0x30;
    protected static final int IRQ_IDLE  = 0x10;
    protected static final int IRQ_ALL   = 0x7F;

    // FIFO
    protected static final int FIFO_FLUSH = 0x80;
    protected static final int STATUS_2_CRYPTO1ON = 0x08;

    // PICC commands
    protected static final int PICC_REQA  = 0x26;
    protected static final int PICC_WUPA  = 0x52;
    protected static final int PICC_HLTA  = 0x50;
    protected static final int PICC_CT    = 0x88;
    protected static final int PICC_SEL_BIT = 0x70;
    protected static final int PICC_SAK_NOT_COMPLETE = 0x04;

    protected final Transport transport;
    protected final int busType;

    /**
     * Construct the MFRC522 driver.
     *
     * @param transport I²C/SPI/UART transport bound to the device.
     * @param busType bus type — one of {@link #BUS_I2C}, {@link #BUS_SPI} (default),
     *                {@link #BUS_UART}.
     */
    public Mfrc522Minimal(Transport transport, int busType) {
        this(transport, busType, true);
    }

    public Mfrc522Minimal(Transport transport) {
        this(transport, BUS_SPI, true);
    }

    protected Mfrc522Minimal(Transport transport, int busType, boolean runInit) {
        this.transport = transport;
        this.busType = busType;
        if (runInit) {
            try { initChip(); } catch (IOException e) { throw new RuntimeException(e); }
        }
    }

    protected int addrFor(int reg, boolean read) {
        if (busType == BUS_SPI) {
            return ((reg << 1) & 0x7E) | (read ? 0x80 : 0x00);
        }
        if (busType == BUS_UART) {
            return (reg & 0x3F) | (read ? 0x80 : 0x00);
        }
        return reg & 0x3F;
    }

    protected void writeReg(int reg, int value) throws IOException {
        transport.write(new byte[] { (byte) addrFor(reg, false), (byte) (value & 0xFF) });
    }

    protected int readReg(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[] { (byte) addrFor(reg, true) }, 1);
        return b[0] & 0xFF;
    }

    protected void setBits(int reg, int mask) throws IOException {
        writeReg(reg, readReg(reg) | mask);
    }

    protected void clearBits(int reg, int mask) throws IOException {
        writeReg(reg, readReg(reg) & ~mask);
    }

    protected void initChip() throws IOException {
        writeReg(REG_COMMAND, CMD_SOFT_RESET);
        for (int i = 0; i < 50; i++) {
            if ((readReg(REG_COMMAND) & 0x10) == 0) break;
            sleep(1);
        }
        sleep(50);
        writeReg(REG_T_MODE,      0x80);
        writeReg(REG_T_PRESCALER, 0xA9);
        writeReg(REG_T_RELOAD_H,  0x03);
        writeReg(REG_T_RELOAD_L,  0xE8);
        writeReg(REG_TX_ASK, 0x40);
        writeReg(REG_MODE, 0x3D);
        setBits(REG_TX_CONTROL, 0x03);
    }

    protected void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    protected int[] readFifo(int n) throws IOException {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = readReg(REG_FIFO_DATA);
        return out;
    }

    protected void writeFifo(int[] data) throws IOException {
        for (int b : data) writeReg(REG_FIFO_DATA, b);
    }

    protected void flushFifo() throws IOException {
        writeReg(REG_FIFO_LEVEL, FIFO_FLUSH);
    }

    protected boolean cardCommand(int command, int waitIrq, int[] sendData) throws IOException {
        writeReg(REG_COMMAND, CMD_IDLE);
        writeReg(REG_COM_IRQ, 0x7F);
        flushFifo();
        if (sendData != null && sendData.length > 0) {
            writeFifo(sendData);
        }
        writeReg(REG_COMMAND, command);
        if (command == CMD_TRANSCEIVE) setBits(REG_BIT_FRAMING, 0x80);
        for (int i = 0; i < 200; i++) {
            int n = readReg(REG_COM_IRQ);
            if ((n & waitIrq) != 0) return true;
            if ((n & 0x01) != 0) return false;
        }
        return false;
    }

    protected int[] transceive(int[] send) throws IOException {
        if (!cardCommand(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, send)) return null;
        int err = readReg(REG_ERROR);
        if ((err & 0x13) != 0) return null;
        int fifoLevel = readReg(REG_FIFO_LEVEL);
        if (fifoLevel == 0) return null;
        return readFifo(fifoLevel);
    }

    protected int[] calcCrc(int[] data) throws IOException {
        writeReg(REG_COMMAND, CMD_IDLE);
        writeReg(REG_DIV_IRQ, 0x04);
        flushFifo();
        writeFifo(data);
        writeReg(REG_COMMAND, CMD_CALC_CRC);
        for (int i = 0; i < 100; i++) {
            if ((readReg(REG_DIV_IRQ) & 0x04) != 0) break;
            sleep(1);
        }
        writeReg(REG_COMMAND, CMD_IDLE);
        return new int[] { readReg(REG_CRC_RESULT_H), readReg(REG_CRC_RESULT_L) };
    }

    protected int[] anticollision(int cmd) throws IOException {
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        writeReg(REG_BIT_FRAMING, 0x00);
        int[] send = new int[7];
        send[0] = cmd;
        send[1] = 0x20;
        sleep(1);
        int[] back = transceive(send);
        if (back == null || back.length != 5) return null;
        int bcc = 0;
        for (int i = 0; i < 4; i++) bcc ^= back[i];
        if (bcc != back[4]) return null;
        return new int[] { back[0], back[1], back[2], back[3] };
    }

    protected Integer select(int cmd, int[] uidPart) throws IOException {
        int[] buf = new int[9];
        buf[0] = cmd;
        buf[1] = PICC_SEL_BIT;
        System.arraycopy(uidPart, 0, buf, 2, 4);
        int bcc = 0;
        for (int i = 0; i < 4; i++) bcc ^= uidPart[i];
        buf[6] = bcc;
        int[] crc = calcCrc(new int[] { buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6] });
        buf[7] = crc[0];
        buf[8] = crc[1];
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        sleep(1);
        int[] back = transceive(buf);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        if (back == null || back.length < 1) return null;
        return back[0];
    }

    protected int[] selectCard() throws IOException {
        int[] uid = new int[10];
        int len = 0;
        int[][] cascade = { { 0x93, 0x93 }, { 0x95, 0x95 }, { 0x97, 0x97 } };
        for (int[] level : cascade) {
            int[] part = anticollision(level[0]);
            if (part == null) return null;
            Integer sak = select(level[1], part);
            if (sak == null) return null;
            if ((sak & PICC_SAK_NOT_COMPLETE) == 0) {
                int[] out = new int[Math.max(4, len + 4)];
                System.arraycopy(uid, 0, out, 0, len);
                if (part[0] == PICC_CT) {
                    System.arraycopy(part, 1, out, len, 3);
                    return java.util.Arrays.copyOf(out, len + 3);
                } else {
                    System.arraycopy(part, 0, out, len, 4);
                    return java.util.Arrays.copyOf(out, len + 4);
                }
            } else {
                int[] out = new int[len + 3];
                System.arraycopy(uid, 0, out, 0, len);
                System.arraycopy(part, 1, out, len, 3);
                uid = out;
                len += 3;
            }
        }
        return null;
    }

    protected void haltCard() throws IOException {
        int[] buf = new int[4];
        buf[0] = PICC_HLTA;
        buf[1] = 0x00;
        writeReg(REG_TX_MODE, 0x80);
        writeReg(REG_RX_MODE, 0x80);
        int[] crc = calcCrc(new int[] { buf[0], buf[1] });
        buf[2] = crc[0];
        buf[3] = crc[1];
        sleep(1);
        cardCommand(CMD_TRANSCEIVE, IRQ_RX | IRQ_IDLE, buf);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
    }

    /**
     * Detect a card in the RF field.
     *
     * <p>Sends a REQA short frame. Returns true if a card answered with
     * a valid 2-byte ATQA.
     *
     * @return true if a card is in the field.
     * @throws IOException on transport error.
     */
    public boolean isCardPresent() throws IOException {
        writeReg(REG_BIT_FRAMING, 0x07);
        writeReg(REG_TX_MODE, 0x00);
        writeReg(REG_RX_MODE, 0x00);
        int[] back = transceive(new int[] { PICC_REQA });
        return back != null && back.length == 2;
    }

    /**
     * Detect a card, run anticollision/Select (all cascade levels), and HLTA.
     *
     * <p>Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
     * is immediately halted, so the next call re-detects it from scratch.
     *
     * @return UID bytes, or {@code null} if no card answered.
     * @throws IOException on transport error.
     */
    public byte[] readUid() throws IOException {
        if (!isCardPresent()) return null;
        int[] uidArr = selectCard();
        haltCard();
        if (uidArr == null) return null;
        byte[] out = new byte[uidArr.length];
        for (int i = 0; i < uidArr.length; i++) out[i] = (byte) uidArr[i];
        return out;
    }
}
