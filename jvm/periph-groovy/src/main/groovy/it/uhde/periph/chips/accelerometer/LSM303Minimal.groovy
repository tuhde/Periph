package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics)
 * — minimal interface.
 *
 * The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
 * magnetometer in one package. The two dies sit behind **two independent I²C
 * slave addresses** on the same bus: the accelerometer at 0x19 and the
 * magnetometer at 0x1E. The driver therefore takes two connections.
 *
 * Accelerometer (0x19):
 * - Four full-scale ranges (±2 / ±4 / ±8 / ±16 *g*), 12-bit output at
 *   1 mg/LSB in the default high-resolution mode
 * - Output data rates from 1 Hz to 5.376 kHz, a 32-level FIFO, a selectable
 *   high-pass filter, two programmable inertial interrupt generators and
 *   single/double click detection
 *
 * Magnetometer (0x1E):
 * - Seven full-scale ranges (±130 µT to ±810 µT), 12-bit output, output data
 *   rates from 0.75 Hz to 220 Hz and a relative-only temperature sensor
 *
 * I²C framing differs between the two slaves:
 * - **Accelerometer** — the master sends an 8-bit sub-address: the 7 LSbs
 *   are the register address and the **MSb enables address auto-increment**.
 * - **Magnetometer** — an address pointer without an auto-increment bit; the
 *   pointer always advances by one.
 *
 * The magnetometer output order is **X, Z, Y** (not X, Y, Z) and the byte
 * order is **high byte first** — the opposite of the accelerometer.
 *
 * Default configuration (baked in at construction):
 * - Accelerometer: ±2 *g*, 100 Hz, high-resolution (12-bit), all three axes
 *   enabled, `BDU` set, high-pass filter bypassed, FIFO bypassed
 * - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion
 */
@CompileStatic
class LSM303Minimal {

    // --- Accelerometer registers -------------------------------------------
    static final int REG_CTRL1_A = 0x20
    static final int REG_CTRL2_A = 0x21
    static final int REG_CTRL3_A = 0x22
    static final int REG_CTRL4_A = 0x23
    static final int REG_CTRL5_A = 0x24
    static final int REG_CTRL6_A = 0x25
    static final int REG_REFERENCE_A = 0x26
    static final int REG_STATUS_A = 0x27
    static final int REG_OUT_X_L_A = 0x28
    static final int REG_FIFO_CTRL_A = 0x2E
    static final int REG_FIFO_SRC_A = 0x2F
    static final int REG_INT1_CFG_A = 0x30
    static final int REG_INT1_SOURCE_A = 0x31
    static final int REG_INT1_THS_A = 0x32
    static final int REG_INT1_DURATION_A = 0x33
    static final int REG_INT2_CFG_A = 0x34
    static final int REG_INT2_SOURCE_A = 0x35
    static final int REG_INT2_THS_A = 0x36
    static final int REG_INT2_DURATION_A = 0x37
    static final int REG_CLICK_CFG_A = 0x38
    static final int REG_CLICK_SRC_A = 0x39
    static final int REG_CLICK_THS_A = 0x3A
    static final int REG_TIME_LIMIT_A = 0x3B
    static final int REG_TIME_LATENCY_A = 0x3C
    static final int REG_TIME_WINDOW_A = 0x3D

    // --- Magnetometer registers --------------------------------------------
    static final int REG_CRA_M = 0x00
    static final int REG_CRB_M = 0x01
    static final int REG_MR_M = 0x02
    static final int REG_OUT_X_H_M = 0x03
    static final int REG_SR_M = 0x09
    static final int REG_IRA_M = 0x0A
    static final int REG_TEMP_OUT_H_M = 0x31

    /** Fixed I²C slave address of the accelerometer. */
    static final int I2C_ADDRESS_ACCEL = 0x19
    /** Fixed I²C slave address of the magnetometer. */
    static final int I2C_ADDRESS_MAG = 0x1E

    /** The magnetometer identity (IRA/IRB/IRC) of a real part. */
    static final byte[] MAG_IDENTITY = [0x48, 0x34, 0x33] as byte[]

