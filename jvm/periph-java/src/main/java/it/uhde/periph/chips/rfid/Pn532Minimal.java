package it.uhde.periph.chips.rfid;

import it.uhde.periph.connection.Connection;

import java.io.IOException;
import java.util.Arrays;

/**
 * PN532 NFC/RFID controller (NXP) — minimal interface: detects one ISO/IEC 14443 A card at
 * 106 kbps and returns its UID.
 *
 * <p>The host exchanges frames with the PN532 firmware: a command frame
 * {@code 00 00 FF LEN LCS D4 code data DCS 00}, the chip's ACK frame {@code 00 00 FF 00 FF 00}
 * and a response frame (TFI {@code 0xD5}, {@code code + 1}). Every I²C read starts with a status
 * byte (bit 0 = frame ready); a 1-byte read does not consume the frame. The driver reads the
 * 6-byte response header, sends a NACK so the chip resends the response and then reads exactly
 * {@code 1 + LEN + 7} bytes.
 *
 * <p>{@link #init()} checks GetFirmwareVersion (IC {@code 0x32}), sets SAMConfiguration normal
 * mode with the IRQ pin enabled and MxRtyPassiveActivation = 2 (so a read without a card
 * returns). Writes are retried up to 10 times (2 ms apart) to wake the chip; the ACK is awaited
 * 15 ms and the frame resent up to 3 times. Wire I0 low and I1 high; address {@code 0x24}.
 */
public class Pn532Minimal {

    /** PN532 protocol or status error; {@link #code()} is the status code or -1. */
    public static class Pn532Exception extends IOException {
        private final int code;

        /**
         * @param message description
         * @param code    PN532 status code ({@code Status & 0x3F}), or -1 for framing / timeout errors
         */
        public Pn532Exception(String message, int code) {
            super(message);
            this.code = code;
        }

        /** @return the PN532 status code, or -1 for framing / timeout errors */
        public int code() { return code; }
    }

    /** Thrown when no response arrived within the timeout (the command was aborted). */
    public static class Pn532TimeoutException extends Pn532Exception {
        /** @param message description */
        public Pn532TimeoutException(String message) { super(message, -1); }
    }

    /** Fixed 7-bit I²C address. */
    public static final int ADDRESS = 0x24;

