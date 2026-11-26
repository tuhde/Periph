package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * SPL06-007 — barometric pressure and temperature sensor (Goertek), minimal driver.
 *
 * <p>One call returns compensated temperature and pressure. The chip stays in standby between
 * readings; each {@link #read()} runs one temperature and one pressure measurement in command
 * mode. The constructor does no bus access; {@link #init()} verifies ID ({@code 0x10}), waits for
 * SENSOR_RDY / COEF_RDY, reads the 18 coefficient bytes and writes TMP_CFG = 0x80 (external
 * temperature sensor, 1×), PRS_CFG = 0x04 (16×, standard precision) and CFG_REG = 0x04 (P_SHIFT).
 * I²C address {@code 0x77} (SDO high or open) or {@code 0x76} (SDO low).
 */
public class Spl06Minimal {

    protected static final int REG_PRS_B2   = 0x00;
    protected static final int REG_TMP_B2   = 0x03;
    protected static final int REG_PRS_CFG  = 0x06;
    protected static final int REG_TMP_CFG  = 0x07;
    protected static final int REG_MEAS_CFG = 0x08;
    protected static final int REG_CFG_REG  = 0x09;
    protected static final int REG_INT_STS  = 0x0A;
    protected static final int REG_FIFO_STS = 0x0B;
    protected static final int REG_RESET    = 0x0C;
    protected static final int REG_ID       = 0x0D;
    protected static final int REG_COEF     = 0x10;

    protected static final int CHIP_ID         = 0x10;
    protected static final int MEAS_COEF_RDY   = 0x80;
    protected static final int MEAS_SENSOR_RDY = 0x40;
    protected static final int MEAS_TMP_RDY    = 0x20;
    protected static final int MEAS_PRS_RDY    = 0x10;
    protected static final int CMD_IDLE        = 0x00;
    protected static final int CMD_PRESSURE    = 0x01;
    protected static final int CMD_TEMPERATURE = 0x02;
    protected static final int CFG_T_SHIFT     = 0x08;
    protected static final int CFG_P_SHIFT     = 0x04;
    protected static final int TMP_EXT         = 0x80;

    /** Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4). */
    protected static final double[] SCALE = {524288, 1572864, 3670016, 7864320, 253952, 516096, 1040384, 2088960};
    /** Typical measurement time in ms indexed by log2(oversampling), rounded up. */
    protected static final int[] MEAS_MS = {4, 6, 9, 15, 28, 54, 105, 207};

    /**
     * One compensated reading.
     *
     * @param temperatureC temperature in °C
     * @param pressurePa   pressure in Pa
     */
    public record Measurement(double temperatureC, double pressurePa) {}

    /** Decoded calibration coefficients (two's complement, sign-extended). */
    public record Coefficients(int c0, int c1, int c00, int c10, int c01, int c11, int c20, int c21, int c30) {}

    protected final RegisterConnection connection;
    protected Coefficients coef = new Coefficients(0, 0, 0, 0, 0, 0, 0, 0, 0);
    protected int pOs = 4;
    protected int tOs = 0;
    protected int cfg = CFG_P_SHIFT;
    protected int lastTraw = 0;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x77 or 0x76
     */
    public Spl06Minimal(RegisterConnection connection) {
        this.connection = connection;
    }

    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static int signed(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }

    protected static int raw24(byte[] b, int o) {
        return signed(((b[o] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] & 0xFF), 24);
    }

    /**
     * Verify the chip, read the calibration coefficients and apply the defaults.
     *
     * @throws IOException on I²C error, a wrong ID or a chip that does not become ready
     */
    public void init() throws IOException {
        int id = readReg(REG_ID);
        if (id != CHIP_ID) throw new IOException(String.format("Unexpected chip ID 0x%02X, expected 0x10", id));
        waitReady();
        readCoefficients();
        applyDefaults();
    }

    protected void waitReady() throws IOException {
        int mask = MEAS_COEF_RDY | MEAS_SENSOR_RDY;
        for (int i = 0; i < 40; i++) {
            try {
                if ((readReg(REG_MEAS_CFG) & mask) == mask) return;
            } catch (IOException e) {
                // the chip may not acknowledge right after a reset
            }
            sleepMs(5);
        }
        throw new IOException("SPL06 not ready");
    }

    protected void readCoefficients() throws IOException {
        byte[] r = connection.read(REG_COEF, 18);
        int[] b = new int[18];
        for (int i = 0; i < 18; i++) b[i] = r[i] & 0xFF;
        coef = new Coefficients(
                signed((b[0] << 4) | (b[1] >> 4), 12),
                signed(((b[1] & 0x0F) << 8) | b[2], 12),
                signed((b[3] << 12) | (b[4] << 4) | (b[5] >> 4), 20),
                signed(((b[5] & 0x0F) << 16) | (b[6] << 8) | b[7], 20),
                signed((b[8] << 8) | b[9], 16),
                signed((b[10] << 8) | b[11], 16),
                signed((b[12] << 8) | b[13], 16),
                signed((b[14] << 8) | b[15], 16),
                signed((b[16] << 8) | b[17], 16));
    }

    protected void applyDefaults() throws IOException {
        pOs = 4;
        tOs = 0;
        cfg = CFG_P_SHIFT;
        writeReg(REG_TMP_CFG, TMP_EXT);
        writeReg(REG_PRS_CFG, 0x04);
        writeReg(REG_CFG_REG, cfg);
    }

    protected int measure(int command, int flag, int os) throws IOException {
        writeReg(REG_MEAS_CFG, command);
        sleepMs(MEAS_MS[os]);
        long deadline = System.currentTimeMillis() + MEAS_MS[os] + 50;
        while ((readReg(REG_MEAS_CFG) & flag) == 0) {
            if (System.currentTimeMillis() > deadline) throw new IOException("SPL06 measurement timeout");
            sleepMs(2);
        }
        return raw24(connection.read(command == CMD_PRESSURE ? REG_PRS_B2 : REG_TMP_B2, 3), 0);
    }

    protected int[] measureRaw() throws IOException {
        int traw = measure(CMD_TEMPERATURE, MEAS_TMP_RDY, tOs);
        int praw = measure(CMD_PRESSURE, MEAS_PRS_RDY, pOs);
        lastTraw = traw;
        return new int[]{praw, traw};
    }

    protected static double compT(Coefficients c, double t) {
        return c.c0() * 0.5 + c.c1() * t;
    }

    protected static double compP(Coefficients c, double p, double t) {
        return c.c00() + p * (c.c10() + p * (c.c20() + p * c.c30())) + t * c.c01() + t * p * (c.c11() + p * c.c21());
    }

    protected double temperature(int traw) {
        return compT(coef, traw / SCALE[tOs]);
    }

    protected double pressure(int praw, int traw) {
        return compP(coef, praw / SCALE[pOs], traw / SCALE[tOs]);
    }

    /**
     * Measure temperature, then pressure (command mode), and compensate both.
     *
     * @return temperature in °C and pressure in Pa
     * @throws IOException on I²C error or a measurement timeout
     */
    public Measurement read() throws IOException {
        int[] raw = measureRaw();
        return new Measurement(temperature(raw[1]), pressure(raw[0], raw[1]));
    }
}
