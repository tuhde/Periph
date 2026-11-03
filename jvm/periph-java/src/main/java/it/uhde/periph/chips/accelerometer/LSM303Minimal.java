package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics)
 * — minimal interface.
 *
 * <p>The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
 * magnetometer in one package. The two dies sit behind <b>two independent I²C
 * slave addresses</b> on the same bus: the accelerometer at 0x19 and the
 * magnetometer at 0x1E. The driver therefore takes two connections.
 *
 * <p>Accelerometer (0x19):
 * <ul>
 *   <li>Four full-scale ranges (±2 / ±4 / ±8 / ±16 <i>g</i>), 12-bit output at
 *       1 mg/LSB in the default high-resolution mode</li>
 *   <li>Output data rates from 1 Hz to 5.376 kHz, a 32-level FIFO, a
 *       selectable high-pass filter</li>
 *   <li>Two programmable inertial interrupt generators (threshold, free-fall,
 *       6D/4D orientation) and single/double click detection</li>
 * </ul>
 *
 * <p>Magnetometer (0x1E):
 * <ul>
 *   <li>Seven full-scale ranges (±130 µT to ±810 µT), 12-bit output</li>
 *   <li>Output data rates from 0.75 Hz to 220 Hz and a relative-only
 *       temperature sensor</li>
 * </ul>
 *
 * <p>I²C framing differs between the two slaves:
 * <ul>
 *   <li><b>Accelerometer</b> — after the slave address the master sends an
 *       8-bit sub-address: the 7 LSbs are the register address and the
 *       <b>MSb enables address auto-increment</b>. A single-register access
 *       uses {@code reg}; a multi-byte access uses {@code reg | 0x80}.</li>
 *   <li><b>Magnetometer</b> — an address pointer without an auto-increment
 *       bit. The pointer always advances by one, so a 6-byte read from 0x03
 *       returns all six output registers in one transaction.</li>
 * </ul>
 *
 * <p>The magnetometer output order is <b>X, Z, Y</b> (not X, Y, Z) and the byte
 * order is <b>high byte first</b> — the opposite of the accelerometer.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>Accelerometer: ±2 <i>g</i>, 100 Hz, high-resolution (12-bit), all three
 *       axes enabled, {@code BDU} set, high-pass filter bypassed, FIFO
 *       bypassed, all interrupt routing cleared</li>
 *   <li>Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
 *       temperature sensor off</li>
 * </ul>
 */
public class LSM303Minimal {

    // --- Accelerometer registers -------------------------------------------
    protected static final int REG_CTRL1_A = 0x20;
    protected static final int REG_CTRL2_A = 0x21;
    protected static final int REG_CTRL3_A = 0x22;
    protected static final int REG_CTRL4_A = 0x23;
    protected static final int REG_CTRL5_A = 0x24;
    protected static final int REG_CTRL6_A = 0x25;
    protected static final int REG_REFERENCE_A = 0x26;
    protected static final int REG_STATUS_A = 0x27;
    protected static final int REG_OUT_X_L_A = 0x28;
    protected static final int REG_FIFO_CTRL_A = 0x2E;
    protected static final int REG_FIFO_SRC_A = 0x2F;
    protected static final int REG_INT1_CFG_A = 0x30;
    protected static final int REG_INT1_SOURCE_A = 0x31;
    protected static final int REG_INT1_THS_A = 0x32;
    protected static final int REG_INT1_DURATION_A = 0x33;
    protected static final int REG_INT2_CFG_A = 0x34;
    protected static final int REG_INT2_SOURCE_A = 0x35;
    protected static final int REG_INT2_THS_A = 0x36;
    protected static final int REG_INT2_DURATION_A = 0x37;
    protected static final int REG_CLICK_CFG_A = 0x38;
    protected static final int REG_CLICK_SRC_A = 0x39;
    protected static final int REG_CLICK_THS_A = 0x3A;
    protected static final int REG_TIME_LIMIT_A = 0x3B;
    protected static final int REG_TIME_LATENCY_A = 0x3C;
    protected static final int REG_TIME_WINDOW_A = 0x3D;

    // --- Magnetometer registers --------------------------------------------
    protected static final int REG_CRA_M = 0x00;
    protected static final int REG_CRB_M = 0x01;
    protected static final int REG_MR_M = 0x02;
    protected static final int REG_OUT_X_H_M = 0x03;
    protected static final int REG_SR_M = 0x09;
    protected static final int REG_IRA_M = 0x0A;
    protected static final int REG_TEMP_OUT_H_M = 0x31;

    /** Fixed I²C slave address of the accelerometer. */
    public static final int I2C_ADDRESS_ACCEL = 0x19;
    /** Fixed I²C slave address of the magnetometer. */
    public static final int I2C_ADDRESS_MAG = 0x1E;

