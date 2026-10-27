package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LSM303 accelerometer + magnetometer e-compass — minimal interface.
 *
 * <p>The LSM303DLHC combines a three-axis accelerometer (0x19) and a
 * three-axis magnetometer (0x1E) behind two independent I²C slave addresses.
 * The accelerometer has four full-scale ranges, left-justified 16-bit output,
 * three operating modes, a 32-level FIFO, a high-pass filter, two inertial
 * interrupt generators and click detection. The magnetometer has seven gain
 * settings, 12-bit output, data rates from 0.75 Hz to 220 Hz and a
 * relative-only temperature sensor.
 *
 * <p>Accelerometer multi-byte transfers OR 0x80 into the sub-address
 * (auto-increment); the magnetometer pointer always auto-increments.
 */
public class LSM303Minimal {

    /** Accelerometer registers. */
    protected static final int REG_CTRL_REG1    = 0x20;
    protected static final int REG_CTRL_REG2    = 0x21;
    protected static final int REG_CTRL_REG3    = 0x22;
    protected static final int REG_CTRL_REG4    = 0x23;
    protected static final int REG_CTRL_REG5    = 0x24;
    protected static final int REG_CTRL_REG6    = 0x25;
    protected static final int REG_REFERENCE    = 0x26;
    protected static final int REG_STATUS       = 0x27;
    protected static final int REG_OUT_X_L      = 0x28;
    protected static final int REG_OUT_X_H      = 0x29;
    protected static final int REG_OUT_Y_L      = 0x2A;
    protected static final int REG_OUT_Y_H      = 0x2B;
    protected static final int REG_OUT_Z_L      = 0x2C;
    protected static final int REG_OUT_Z_H      = 0x2D;
    protected static final int REG_FIFO_CTRL    = 0x2E;
    protected static final int REG_FIFO_SRC     = 0x2F;
    protected static final int REG_INT1_CFG     = 0x30;
    protected static final int REG_INT1_SRC     = 0x31;
    protected static final int REG_INT1_THS     = 0x32;
    protected static final int REG_INT1_DUR     = 0x33;
    protected static final int REG_INT2_CFG     = 0x34;
    protected static final int REG_INT2_SRC     = 0x35;
    protected static final int REG_INT2_THS     = 0x36;
    protected static final int REG_INT2_DUR     = 0x37;
    protected static final int REG_CLICK_CFG    = 0x38;
    protected static final int REG_CLICK_SRC    = 0x39;
    protected static final int REG_CLICK_THS    = 0x3A;
    protected static final int REG_TIME_LIMIT   = 0x3B;
    protected static final int REG_TIME_LATENCY = 0x3C;
    protected static final int REG_TIME_WINDOW  = 0x3D;

    /** Magnetometer registers. */
    protected static final int MAG_REG_CRA     = 0x00;
    protected static final int MAG_REG_CRB     = 0x01;
    protected static final int MAG_REG_MR      = 0x02;
    protected static final int MAG_REG_OUT_X_H = 0x03;
    protected static final int MAG_REG_SR      = 0x09;
    protected static final int MAG_REG_IRA     = 0x0A;
    protected static final int MAG_REG_TEMP_H  = 0x31;
    protected static final int MAG_REG_TEMP_L  = 0x32;

    protected static final int CTRL_REG4_DEFAULT = 0x88;
    protected static final int CTRL_REG1_DEFAULT = 0x57;
    protected static final int AUTO_INCREMENT = 0x80;
    protected static final int BOOT_DELAY_MS = 5;

    /** Low-power mode: 8-bit output. */
    public static final int MODE_LOW_POWER = 0;
    /** Normal mode: 10-bit output. */
    public static final int MODE_NORMAL = 1;
    /** High-resolution mode: 12-bit output. */
    public static final int MODE_HIGH_RESOLUTION = 2;

    /** ±2 g full-scale range. */
    public static final int RANGE_2G = 2;
    /** ±4 g full-scale range. */
    public static final int RANGE_4G = 4;
    /** ±8 g full-scale range. */
    public static final int RANGE_8G = 8;
    /** ±16 g full-scale range. */
    public static final int RANGE_16G = 16;

    private static final int[] RANGES = {2, 4, 8, 16};
    private static final int[] RATE_CODES = {0, 1, 10, 25, 50, 100, 200, 400, 1620, 1344, 5376};
    private static final int[] RATE_BITS = {0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0x90};

    protected static final int[] MAG_CODES = {0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0};
    protected static final int[] MAG_RANGES = {130, 190, 250, 400, 470, 560, 810};
    protected static final double[] MAG_XY = {1100, 855, 670, 450, 400, 330, 230};
    protected static final double[] MAG_Z = {980, 760, 600, 400, 355, 295, 205};
    protected static final double[] MAG_RATES = {0.75, 1.5, 3, 7.5, 15, 30, 75, 220};
    protected static final int[] MAG_RATE_BITS = {0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C};

    /** Connection bound to the accelerometer (0x19). */
    protected final RegisterConnection accelConnection;
    /** Connection bound to the magnetometer (0x1E). */
    protected final RegisterConnection magConnection;

