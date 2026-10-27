package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LSM303DLHC — 3-axis accelerometer + 3-axis magnetometer e-compass
 * (STMicroelectronics) — minimal interface.
 *
 * <p>System-in-package combining a three-axis MEMS accelerometer (I²C address
 * 0x19) and a three-axis magnetometer (I²C address 0x1E) behind two independent
 * I²C slave addresses on one bus. The driver treats the module as one logical
 * device with two connections; the compass heading is computed in software
 * from both sensors (see {@code LSM303Full}). Same two-connection pattern as
 * {@code MPU9250}.
 *
 * <p>Default configuration (written at construction):
 * <ul>
 *   <li>Accelerometer: ±2 g (1 mg/LSB), 100 Hz ODR, high-resolution mode,
 *       all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
 *       all interrupt routing cleared</li>
 *   <li>Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
 *       temperature sensor off</li>
 * </ul>
 *
 * <p>The two slaves use different I²C framing: the accelerometer sub-address
 * bit 7 enables address auto-increment (the driver sets it for every
 * multi-byte transfer); the magnetometer has an address pointer that always
 * auto-increments and needs no extra bit. Magnetometer outputs are X, Z, Y
 * with the high byte first — the driver reorders to X, Y, Z. The
 * accelerometer outputs are X, Y, Z low byte first.
 */
public class LSM303Minimal {

    // Accelerometer register map (sub-address bit 7 = auto-increment).
    protected static final int REG_CTRL1_A      = 0x20;
    protected static final int REG_CTRL2_A      = 0x21;
    protected static final int REG_CTRL3_A      = 0x22;
    protected static final int REG_CTRL4_A      = 0x23;
    protected static final int REG_CTRL5_A      = 0x24;
    protected static final int REG_CTRL6_A      = 0x25;
    protected static final int REG_REFERENCE_A  = 0x26;
    protected static final int REG_STATUS_A     = 0x27;
    protected static final int REG_OUT_X_L_A    = 0x28;
    protected static final int REG_FIFO_CTRL_A  = 0x2E;
    protected static final int REG_FIFO_SRC_A   = 0x2F;
    protected static final int REG_INT1_CFG_A   = 0x30;
    protected static final int REG_INT1_SRC_A   = 0x31;
    protected static final int REG_INT1_THS_A   = 0x32;
    protected static final int REG_INT1_DUR_A   = 0x33;
    protected static final int REG_INT2_CFG_A   = 0x34;
    protected static final int REG_INT2_SRC_A   = 0x35;
    protected static final int REG_INT2_THS_A   = 0x36;
    protected static final int REG_INT2_DUR_A   = 0x37;
    protected static final int REG_CLICK_CFG_A  = 0x38;
    protected static final int REG_CLICK_SRC_A  = 0x39;
    protected static final int REG_CLICK_THS_A  = 0x3A;
    protected static final int REG_TIME_LIMIT_A = 0x3B;
    protected static final int REG_TIME_LAT_A   = 0x3C;
    protected static final int REG_TIME_WIN_A   = 0x3D;

    // Magnetometer register map (address pointer auto-increments; no auto-inc bit).
    protected static final int REG_CRA_M     = 0x00;
    protected static final int REG_CRB_M     = 0x01;
    protected static final int REG_MR_M      = 0x02;
    protected static final int REG_OUT_X_H_M = 0x03;
    protected static final int REG_SR_M      = 0x09;
    protected static final int REG_IRA_M     = 0x0A;
    protected static final int REG_TEMP_H_M  = 0x31;

    /** STATUS_REG_A bit 3 (0x08): new XYZ data available. */
    protected static final int STATUS_ZYXDA = 0x08;

    // Sensitivity in mg/LSB, indexed [mode][range]; mode 0 = low-power (8-bit),
    // 1 = normal (10-bit), 2 = high-resolution (12-bit); range 0..3 = ±2/4/8/16 g.
    protected static final double[][] ACCEL_SENSITIVITY_MG = {
            {16.0, 32.0, 64.0, 192.0},
            {4.0, 8.0, 16.0, 48.0},
            {1.0, 2.0, 4.0, 12.0},
    };

    // Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
    protected static final int[] ACCEL_SHIFT = {8, 6, 4};

    // Full scale in g by range bits 0..3.
    protected static final double[] ACCEL_FULL_SCALE_G = {2.0, 4.0, 8.0, 16.0};

    // Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for XY and Z.
    protected static final double[] MAG_FULL_SCALE_UT = {130.0, 190.0, 250.0, 400.0, 470.0, 560.0, 810.0};
    protected static final double[] MAG_GAIN_XY = {1100.0, 855.0, 670.0, 450.0, 400.0, 330.0, 230.0};
    protected static final double[] MAG_GAIN_Z  = {980.0, 760.0, 600.0, 400.0, 355.0, 295.0, 205.0};

    /**
     * Construct and initialise the LSM303.
     *
     * @param accelConnection RegisterConnection (I²C or SMBus) bound to the
     *                        accelerometer slave address 0x19
     * @param magConnection   RegisterConnection (I²C or SMBus) bound to the
     *                        magnetometer slave address 0x1E, on the same bus
     * @throws IOException on bus error or wrong magnetometer identity
     */
    public LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        this.accelConn = accelConnection;
        this.magConn = magConnection;
        this.rangeBits = 0;         // 0..3: ±2/4/8/16 g
        this.mode = 2;              // 0 low-power, 1 normal, 2 high-resolution
        this.odrHz = 100.0;         // accelerometer output data rate
        this.magGn = 0;             // 0..6 index into the gain tables
        this.magOdrHz = 15.0;
        this.tempEnabled = false;

        // Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
        sleepMs(5);

        byte[] identity = magConn.read(REG_IRA_M, 3);
        if ((identity[0] & 0xFF) != 0x48 || (identity[1] & 0xFF) != 0x34 || (identity[2] & 0xFF) != 0x33) {
            throw new IOException("LSM303 magnetometer ID: expected 0x483433, got 0x"
                    + hex2(identity[0]) + hex2(identity[1]) + hex2(identity[2]));
        }

        // Accelerometer: no soft reset — write the control registers to known values.
        accelWrite(REG_CTRL2_A, 0x00);
        accelWrite(REG_CTRL3_A, 0x00);
        accelWrite(REG_CTRL5_A, 0x00);
        accelWrite(REG_CTRL6_A, 0x00);
        accelWrite(REG_FIFO_CTRL_A, 0x00);
        // BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
        accelWrite(REG_CTRL4_A, 0x88);
        // ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
        accelWrite(REG_CTRL1_A, 0x57);

        // Magnetometer: the part resets into sleep mode, so write all three
        // control registers explicitly (15 Hz, ±1.3 gauss, continuous).
        magWrite(REG_CRA_M, 0x10);
        magWrite(REG_CRB_M, 0x20);
        magWrite(REG_MR_M, 0x00);

        // Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
        // output period (67 ms at 15 Hz) for the first magnetometer sample.
        sleepMs(70);
        sleepMs(67);
    }

    /** Accelerometer connection, bound to 0x19 (visible to {@code LSM303Full}). */
    protected final RegisterConnection accelConn;

    /** Magnetometer connection, bound to 0x1E (visible to {@code LSM303Full}). */
    protected final RegisterConnection magConn;

    // Configuration cached for conversion validation.
    /** Full-scale bits 0..3 (±2/4/8/16 g). */
    protected int rangeBits;

    /** Operating mode: 0 low-power, 1 normal, 2 high-resolution. */
    protected int mode;

    /** Accelerometer output data rate in Hz. */
    protected double odrHz;

    /** Magnetometer gain index 0..6. */
    protected int magGn;

    /** Magnetometer output data rate in Hz. */
    protected double magOdrHz;

    /** True once TEMP_EN has been set. */
    protected boolean tempEnabled;

    /**
     * Read 3-axis linear acceleration.
     *
     * <p>Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so
     * all six bytes belong to one sample (BDU=1).
     *
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] readAcceleration() throws IOException {
        byte[] raw = accelConn.read(REG_OUT_X_L_A | 0x80, 6);
        int[] c = convertAccelSample(raw);
        double scale = accelSensitivityG();
        return new double[]{c[0] * scale, c[1] * scale, c[2] * scale};
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * <p>Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z, Y,
     * each high byte first) in one transaction and reorders to X, Y, Z. Z uses
     * a different gain (LSB/gauss) from X and Y at every range setting.
     *
     * @return array of three doubles — X, Y, Z magnetic flux density in µT
     * @throws IOException on bus error
     */
    public double[] readMagnetic() throws IOException {
        byte[] raw = magConn.read(REG_OUT_X_H_M, 6);
        int mx = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16);
        int mz = Register.toSigned(((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF), 16);
        int my = Register.toSigned(((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF), 16);
        double gainXY = MAG_GAIN_XY[magGn];
        double gainZ = MAG_GAIN_Z[magGn];
        return new double[]{mx / gainXY * 100.0, my / gainXY * 100.0, mz / gainZ * 100.0};
    }

    /**
     * Convert a 6-byte accelerometer burst to (x, y, z) signed counts at the
     * current mode's resolution (sign-extend the 16-bit pair first, then
     * arithmetic-shift right).
     */
    protected int[] convertAccelSample(byte[] raw) {
        int rx = Register.toSigned((raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8), 16);
        int ry = Register.toSigned((raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8), 16);
        int rz = Register.toSigned((raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8), 16);
        int shift = ACCEL_SHIFT[mode];
        return new int[]{rx >> shift, ry >> shift, rz >> shift};
    }

    /** Sensitivity in g/LSB at the current mode and range. */
    protected double accelSensitivityG() {
        return ACCEL_SENSITIVITY_MG[mode][rangeBits] / 1000.0;
    }

    protected int accelRead(int reg) throws IOException {
        return accelConn.read(reg, 1)[0] & 0xFF;
    }

    protected void accelWrite(int reg, int value) throws IOException {
        accelConn.write(reg, new byte[]{(byte) value});
    }

    protected int magRead(int reg) throws IOException {
        return magConn.read(reg, 1)[0] & 0xFF;
    }

    protected void magWrite(int reg, int value) throws IOException {
        magConn.write(reg, new byte[]{(byte) value});
    }

    /** Sleep the given time without propagating interrupts. */
    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hex2(byte b) {
        return String.format("%02X", b & 0xFF);
    }
}
