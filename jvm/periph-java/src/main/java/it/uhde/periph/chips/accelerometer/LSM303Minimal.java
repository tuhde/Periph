package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LSM303DLHC — e-compass module: 3-axis MEMS accelerometer plus 3-axis magnetometer
 * behind two independent I²C slaves (STMicroelectronics) — minimal interface.
 *
 * <p>The module is one logical device with two connections, the same pattern as
 * {@code MPU9250}: the accelerometer is on {@code accelConnection} (fixed address
 * 0x19) and the magnetometer on {@code magConnection} (fixed address 0x1E). Both
 * must be on the same bus. Each connection is a {@link RegisterConnection} with
 * 1-byte register addresses.
 *
 * <p>The two slaves frame sub-addresses differently: the accelerometer ORs bit 7
 * into the sub-address for multi-byte transfers (auto-increment), while the
 * magnetometer's address pointer always auto-increments and needs no such bit.
 * The driver handles both itself.
 *
 * <p>Initialisation verifies the magnetometer identity ({@code 0x48 0x34 0x33}, ASCII
 * "H43"; the accelerometer has no identity register), then writes every control
 * register explicitly. Defaults:
 * <ul>
 *   <li>Accelerometer: ±2&nbsp;g, 100&nbsp;Hz, high-resolution (12-bit, 1&nbsp;mg/LSB),
 *       X/Y/Z enabled, BDU=1, high-pass bypassed, FIFO bypassed, interrupts unrouted</li>
 *   <li>Magnetometer: ±1.3&nbsp;gauss (±130&nbsp;µT), 15&nbsp;Hz, continuous conversion,
 *       temperature sensor off</li>
 * </ul>
 *
 * <p>The datasheet revision committed with this driver does not document the
 * normal / low-power resolution split; the mode-dependent shifts and sensitivities
 * follow the LIS3DH sibling and are unverified on hardware.
 */
public class LSM303Minimal {

    // ---- Accelerometer registers (connection address 0x19) ----------------
    protected static final int REG_CTRL_REG1_A     = 0x20;
    protected static final int REG_CTRL_REG2_A     = 0x21;
    protected static final int REG_CTRL_REG3_A     = 0x22;
    protected static final int REG_CTRL_REG4_A     = 0x23;
    protected static final int REG_CTRL_REG5_A     = 0x24;
    protected static final int REG_CTRL_REG6_A     = 0x25;
    protected static final int REG_REFERENCE_A     = 0x26;
    protected static final int REG_STATUS_REG_A    = 0x27;
    protected static final int REG_OUT_X_L_A       = 0x28;
    protected static final int REG_FIFO_CTRL_REG_A = 0x2E;
    protected static final int REG_FIFO_SRC_REG_A  = 0x2F;
    protected static final int REG_INT1_CFG_A      = 0x30;
    protected static final int REG_INT1_SOURCE_A   = 0x31;
    protected static final int REG_INT1_THS_A      = 0x32;
    protected static final int REG_INT1_DURATION_A = 0x33;
    protected static final int REG_INT2_CFG_A      = 0x34;
    protected static final int REG_INT2_SOURCE_A   = 0x35;
    protected static final int REG_INT2_THS_A      = 0x36;
    protected static final int REG_INT2_DURATION_A = 0x37;
    protected static final int REG_CLICK_CFG_A     = 0x38;
    protected static final int REG_CLICK_SRC_A     = 0x39;
    protected static final int REG_CLICK_THS_A     = 0x3A;
    protected static final int REG_TIME_LIMIT_A    = 0x3B;
    protected static final int REG_TIME_LATENCY_A  = 0x3C;
    protected static final int REG_TIME_WINDOW_A   = 0x3D;

    // ---- Magnetometer registers (connection address 0x1E) -----------------
    protected static final int REG_CRA_REG_M     = 0x00;
    protected static final int REG_CRB_REG_M     = 0x01;
    protected static final int REG_MR_REG_M      = 0x02;
    protected static final int REG_OUT_X_H_M     = 0x03;
    protected static final int REG_SR_REG_M      = 0x09;
    protected static final int REG_IRA_REG_M     = 0x0A;
    protected static final int REG_TEMP_OUT_H_M  = 0x31;