    /** Bit 7 of the accelerometer sub-address enables address auto-increment. */
    static final int ACCEL_AUTO_INCREMENT = 0x80

    /** Accelerometer operating mode: low-power (8-bit output). */
    static final int MODE_LOW_POWER = 0
    /** Accelerometer operating mode: normal (10-bit output). */
    static final int MODE_NORMAL = 1
    /** Accelerometer operating mode: high-resolution (12-bit output). */
    static final int MODE_HIGH_RESOLUTION = 2

    protected final RegisterConnection accelConnection
    protected final RegisterConnection magConnection

    protected int mode = MODE_HIGH_RESOLUTION
    protected int fsG = 2
    protected double odrHz = 100.0d
    protected int magRangeUt = 130

    LSM303Minimal(RegisterConnection accelConnection, RegisterConnection magConnection) {
        this.accelConnection = accelConnection
        this.magConnection = magConnection
        init()
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * Verifies the magnetometer identity, clears the accelerometer's interrupt
     * routing, FIFO and high-pass configuration, selects ±2 *g* / 100 Hz /
     * high-resolution on the accelerometer and ±130 µT / 15 Hz / continuous
     * conversion on the magnetometer.
     */
    protected void init() {
        // Boot download of the factory trim values.
        sleepMs(5)

        byte[] identity = magRead(REG_IRA_M, 3)
        if (identity[0] != MAG_IDENTITY[0] || identity[1] != MAG_IDENTITY[1] ||
            identity[2] != MAG_IDENTITY[2]) {
            throw new IOException(String.format(
                "LSM303 magnetometer not found: expected identity 0x483433, got 0x%02X%02X%02X",
                identity[0] & 0xFF, identity[1] & 0xFF, identity[2] & 0xFF))
        }

        // Accelerometer: known state for the high-pass filter, the interrupt
        // routing and the FIFO.
        accelWrite(REG_CTRL2_A, 0x00)
        accelWrite(REG_CTRL3_A, 0x00)
        accelWrite(REG_CTRL5_A, 0x00)
        accelWrite(REG_CTRL6_A, 0x00)
        accelWrite(REG_FIFO_CTRL_A, 0x00)

        // Accelerometer: BDU = 1, ±2 g, high-resolution mode.
        accelWrite(REG_CTRL4_A, 0x88)
        // Accelerometer: 100 Hz, X/Y/Z enabled.
        accelWrite(REG_CTRL1_A, 0x57)

        // Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
        // resets into sleep mode).
        magWrite(REG_CRA_M, 0x10)
        magWrite(REG_CRB_M, 0x20)
        magWrite(REG_MR_M, 0x00)

        // Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
        sleepMs(70)
        // Magnetometer first sample: one output period = 67 ms at 15 Hz.
        sleepMs(67)

        mode = MODE_HIGH_RESOLUTION
        fsG = 2
        odrHz = 100.0d
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms delay in milliseconds
     */
    protected static void sleepMs(int ms) {
        try {
            Thread.sleep((long) ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    // --- Low level register access -----------------------------------------

    /**
     * Read bytes from an accelerometer register.
     *
     * Multi-byte reads set bit 7 of the sub-address so the accelerometer
     * auto-increments the register pointer.
     */
    protected byte[] accelRead(int reg, int length) {
        int addr = length > 1 ? (reg | ACCEL_AUTO_INCREMENT) : reg
        return accelConnection.read(addr, length)
    }

    /** Write one byte to an accelerometer register. */
    protected void accelWrite(int reg, int value) {
        accelConnection.write(reg, [(byte) value] as byte[])
    }

    /**
     * Read bytes from a magnetometer register.
     *
     * The magnetometer address pointer always auto-increments, so no
     * auto-increment bit is needed.
     */
    protected byte[] magRead(int reg, int length) {
        return magConnection.read(reg, length)
    }

    /** Write one byte to a magnetometer register. */
    protected void magWrite(int reg, int value) {
        magConnection.write(reg, [(byte) value] as byte[])
    }

    /** Read a single accelerometer register. */
    protected int accelReadReg(int reg) {
        return accelRead(reg, 1)[0] & 0xFF
    }

    /** Read a single magnetometer register. */
    protected int magReadReg(int reg) {
        return magRead(reg, 1)[0] & 0xFF
    }

    // --- Minimal API ---------------------------------------------------------

    /**
     * Read the acceleration.
     *
     * Burst-reads the six output registers so all three axes belong to the
     * same sample.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    double[] readAcceleration() {
        byte[] raw = accelRead(REG_OUT_X_L_A, 6)
        return convertAccel(raw)
    }

    /**
     * Read the magnetic flux density.
     *
     * Burst-reads the six output registers (X, Z, Y on the wire) and reorders
     * them to X, Y, Z.
     *
     * @return array of three doubles — X, Y, Z magnetic flux density in µT
     */
    double[] readMagnetic() {
        byte[] raw = magRead(REG_OUT_X_H_M, 6)
        int[] v = reorderMag(raw)
        return [(double) v[0] / magGainX() * 100.0d,
                (double) v[1] / magGainX() * 100.0d,
                (double) v[2] / magGainZ() * 100.0d] as double[]
    }

    // --- Conversion helpers ---------------------------------------------------

    /** Return the right-shift that turns the raw 16-bit pair into counts. */
    protected int shift() {
        if (mode == MODE_LOW_POWER) {
            return 8
        }
        if (mode == MODE_NORMAL) {
            return 6
        }
        return 4
    }

    /** Return the sensitivity in mg/LSB for the current mode and range. */
    protected double sensitivityMg() {
        double[] table
        if (mode == MODE_LOW_POWER) {
            table = [16.0d, 32.0d, 64.0d, 192.0d] as double[]
        } else if (mode == MODE_NORMAL) {
            table = [4.0d, 8.0d, 16.0d, 48.0d] as double[]
        } else {
            table = [1.0d, 2.0d, 4.0d, 12.0d] as double[]
        }
        int index
        switch (fsG) {
            case 4: index = 1; break
            case 8: index = 2; break
            case 16: index = 3; break
            default: index = 0; break
        }
        return table[index]
    }

    /** Convert a six-byte accelerometer burst into (x, y, z) in *g*. */
    protected double[] convertAccel(byte[] raw) {
        int s = shift()
        double sens = sensitivityMg() / 1000.0d
        int x = Register.toSigned((raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8), 16) >> s
        int y = Register.toSigned((raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8), 16) >> s
        int z = Register.toSigned((raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8), 16) >> s
        return [x * sens, y * sens, z * sens] as double[]
    }

    /** Convert a six-byte accelerometer burst into raw counts. */
    protected int[] rawAccel(byte[] raw) {
        int s = shift()
        int x = Register.toSigned((raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8), 16) >> s
        int y = Register.toSigned((raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8), 16) >> s
        int z = Register.toSigned((raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8), 16) >> s
        return [x, y, z] as int[]
    }

    /** Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z). */
    protected int[] reorderMag(byte[] raw) {
        int x = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16)
        int z = Register.toSigned(((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF), 16)
        int y = Register.toSigned(((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF), 16)
        return [x, y, z] as int[]
    }

    /** Return the X/Y gain in LSB per gauss for the current range. */
    protected double magGainX() {
        switch (magRangeUt) {
            case 190: return 855.0d
            case 250: return 670.0d
            case 400: return 450.0d
            case 470: return 400.0d
            case 560: return 330.0d
            case 810: return 230.0d
            default: return 1100.0d
        }
    }

    /** Return the Z gain in LSB per gauss for the current range. */
    protected double magGainZ() {
        switch (magRangeUt) {
            case 190: return 760.0d
            case 250: return 600.0d
            case 400: return 400.0d
            case 470: return 355.0d
            case 560: return 295.0d
            case 810: return 205.0d
            default: return 980.0d
        }
    }
}