    protected static final byte[] ACK = {0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF, 0x00};
    protected static final byte[] NACK = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};
    protected static final int TFI_HOST = 0xD4;
    protected static final int TFI_CHIP = 0xD5;
    protected static final int TFI_ERROR = 0x7F;

    protected static final int CMD_DIAGNOSE = 0x00;
    protected static final int CMD_GET_FIRMWARE_VERSION = 0x02;
    protected static final int CMD_GET_GENERAL_STATUS = 0x04;
    protected static final int CMD_READ_REGISTER = 0x06;
    protected static final int CMD_WRITE_REGISTER = 0x08;
    protected static final int CMD_SAM_CONFIGURATION = 0x14;
    protected static final int CMD_POWER_DOWN = 0x16;
    protected static final int CMD_RF_CONFIGURATION = 0x32;
    protected static final int CMD_IN_DATA_EXCHANGE = 0x40;
    protected static final int CMD_IN_COMMUNICATE_THRU = 0x42;
    protected static final int CMD_IN_DESELECT = 0x44;
    protected static final int CMD_IN_LIST_PASSIVE_TARGET = 0x4A;
    protected static final int CMD_IN_RELEASE = 0x52;
    protected static final int CMD_IN_SELECT = 0x54;

    protected static final int IC = 0x32;
    protected static final int WRITE_RETRIES = 10;
    protected static final double ACK_TIMEOUT = 0.015;
    protected static final int ACK_ATTEMPTS = 3;

    protected final Connection connection;

    /**
     * Creates the driver; no bus access.
     *
     * @param connection plain I²C connection bound to address 0x24
     */
    public Pn532Minimal(Connection connection) {
        this.connection = connection;
    }

    /**
     * Wakes the chip, checks the firmware and configures SAM and retries.
     *
     * @throws IOException if the chip does not answer or IC is not 0x32
     */
    public void init() throws IOException {
        byte[] d = command(CMD_GET_FIRMWARE_VERSION, new byte[0], 1.0);
        if (d.length < 4 || (d[0] & 0xFF) != IC) {
            throw new Pn532Exception(String.format("Unexpected PN532 IC 0x%02X, expected 0x32",
                    d.length > 0 ? d[0] & 0xFF : 0), -1);
        }
        command(CMD_SAM_CONFIGURATION, new byte[]{0x01, 0x14, 0x01}, 1.0);
        command(CMD_RF_CONFIGURATION, new byte[]{0x05, (byte) 0xFF, 0x01, 0x02}, 1.0);
    }

    /**
     * Detects one ISO/IEC 14443 A card with a 1 s timeout.
     *
     * @return the NFCID1, or {@code null} when no card answered
     * @throws IOException on a bus or protocol error
     */
    public byte[] readUid() throws IOException {
        return readUid(1.0);
    }

    /**
     * Detects one ISO/IEC 14443 A card and returns its UID; the target is released.
     *
     * @param timeout response timeout in s; a timeout aborts the command
     * @return the NFCID1 (4, 7 or 10 bytes), or {@code null} when no card answered
     * @throws IOException on a bus or protocol error
     */
    public byte[] readUid(double timeout) throws IOException {
        byte[] d = list(1, 0x00, new byte[0], timeout);
        if (d == null || d.length < 6 || d[0] == 0) return null;
        int n = d[5] & 0xFF;
        if (6 + n > d.length) throw new Pn532Exception("PN532 bad target data", -1);
        byte[] uid = Arrays.copyOfRange(d, 6, 6 + n);
        command(CMD_IN_RELEASE, new byte[]{d[1]}, 1.0);
        return uid;
    }

    protected synchronized byte[] list(int maxTargets, int baud, byte[] initiatorData, double timeout) throws IOException {
        byte[] p = new byte[2 + initiatorData.length];
        p[0] = (byte) maxTargets;
        p[1] = (byte) baud;
        System.arraycopy(initiatorData, 0, p, 2, initiatorData.length);
        start(CMD_IN_LIST_PASSIVE_TARGET, p);
        try {
            return response(CMD_IN_LIST_PASSIVE_TARGET, timeout);
        } catch (Pn532TimeoutException e) {
            return null;
        }
    }

    protected void writeFrame(byte[] data) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                connection.write(data);
                return;
            } catch (IOException e) {
                if (attempt >= WRITE_RETRIES - 1) throw e;
                sleepMs(2);
            }
        }
    }

    protected boolean ready() throws IOException {
        byte[] s = connection.read(1);
        return s.length > 0 && (s[0] & 0x01) != 0;
    }

    protected boolean waitFrame(double timeout) throws IOException {
        int polls = (int) (timeout * 1000) + 1;
        for (int i = 0; i < polls; i++) {
            if (ready()) return true;
            sleepMs(1);
        }
        return false;
    }

    /**
     * Builds a host command frame.
     *
     * @param command command code
     * @param data    parameters
     * @return the frame
     */
    static byte[] frame(int command, byte[] data) {
        int length = data.length + 2;
        byte[] f = new byte[data.length + 9];
        f[2] = (byte) 0xFF;
        f[3] = (byte) length;
        f[4] = (byte) (0x100 - length);
        f[5] = (byte) TFI_HOST;
        f[6] = (byte) command;
        int sum = TFI_HOST + command;
        for (int i = 0; i < data.length; i++) {
            f[7 + i] = data[i];
            sum += data[i] & 0xFF;
        }
        f[7 + data.length] = (byte) (0x100 - (sum & 0xFF));
        return f;
    }

    protected void start(int command, byte[] data) throws IOException {
        byte[] f = frame(command, data);
        for (int i = 0; i < ACK_ATTEMPTS; i++) {
            writeFrame(f);
            if (waitFrame(ACK_TIMEOUT)) {
                byte[] ack = connection.read(7);
                if (ack.length == 7 && Arrays.equals(Arrays.copyOfRange(ack, 1, 7), ACK)) return;
            }
        }
        throw new Pn532Exception(String.format("PN532 did not ACK command 0x%02X", command), -1);
    }

    protected byte[] response(int command, double timeout) throws IOException {
        if (!waitFrame(timeout)) {
            writeFrame(ACK); // abort the running command
            throw new Pn532TimeoutException(String.format("PN532 response timeout (command 0x%02X)", command));
        }
        byte[] h = connection.read(6);
        if (h.length < 6 || h[1] != 0 || h[2] != 0 || (h[3] & 0xFF) != 0xFF) {
            throw new Pn532Exception("PN532 bad frame preamble", -1);
        }
        int length = h[4] & 0xFF;
        if (((length + (h[5] & 0xFF)) & 0xFF) != 0) throw new Pn532Exception("PN532 bad length checksum", -1);
        writeFrame(NACK); // have the chip resend the whole frame
        if (!waitFrame(timeout)) {
            throw new Pn532TimeoutException(String.format("PN532 response timeout (command 0x%02X)", command));
        }
        byte[] f = connection.read(1 + length + 7);
        if (f.length < 1 + length + 7) throw new Pn532Exception("PN532 short frame", -1);
        if (length == 1 && (f[6] & 0xFF) == TFI_ERROR) {
            throw new Pn532Exception(String.format("PN532 error frame (command 0x%02X)", command), -1);
        }
        if (length < 2 || (f[6] & 0xFF) != TFI_CHIP || (f[7] & 0xFF) != command + 1) {
            throw new Pn532Exception(String.format("PN532 unexpected response to command 0x%02X", command), -1);
        }
        int sum = f[6 + length] & 0xFF;
        for (int i = 6; i < 6 + length; i++) sum += f[i] & 0xFF;
        if ((sum & 0xFF) != 0) throw new Pn532Exception("PN532 bad data checksum", -1);
        return Arrays.copyOfRange(f, 8, 6 + length);
    }

    protected synchronized byte[] command(int command, byte[] data, double timeout) throws IOException {
        start(command, data);
        return response(command, timeout);
    }

    protected static void sleepMs(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
