package it.uhde.periph.chips.light;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * Si1132 — UV index and ambient light sensor (Silicon Labs), minimal driver.
 *
 * <p>The chip is a small sequencer: configuration lives partly in registers and partly in a
 * parameter RAM written with {@code PARAM_WR} plus a {@code PARAM_SET} command. The constructor
 * waits 25 ms, verifies {@code PART_ID = 0x32}, writes {@code HW_KEY}, the default UV coefficients
 * and enables the UV, infrared and visible channels in forced mode (gain 0, normal range).
 * {@link #read()} runs a forced measurement, waits for {@code ALS_INT} (timeout 500 ms) and returns
 * the UV index ({@code AUX_DATA / 100}) plus the raw 16-bit visible and infrared counts. I²C
 * address {@code 0x60} (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Forced mode, UV + infrared + visible channels, gain 0, normal signal range</li>
 *   <li>UV coefficients at the datasheet defaults ({@code 7B 6B 01 00})</li>
 *   <li>{@code ALS_IE} set so completed measurements are flagged; INT pin released</li>
 * </ul>
 */
public class Si1132Minimal {

    protected static final int REG_PART_ID    = 0x00;
    protected static final int REG_SEQ_ID     = 0x02;
    protected static final int REG_INT_CFG    = 0x03;
    protected static final int REG_IRQ_ENABLE = 0x04;
    protected static final int REG_HW_KEY     = 0x07;
    protected static final int REG_MEAS_RATE0 = 0x08;
    protected static final int REG_MEAS_RATE1 = 0x09;
    protected static final int REG_MEAS_RATE_SWAPPED = 0x0A;
    protected static final int REG_UCOEF      = 0x13;
    protected static final int REG_PARAM_WR   = 0x17;
    protected static final int REG_COMMAND    = 0x18;
    protected static final int REG_RESPONSE   = 0x20;
    protected static final int REG_IRQ_STATUS = 0x21;
    protected static final int REG_DATA       = 0x22;

    protected static final int PART_ID_VALUE  = 0x32;
    protected static final int HW_KEY_VALUE   = 0x17;

    protected static final int CMD_PARAM_SET  = 0xA0;
    protected static final int CMD_NOP        = 0x00;
    protected static final int CMD_RESET      = 0x01;
    protected static final int CMD_BUSADDR    = 0x02;
    protected static final int CMD_ALS_FORCE  = 0x06;
    protected static final int CMD_ALS_PAUSE  = 0x0A;
    protected static final int CMD_ALS_AUTO   = 0x0E;
    protected static final int CMD_GET_CAL    = 0x12;

    protected static final int PARAM_I2C_ADDR     = 0x00;
    protected static final int PARAM_CHLIST       = 0x01;
    protected static final int PARAM_ALS_ENCODING = 0x06;
    protected static final int PARAM_AUX_ADCMUX   = 0x0F;
    protected static final int PARAM_VIS_COUNTER  = 0x10;
    protected static final int PARAM_VIS_GAIN     = 0x11;
    protected static final int PARAM_VIS_MISC     = 0x12;
    protected static final int PARAM_IR_COUNTER   = 0x1D;
    protected static final int PARAM_IR_GAIN      = 0x1E;
    protected static final int PARAM_IR_MISC      = 0x1F;

    protected static final int CHLIST_ALL     = 0xB0;
    protected static final int CHLIST_AUX     = 0x40;
    protected static final int IRQ_ALS_INT    = 0x01;
    protected static final int IRQ_CLEAR      = 0x21;
    protected static final int RESP_ERROR     = 0x80;
    protected static final int TIMEOUT_MS     = 500;

    protected final RegisterConnection connection;
    protected int chlist = CHLIST_ALL;
    protected int seqId;

    /**
     * Construct the driver and run the initialization.
     *
     * @param connection I²C register connection bound to 0x60
     * @throws IOException on bus error, a {@code PART_ID} mismatch or a command error
     */
    public Si1132Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        sleepMs(25);
        if (readReg(REG_PART_ID) != PART_ID_VALUE) {
            throw new IOException("Si1132 not found (PART_ID mismatch)");
        }
        seqId = readReg(REG_SEQ_ID);
        writeReg(REG_HW_KEY, HW_KEY_VALUE);
        connection.write(REG_UCOEF, new byte[]{0x7B, 0x6B, 0x01, 0x00});
        // ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
        writeReg(REG_IRQ_ENABLE, IRQ_ALS_INT);
        chlist = CHLIST_ALL;
        paramSet(PARAM_CHLIST, chlist);
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void waitResponse(int before) throws IOException {
        for (int i = 0; i < 20; i++) {
            int resp = readReg(REG_RESPONSE);
            if ((resp & RESP_ERROR) != 0) {
                writeReg(REG_COMMAND, CMD_NOP);
                throw new IOException(String.format("Si1132 command error 0x%02X", resp));
            }
            if ((resp & 0x0F) != before) return;
            sleepMs(1);
        }
        throw new IOException("Si1132 command timeout");
    }

    protected void command(int cmd) throws IOException {
        int before = readReg(REG_RESPONSE) & 0x0F;
        writeReg(REG_COMMAND, cmd);
        waitResponse(before);
    }

    protected void paramSet(int offset, int value) throws IOException {
        int before = readReg(REG_RESPONSE) & 0x0F;
        writeReg(REG_PARAM_WR, value);
        writeReg(REG_COMMAND, CMD_PARAM_SET | (offset & 0x1F));
        waitResponse(before);
    }

    protected void startMeasurement() throws IOException {
        writeReg(REG_IRQ_STATUS, IRQ_CLEAR);
        command(CMD_ALS_FORCE);
    }

    protected void waitReady() throws IOException {
        for (int i = 0; i < TIMEOUT_MS; i++) {
            if ((readReg(REG_IRQ_STATUS) & IRQ_ALS_INT) != 0) {
                writeReg(REG_IRQ_STATUS, IRQ_CLEAR);
                return;
            }
            sleepMs(1);
        }
        throw new IOException("Si1132 measurement timeout");
    }

    /** Read the data block {@code 0x22}–{@code 0x2D}: {visible, infrared, aux}. */
    protected int[] readData() throws IOException {
        byte[] d = connection.read(REG_DATA, 12);
        return new int[]{
            (d[0] & 0xFF) | ((d[1] & 0xFF) << 8),
            (d[2] & 0xFF) | ((d[3] & 0xFF) << 8),
            (d[10] & 0xFF) | ((d[11] & 0xFF) << 8)
        };
    }

    protected int[] measure() throws IOException {
        startMeasurement();
        waitReady();
        return readData();
    }

    /**
     * Run one forced measurement of the enabled channels.
     *
     * @return {UV index ({@code AUX_DATA / 100}), raw visible count, raw infrared count}
     * @throws IOException on bus error or when the measurement does not finish within 500 ms
     */
    public double[] read() throws IOException {
        int[] d = measure();
        return new double[]{d[2] / 100.0, d[0], d[1]};
    }
}
