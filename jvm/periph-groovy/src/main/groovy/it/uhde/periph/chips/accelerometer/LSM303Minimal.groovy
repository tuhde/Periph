package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/** LSM303 accelerometer + magnetometer e-compass — minimal interface. */
@CompileStatic
class LSM303Minimal {
    protected static final int REG_CTRL_REG1 = 0x20
    protected static final int REG_CTRL_REG2 = 0x21
    protected static final int REG_CTRL_REG3 = 0x22
    protected static final int REG_CTRL_REG4 = 0x23
    protected static final int REG_CTRL_REG5 = 0x24
    protected static final int REG_CTRL_REG6 = 0x25
    protected static final int REG_REFERENCE = 0x26
    protected static final int REG_STATUS = 0x27
    protected static final int REG_OUT_X_L = 0x28
    protected static final int REG_OUT_X_H = 0x29
    protected static final int REG_OUT_Y_L = 0x2A
    protected static final int REG_OUT_Y_H = 0x2B
    protected static final int REG_OUT_Z_L = 0x2C
    protected static final int REG_OUT_Z_H = 0x2D
    protected static final int REG_FIFO_CTRL = 0x2E
    protected static final int REG_FIFO_SRC = 0x2F
    protected static final int REG_INT1_CFG = 0x30
    protected static final int REG_INT1_SRC = 0x31
    protected static final int REG_INT1_THS = 0x32
    protected static final int REG_INT1_DUR = 0x33
    protected static final int REG_INT2_CFG = 0x34
    protected static final int REG_INT2_SRC = 0x35
    protected static final int REG_INT2_THS = 0x36
    protected static final int REG_INT2_DUR = 0x37
    protected static final int REG_CLICK_CFG = 0x38
    protected static final int REG_CLICK_SRC = 0x39
    protected static final int REG_CLICK_THS = 0x3A
    protected static final int REG_TIME_LIMIT = 0x3B
    protected static final int REG_TIME_LATENCY = 0x3C
    protected static final int REG_TIME_WINDOW = 0x3D

    protected static final int MAG_REG_CRA = 0x00
    protected static final int MAG_REG_CRB = 0x01
    protected static final int MAG_REG_MR = 0x02
    protected static final int MAG_REG_OUT_X_H = 0x03
    protected static final int MAG_REG_SR = 0x09
    protected static final int MAG_REG_IRA = 0x0A
    protected static final int MAG_REG_TEMP_H = 0x31
    protected static final int MAG_REG_TEMP_L = 0x32

    protected static final int CTRL_REG4_DEFAULT = 0x88
    protected static final int CTRL_REG1_DEFAULT = 0x57
    protected static final int AUTO_INCREMENT = 0x80
    protected static final int BOOT_DELAY_MS = 5

    static final int MODE_LOW_POWER = 0
    static final int MODE_NORMAL = 1
    static final int MODE_HIGH_RESOLUTION = 2

    static final int RANGE_2G = 2
    static final int RANGE_4G = 4
    static final int RANGE_8G = 8
    static final int RANGE_16G = 16

    private static final int[] RANGES = [2, 4, 8, 16] as int[]
    private static final int[] RATE_CODES = [0, 1, 10, 25, 50, 100, 200, 400, 1620, 1344, 5376] as int[]
    private static final int[] RATE_BITS = [0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0x90] as int[]

    protected static final int[] MAG_CODES = [0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0] as int[]
    protected static final int[] MAG_RANGES = [130, 190, 250, 400, 470, 560, 810] as int[]
    protected static final double[] MAG_XY = [1100d, 855d, 670d, 450d, 400d, 330d, 230d] as double[]
    protected static final double[] MAG_Z = [980d, 760d, 600d, 400d, 355d, 295d, 205d] as double[]
    protected static final double[] MAG_RATES = [0.75d, 1.5d, 3d, 7.5d, 15d, 30d, 75d, 220d] as double[]
    protected static final int[] MAG_RATE_BITS = [0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C] as int[]

    protected final RegisterConnection accelConnection
    protected final RegisterConnection magConnection
    protected int rangeG = RANGE_2G
    protected int mode = MODE_HIGH_RESOLUTION
    protected int rateHz = 100
    protected int magGainCode = 0x20
    protected double magRateHz = 15d

    LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection) {
        this.accelConnection = accelConnection
        this.magConnection = magConnection
        runInitSequence()
    }

    protected void runInitSequence() {
        sleepMs(BOOT_DELAY_MS)
        byte[] identity = magConnection.read(MAG_REG_IRA, 3)
        if ((identity[0] & 0xFF) != 0x48 || (identity[1] & 0xFF) != 0x34 || (identity[2] & 0xFF) != 0x33) {
            throw new IOException('LSM303 magnetometer identity: expected 48 34 33')
        }
        writeReg(REG_CTRL_REG2, 0x00)
        writeReg(REG_CTRL_REG3, 0x00)
        writeReg(REG_CTRL_REG5, 0x00)
        writeReg(REG_CTRL_REG6, 0x00)
        writeReg(REG_FIFO_CTRL, 0x00)
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT)
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
        magWriteReg(MAG_REG_CRA, 0x10)
        magWriteReg(MAG_REG_CRB, magGainCode)
        magWriteReg(MAG_REG_MR, 0x00)
        sleepMs(Math.max(settleMs(), (int) Math.ceil(1000d / magRateHz)))
    }

    protected void writeReg(int reg, int value) {
        accelConnection.write(reg, [(byte) value] as byte[])
    }

    protected int readReg(int reg) {
        return (accelConnection.read(reg, 1)[0] as int) & 0xFF
    }

    protected byte[] readBurst(int reg, int length) {
        return accelConnection.read(length > 1 ? (reg | AUTO_INCREMENT) : reg, length)
    }

    protected void magWriteReg(int reg, int value) {
        magConnection.write(reg, [(byte) value] as byte[])
    }

    protected int magReadReg(int reg) {
        return (magConnection.read(reg, 1)[0] as int) & 0xFF
    }

    protected byte[] magReadBurst(int reg, int length) {
        return magConnection.read(reg, length)
    }

    protected int settleMs() {
        if (rateHz <= 0) return 0
        double period = 1000d / rateHz
        double turnOn
        if (mode == MODE_LOW_POWER) turnOn = 1d
        else if (mode == MODE_NORMAL) turnOn = 1.6d
        else turnOn = 7d * period
        return (int) Math.ceil(Math.max(turnOn, period))
    }

    protected int decodeAxis(byte low, byte high) {
        int raw = ((low as int) & 0xFF) | (((high as int) & 0xFF) << 8)
        int signed16 = (short) raw
        int shift = mode == MODE_LOW_POWER ? 8 : (mode == MODE_NORMAL ? 6 : 4)
        return signed16 >> shift
    }

    protected double sensitivityMg() {
        int index = 0
        for (int i = 0; i < RANGES.length; i++) if (RANGES[i] == rangeG) index = i
        if (mode == MODE_LOW_POWER) return [16d, 32d, 64d, 192d][index] as double
        if (mode == MODE_NORMAL) return [4d, 8d, 16d, 48d][index] as double
        return [1d, 2d, 4d, 12d][index] as double
    }

    /** Read 3-axis linear acceleration in g. */
    double[] readAcceleration() {
        byte[] raw = readBurst(REG_OUT_X_L, 6)
        double scale = sensitivityMg() / 1000d
        return [decodeAxis(raw[0], raw[1]) * scale,
                decodeAxis(raw[2], raw[3]) * scale,
                decodeAxis(raw[4], raw[5]) * scale] as double[]
    }

    /** Read 3-axis magnetic flux density in µT. */
    double[] readMagnetic() {
        byte[] raw = magReadBurst(MAG_REG_OUT_X_H, 6)
        int gainIndex = 0
        for (int i = 0; i < MAG_CODES.length; i++) if (MAG_CODES[i] == magGainCode) gainIndex = i
        double xy = MAG_XY[gainIndex]
        double z = MAG_Z[gainIndex]
        double x = (short) ((((raw[0] as int) & 0xFF) << 8) | ((raw[1] as int) & 0xFF)) / xy * 100d
        double zz = (short) ((((raw[2] as int) & 0xFF) << 8) | ((raw[3] as int) & 0xFF)) / z * 100d
        double y = (short) ((((raw[4] as int) & 0xFF) << 8) | ((raw[5] as int) & 0xFF)) / xy * 100d
        return [x, y, zz] as double[]
    }

    protected static void sleepMs(int ms) {
        if (ms > 0) Thread.sleep((long) ms)
    }

    protected static int roundHalfAway(double value) {
        return value >= 0 ? (int) Math.floor(value + 0.5d) : -(int) Math.floor(-value + 0.5d)
    }

    protected static int rateBit(int rate) {
        for (int i = 0; i < RATE_CODES.length; i++) if (RATE_CODES[i] == rate) return RATE_BITS[i]
        return -1
    }

    protected static boolean isLowPowerOnly(int rate) { return rate == 1620 || rate == 5376 }

    protected static boolean isNormalHighResOnly(int rate) { return rate == 1344 }
}