    /** Bit 7 of the accelerometer sub-address: auto-increment for multi-byte transfers. */
    protected static final int ACCEL_AUTO_INC = 0x80;

    /** Magnetometer identity bytes IRA, IRB, IRC ("H43"). */
    protected static final int[] MAG_ID = {0x48, 0x34, 0x33};

    /** Accelerometer right-shift per mode (index: 0 low-power, 1 normal, 2 high-resolution). */
    protected static final int[] ACCEL_SHIFT = {8, 6, 4};

    /** Accelerometer mg/LSB, indexed [mode][FS code] (FS code: 0 ±2 g … 3 ±16 g). */
    protected static final int[][] ACCEL_MG_PER_LSB = {
        {16, 32, 64, 192},   // low-power (8-bit)
        {4, 8, 16, 48},      // normal (10-bit)
        {1, 2, 4, 12},       // high-resolution (12-bit)
    };

    /** Magnetometer X/Y gain in LSB/gauss, indexed by GN code − 1 (codes 001…111). */
    protected static final int[] MAG_GAIN_XY = {1100, 855, 670, 450, 400, 330, 230};

    /** Magnetometer Z gain in LSB/gauss, indexed by GN code − 1 (Z differs from X/Y). */
    protected static final int[] MAG_GAIN_Z = {980, 760, 600, 400, 355, 295, 205};

    /** Magnetometer full scale in µT, indexed by GN code − 1. */
    protected static final int[] MAG_RANGE_UT = {130, 190, 250, 400, 470, 560, 810};

    protected final RegisterConnection accel;
    protected final RegisterConnection mag;

    /** Accelerometer full-scale code (0 ±2 g, 1 ±4 g, 2 ±8 g, 3 ±16 g). */
    protected int accelFs = 0;
    /** Accelerometer mode (0 low-power, 1 normal, 2 high-resolution). */
    protected int accelMode = 2;
    /** Accelerometer output data rate in Hz (0 = power-down). */
    protected int odrHz = 100;
    /** Magnetometer X/Y gain in LSB/gauss. */
    protected double magGainXy = 1100.0;
    /** Magnetometer Z gain in LSB/gauss. */
    protected double magGainZ = 980.0;
    /** Magnetometer full scale in µT. */
    protected int magRangeUt = 130;

    /**
     * Construct the driver and run the initialisation sequence.
     *
     * <p>Verifies the magnetometer identity, writes all control registers, and waits
     * for the accelerometer turn-on and first magnetometer sample (about 75&nbsp;ms).
     *
     * @param accelConnection register connection to the accelerometer (address 0x19)
     * @param magConnection   register connection to the magnetometer (address 0x1E)
     * @throws IOException on bus error or if the magnetometer identity does not match
     */
    public LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        this.accel = accelConnection;
        this.mag = magConnection;

        sleepMs(5);

        byte[] id = mag.read(REG_IRA_REG_M, 3);
        for (int i = 0; i < 3; i++) {
            if ((id[i] & 0xFF) != MAG_ID[i]) {
                throw new IOException(String.format(
                        "LSM303 magnetometer identity: expected 0x483433, got 0x%02X%02X%02X",
                        id[0] & 0xFF, id[1] & 0xFF, id[2] & 0xFF));
            }
        }

        accelWrite(REG_CTRL_REG2_A, 0x00);
        accelWrite(REG_CTRL_REG3_A, 0x00);
        accelWrite(REG_CTRL_REG5_A, 0x00);
        accelWrite(REG_CTRL_REG6_A, 0x00);
        accelWrite(REG_FIFO_CTRL_REG_A, 0x00);
        accelWrite(REG_CTRL_REG4_A, 0x88);  // BDU=1, FS=±2 g, HR=1
        accelWrite(REG_CTRL_REG1_A, 0x57);  // ODR=100 Hz, LPen=0, X/Y/Z enabled

        magWrite(REG_CRA_REG_M, 0x10);      // 15 Hz, temperature off
        magWrite(REG_CRB_REG_M, 0x20);      // ±1.3 gauss
        magWrite(REG_MR_REG_M, 0x00);       // continuous conversion (reset value is sleep)