    /** The magnetometer identity (IRA/IRB/IRC) of a real part. */
    protected static final byte[] MAG_IDENTITY = {0x48, 0x34, 0x33};

    /** Bit 7 of the accelerometer sub-address enables address auto-increment. */
    protected static final int ACCEL_AUTO_INCREMENT = 0x80;

    /** Accelerometer operating mode: low-power (8-bit output). */
    public static final int MODE_LOW_POWER = 0;
    /** Accelerometer operating mode: normal (10-bit output). */
    public static final int MODE_NORMAL = 1;
    /** Accelerometer operating mode: high-resolution (12-bit output). */
    public static final int MODE_HIGH_RESOLUTION = 2;

    protected final RegisterConnection accelConnection;
    protected final RegisterConnection magConnection;

    protected int mode = MODE_HIGH_RESOLUTION;
    protected int fsG = 2;
    protected double odrHz = 100.0;
    protected int magRangeUt = 130;

    /**
     * Construct the driver and run the datasheet initialization sequence.
     *
     * @param accelConnection register connection bound to 0x19
     * @param magConnection register connection bound to 0x1E
     * @throws IOException on bus error or wrong magnetometer identity
     */
    public LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection)
            throws IOException {
        this.accelConnection = accelConnection;
        this.magConnection = magConnection;
        init();
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * <p>Verifies the magnetometer identity, clears the accelerometer's
     * interrupt routing, FIFO and high-pass configuration, selects
     * ±2 <i>g</i> / 100 Hz / high-resolution on the accelerometer and
     * ±130 µT / 15 Hz / continuous conversion on the magnetometer.
     *
     * @throws IOException on bus error or wrong magnetometer identity
     */
    protected void init() throws IOException {
        // Boot download of the factory trim values.
        sleepMs(5);

        byte[] identity = magRead(REG_IRA_M, 3);
        if (identity[0] != MAG_IDENTITY[0]
                || identity[1] != MAG_IDENTITY[1]
                || identity[2] != MAG_IDENTITY[2]) {
            throw new IOException(String.format(
                    "LSM303 magnetometer not found: expected identity 0x483433, got 0x%02X%02X%02X",
                    identity[0] & 0xFF, identity[1] & 0xFF, identity[2] & 0xFF));
        }

        // Accelerometer: known state for the high-pass filter, the interrupt
        // routing and the FIFO.
        accelWrite(REG_CTRL2_A, 0x00);
        accelWrite(REG_CTRL3_A, 0x00);
        accelWrite(REG_CTRL5_A, 0x00);
        accelWrite(REG_CTRL6_A, 0x00);
        accelWrite(REG_FIFO_CTRL_A, 0x00);

        // Accelerometer: BDU = 1, ±2 g, high-resolution mode.
        accelWrite(REG_CTRL4_A, 0x88);
        // Accelerometer: 100 Hz, X/Y/Z enabled.
        accelWrite(REG_CTRL1_A, 0x57);

        // Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
        // resets into sleep mode).
        magWrite(REG_CRA_M, 0x10);
        magWrite(REG_CRB_M, 0x20);
        magWrite(REG_MR_M, 0x00);

        // Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
        sleepMs(70);
        // Magnetometer first sample: one output period = 67 ms at 15 Hz.
        sleepMs(67);

        mode = MODE_HIGH_RESOLUTION;
        fsG = 2;
        odrHz = 100.0;
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms delay in milliseconds
     */
    protected static void sleepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Low level register access -----------------------------------------

    /**
     * Read bytes from an accelerometer register.
     *
     * <p>Multi-byte reads set bit 7 of the sub-address so the accelerometer
     * auto-increments the register pointer.
     *
     * @param reg register address
     * @param length number of bytes to read
     * @return the register contents
     * @throws IOException on bus error
     */
    protected byte[] accelRead(int reg, int length) throws IOException {
        int addr = length > 1 ? (reg | ACCEL_AUTO_INCREMENT) : reg;
        return accelConnection.read(addr, length);
    }

    /**
     * Write one byte to an accelerometer register.
     *
     * @param reg register address
     * @param value value to write
     * @throws IOException on bus error
     */
    protected void accelWrite(int reg, int value) throws IOException {
        accelConnection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read bytes from a magnetometer register.
     *
     * <p>The magnetometer address pointer always auto-increments, so no
     * auto-increment bit is needed.
     *
     * @param reg register address
     * @param length number of bytes to read
     * @return the register contents
     * @throws IOException on bus error
     */
    protected byte[] magRead(int reg, int length) throws IOException {
        return magConnection.read(reg, length);
    }

    /**
     * Write one byte to a magnetometer register.
     *
     * @param reg register address
     * @param value value to write
     * @throws IOException on bus error
     */
    protected void magWrite(int reg, int value) throws IOException {
        magConnection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read a single accelerometer register.
     *
     * @param reg register address
     * @return the register value
     * @throws IOException on bus error
     */
    protected int accelReadReg(int reg) throws IOException {
        return accelRead(reg, 1)[0] & 0xFF;
    }

    /**
     * Read a single magnetometer register.
     *
     * @param reg register address
     * @return the register value
     * @throws IOException on bus error
     */
    protected int magReadReg(int reg) throws IOException {
        return magRead(reg, 1)[0] & 0xFF;
    }

    // --- Minimal API ---------------------------------------------------------

    /**
     * Read the acceleration.
     *
     * <p>Burst-reads the six output registers so all three axes belong to the
     * same sample.
     *
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] readAcceleration() throws IOException {
        byte[] raw = accelRead(REG_OUT_X_L_A, 6);
        return convertAccel(raw);
    }

    /**
     * Read the magnetic flux density.
     *
     * <p>Burst-reads the six output registers (X, Z, Y on the wire) and
     * reorders them to X, Y, Z.
     *
     * @return array of three doubles — X, Y, Z magnetic flux density in µT
     * @throws IOException on bus error
     */
    public double[] readMagnetic() throws IOException {
        byte[] raw = magRead(REG_OUT_X_H_M, 6);
        int[] v = reorderMag(raw);
        double gainX = magGainX();
        double gainZ = magGainZ();
        return new double[]{v[0] / gainX * 100.0, v[1] / gainX * 100.0, v[2] / gainZ * 100.0};
    }

    // --- Conversion helpers ---------------------------------------------------

    /**
     * Return the right-shift that turns the raw 16-bit pair into counts.
     *
     * @return the shift in bits
     */
    protected int shift() {
        if (mode == MODE_LOW_POWER) {
            return 8;
        }
        if (mode == MODE_NORMAL) {
            return 6;
        }
        return 4;
    }

    /**
     * Return the sensitivity in mg/LSB for the current mode and range.
     *
     * @return the sensitivity in mg/LSB
     */
    protected double sensitivityMg() {
        double[] table;
        if (mode == MODE_LOW_POWER) {
            table = new double[]{16, 32, 64, 192};
        } else if (mode == MODE_NORMAL) {
            table = new double[]{4, 8, 16, 48};
        } else {
            table = new double[]{1, 2, 4, 12};
        }
        int index;
        switch (fsG) {
            case 4: index = 1; break;
            case 8: index = 2; break;
            case 16: index = 3; break;
            default: index = 0; break;
        }
        return table[index];
    }

    /**
     * Convert a six-byte accelerometer burst into (x, y, z) in <i>g</i>.
     *
     * @param raw the six raw bytes, little-endian per axis
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     */
    protected double[] convertAccel(byte[] raw) {
        int s = shift();
        double sens = sensitivityMg() / 1000.0;
        int x = Register.toSigned((raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8), 16) >> s;
        int y = Register.toSigned((raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8), 16) >> s;
        int z = Register.toSigned((raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8), 16) >> s;
        return new double[]{x * sens, y * sens, z * sens};
    }

    /**
     * Convert a six-byte accelerometer burst into raw counts.
     *
     * @param raw the six raw bytes, little-endian per axis
     * @return array of three ints — X, Y, Z signed counts
     */
    protected int[] rawAccel(byte[] raw) {
        int s = shift();
        int x = Register.toSigned((raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8), 16) >> s;
        int y = Register.toSigned((raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8), 16) >> s;
        int z = Register.toSigned((raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8), 16) >> s;
        return new int[]{x, y, z};
    }

    /**
     * Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z).
     *
     * @param raw the six raw bytes, high byte first
     * @return array of three ints — X, Y, Z signed counts
     */
    protected int[] reorderMag(byte[] raw) {
        int x = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16);
        int z = Register.toSigned(((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF), 16);
        int y = Register.toSigned(((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF), 16);
        return new int[]{x, y, z};
    }

    /**
     * Return the X/Y gain in LSB per gauss for the current range.
     *
     * @return the gain
     */
    protected double magGainX() {
        switch (magRangeUt) {
            case 190: return 855;
            case 250: return 670;
            case 400: return 450;
            case 470: return 400;
            case 560: return 330;
            case 810: return 230;
            default: return 1100;
        }
    }

    /**
     * Return the Z gain in LSB per gauss for the current range.
     *
     * @return the gain
     */
    protected double magGainZ() {
        switch (magRangeUt) {
            case 190: return 760;
            case 250: return 600;
            case 400: return 400;
            case 470: return 355;
            case 560: return 295;
            case 810: return 205;
            default: return 980;
        }
    }
}