    protected int rangeG = RANGE_2G;
    protected int mode = MODE_HIGH_RESOLUTION;
    protected int rateHz = 100;
    protected int magGainCode = 0x20;
    protected double magRateHz = 15;

    /**
     * Construct the driver and run the Initialization Sequence.
     *
     * @param accelConnection connection bound to 0x19
     * @param magConnection   connection bound to 0x1E
     * @throws IOException on bus error or a wrong magnetometer identity
     */
    public LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        this.accelConnection = accelConnection;
        this.magConnection = magConnection;
        runInitSequence();
    }

    protected void runInitSequence() throws IOException {
        sleepMs(BOOT_DELAY_MS);
        byte[] identity = magConnection.read(MAG_REG_IRA, 3);
        if (identity[0] != 0x48 || identity[1] != 0x34 || identity[2] != 0x33) {
            throw new IOException("LSM303 magnetometer identity: expected 48 34 33");
        }
        writeReg(REG_CTRL_REG2, 0x00);
        writeReg(REG_CTRL_REG3, 0x00);
        writeReg(REG_CTRL_REG5, 0x00);
        writeReg(REG_CTRL_REG6, 0x00);
        writeReg(REG_FIFO_CTRL, 0x00);
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT);
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
        magWriteReg(MAG_REG_CRA, 0x10);
        magWriteReg(MAG_REG_CRB, magGainCode);
        magWriteReg(MAG_REG_MR, 0x00);
        sleepMs(Math.max(settleMs(), (int) Math.ceil(1000.0 / magRateHz)));
    }

    protected void writeReg(int reg, int value) throws IOException {
        accelConnection.write(reg, new byte[]{(byte) value});
    }

    protected int readReg(int reg) throws IOException {
        return accelConnection.read(reg, 1)[0] & 0xFF;
    }

    protected byte[] readBurst(int reg, int length) throws IOException {
        return accelConnection.read(length > 1 ? (reg | AUTO_INCREMENT) : reg, length);
    }

    protected void magWriteReg(int reg, int value) throws IOException {
        magConnection.write(reg, new byte[]{(byte) value});
    }

    protected int magReadReg(int reg) throws IOException {
        return magConnection.read(reg, 1)[0] & 0xFF;
    }

    protected byte[] magReadBurst(int reg, int length) throws IOException {
        return magConnection.read(reg, length);
    }

    protected int settleMs() {
        if (rateHz <= 0) return 0;
        double period = 1000.0 / rateHz;
        double turnOn;
        if (mode == MODE_LOW_POWER) turnOn = 1.0;
        else if (mode == MODE_NORMAL) turnOn = 1.6;
        else turnOn = 7.0 * period;
        return (int) Math.ceil(Math.max(turnOn, period));
    }

    protected int decodeAxis(byte low, byte high) {
        int raw = (low & 0xFF) | ((high & 0xFF) << 8);
        int signed16 = (short) raw;
        int shift = mode == MODE_LOW_POWER ? 8 : (mode == MODE_NORMAL ? 6 : 4);
        return signed16 >> shift;
    }

    protected double sensitivityMg() {
        int index = 0;
        for (int i = 0; i < RANGES.length; i++) if (RANGES[i] == rangeG) index = i;
        if (mode == MODE_LOW_POWER) return new double[]{16, 32, 64, 192}[index];
        if (mode == MODE_NORMAL) return new double[]{4, 8, 16, 48}[index];
        return new double[]{1, 2, 4, 12}[index];
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return {x, y, z} in g
     * @throws IOException on bus error
     */
    public double[] readAcceleration() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_L, 6);
        double scale = sensitivityMg() / 1000.0;
        return new double[]{
                decodeAxis(raw[0], raw[1]) * scale,
                decodeAxis(raw[2], raw[3]) * scale,
                decodeAxis(raw[4], raw[5]) * scale,
        };
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * @return {x, y, z} in µT
     * @throws IOException on bus error
     */
    public double[] readMagnetic() throws IOException {
        byte[] raw = magReadBurst(MAG_REG_OUT_X_H, 6);
        double xy = mgGainIndex(MAG_XY);
        double z = mgGainIndex(MAG_Z);
        double x = (short) (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) / xy * 100;
        double zz = (short) (((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF)) / z * 100;
        double y = (short) (((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)) / xy * 100;
        return new double[]{x, y, zz};
    }

    private double mgGainIndex(double[] table) {
        for (int i = 0; i < MAG_CODES.length; i++) if (MAG_CODES[i] == magGainCode) return table[i];
        return table[0];
    }

    protected static void sleepMs(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected static int roundHalfAway(double value) {
        return value >= 0 ? (int) Math.floor(value + 0.5) : -(int) Math.floor(-value + 0.5);
    }

    protected static int rateBit(int rate) {
        for (int i = 0; i < RATE_CODES.length; i++) if (RATE_CODES[i] == rate) return RATE_BITS[i];
        return -1;
    }

    protected static boolean isLowPowerOnly(int rate) {
        return rate == 1620 || rate == 5376;
    }

    protected static boolean isNormalHighResOnly(int rate) {
        return rate == 1344;
    }
}