        sleepMs(70);
    }

    /**
     * Read 3-axis acceleration.
     *
     * <p>Burst-reads {@code OUT_X_L_A}…{@code OUT_Z_H_A} in one transaction so the
     * three axes come from the same sample. Left-justified 16-bit data is
     * sign-extended and shifted by the current mode.
     *
     * @return array {@code [x, y, z]} in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] readAcceleration() throws IOException {
        return convertAccel(accel.read(REG_OUT_X_L_A | ACCEL_AUTO_INC, 6));
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * <p>Burst-reads {@code OUT_X_H_M}…{@code OUT_Y_L_M} (six bytes, high byte first),
     * reorders the wire order X, Z, Y into X, Y, Z, and converts to µT with the
     * per-axis gain (Z uses its own gain).
     *
     * @return array {@code [x, y, z]} in µT
     * @throws IOException on bus error
     */
    public double[] readMagnetic() throws IOException {
        int[] c = readMagCounts();
        return new double[]{
            c[0] / magGainXy * 100.0,
            c[1] / magGainXy * 100.0,
            c[2] / magGainZ * 100.0,
        };
    }

    /** Convert a 6-byte accelerometer burst (X, Y, Z, low byte first) to <i>g</i>. */
    protected double[] convertAccel(byte[] raw) {
        int[] c = accelCounts(raw);
        double mgPerLsb = ACCEL_MG_PER_LSB[accelMode][accelFs];
        return new double[]{c[0] * mgPerLsb / 1000.0, c[1] * mgPerLsb / 1000.0, c[2] * mgPerLsb / 1000.0};
    }

    /** Convert a 6-byte accelerometer burst to signed counts at the current resolution. */
    protected int[] accelCounts(byte[] raw) {
        int shift = ACCEL_SHIFT[accelMode];
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            int v = Register.toSigned((raw[2 * i] & 0xFF) | ((raw[2 * i + 1] & 0xFF) << 8), 16);
            out[i] = v >> shift;
        }
        return out;
    }

    /** Read the six magnetometer output bytes and return signed counts in X, Y, Z order. */
    protected int[] readMagCounts() throws IOException {
        byte[] raw = mag.read(REG_OUT_X_H_M, 6);
        int x = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16);
        int z = Register.toSigned(((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF), 16);
        int y = Register.toSigned(((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF), 16);
        return new int[]{x, y, z};
    }

    /** Full scale in <i>g</i> for the current FS code (2, 4, 8 or 16). */
    protected int fullScaleG() {
        return 2 << accelFs;
    }

    /** 7-bit threshold code for a threshold in <i>g</i> (1 LSb = full scale / 128). */
    protected int thresholdCode(double thresholdG) {
        return clamp((int) Math.round(thresholdG * 128.0 / fullScaleG()), 0, 127);
    }

    /** Convert a time in ms to a count of accelerometer output periods (1 LSb = 1/ODR), clamped to {@code max}. */
    protected int timeCode(double timeMs, int max) {
        return clamp((int) Math.round(timeMs * odrHz / 1000.0), 0, max);
    }

    /** Write one accelerometer register. */
    protected void accelWrite(int reg, int value) throws IOException {
        accel.write(reg, new byte[]{(byte) value});
    }

    /** Read one accelerometer register. */
    protected int accelRead(int reg) throws IOException {
        return accel.read(reg, 1)[0] & 0xFF;
    }

    /** Read-modify-write: replace the bits in {@code mask} with {@code value & mask}. */
    protected void accelUpdate(int reg, int mask, int value) throws IOException {
        int r = accelRead(reg);
        accelWrite(reg, (r & ~mask) | (value & mask));
    }

    /** Write one magnetometer register. */
    protected void magWrite(int reg, int value) throws IOException {
        mag.write(reg, new byte[]{(byte) value});
    }

    /** Read one magnetometer register. */
    protected int magRead(int reg) throws IOException {
        return mag.read(reg, 1)[0] & 0xFF;
    }

    /** Read-modify-write on a magnetometer register. */
    protected void magUpdate(int reg, int mask, int value) throws IOException {
        int r = magRead(reg);
        magWrite(reg, (r & ~mask) | (value & mask));
    }

    /** Clamp {@code v} into {@code [lo, hi]}. */
    protected static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Sleep without propagating interruption; restores the interrupt flag. */
    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
