package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * LSM303DLHC — 3-axis accelerometer + 3-axis magnetometer e-compass
 * (STMicroelectronics) — minimal interface.
 *
 * System-in-package combining a three-axis MEMS accelerometer (I²C address
 * 0x19) and a three-axis magnetometer (I²C address 0x1E) behind two independent
 * I²C slave addresses on one bus. The driver treats the module as one logical
 * device with two connections; the compass heading is computed in software
 * from both sensors (see [LSM303Full]). Same two-connection pattern as
 * MPU9250.
 *
 * Default configuration (written at construction):
 * - Accelerometer: ±2 g (1 mg/LSB), 100 Hz ODR, high-resolution mode,
 *   all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
 *   all interrupt routing cleared
 * - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
 *   temperature sensor off
 *
 * The two slaves use different I²C framing: the accelerometer sub-address
 * bit 7 enables address auto-increment (the driver sets it for every
 * multi-byte transfer); the magnetometer has an address pointer that always
 * auto-increments and needs no extra bit. Magnetometer outputs are X, Z, Y
 * with the high byte first — the driver reorders to X, Y, Z. The
 * accelerometer outputs are X, Y, Z low byte first.
 */
open class LSM303Minimal @Throws(IOException::class) constructor(
    protected val accelConn: RegisterConnection,
    protected val magConn: RegisterConnection
) {
    init {
        // Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
        sleepMs(5)

        val identity = magConn.read(REG_IRA_M, 3)
        if ((identity[0].toInt() and 0xFF) != 0x48 || (identity[1].toInt() and 0xFF) != 0x34 || (identity[2].toInt() and 0xFF) != 0x33) {
            throw IOException(
                "LSM303 magnetometer ID: expected 0x483433, got 0x"
                        + hex2(identity[0]) + hex2(identity[1]) + hex2(identity[2])
            )
        }

        // Accelerometer: no soft reset — write the control registers to known values.
        accelWrite(REG_CTRL2_A, 0x00)
        accelWrite(REG_CTRL3_A, 0x00)
        accelWrite(REG_CTRL5_A, 0x00)
        accelWrite(REG_CTRL6_A, 0x00)
        accelWrite(REG_FIFO_CTRL_A, 0x00)
        // BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
        accelWrite(REG_CTRL4_A, 0x88)
        // ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
        accelWrite(REG_CTRL1_A, 0x57)

        // Magnetometer: the part resets into sleep mode, so write all three
        // control registers explicitly (15 Hz, ±1.3 gauss, continuous).
        magWrite(REG_CRA_M, 0x10)
        magWrite(REG_CRB_M, 0x20)
        magWrite(REG_MR_M, 0x00)

        // Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
        // output period (67 ms at 15 Hz) for the first magnetometer sample.
        sleepMs(70)
        sleepMs(67)
    }

    // Configuration cached for conversion validation.
    /** Full-scale bits 0..3 (±2/4/8/16 g). */
    protected var rangeBits: Int = 0

    /** Operating mode: 0 low-power, 1 normal, 2 high-resolution.
     *
     * Named `powerMode` in Kotlin to avoid the JVM-signature clash between
     * the property setter and the [LSM303Full.setMode] method.
     */
    protected var powerMode: Int = 2

    /** Accelerometer output data rate in Hz. */
    protected var odrHz: Double = 100.0

    /** Magnetometer gain index 0..6. */
    protected var magGn: Int = 0

    /** Magnetometer output data rate in Hz. */
    protected var magOdrHz: Double = 15.0

    /** True once TEMP_EN has been set. */
    protected var tempEnabled: Boolean = false

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so
     * all six bytes belong to one sample (BDU=1).
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    @Throws(IOException::class)
    fun readAcceleration(): DoubleArray {
        val raw = accelConn.read(REG_OUT_X_L_A or 0x80, 6)
        val c = convertAccelSample(raw)
        val scale = accelSensitivityG()
        return doubleArrayOf(c[0] * scale, c[1] * scale, c[2] * scale)
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z, Y,
     * each high byte first) in one transaction and reorders to X, Y, Z. Z uses
     * a different gain (LSB/gauss) from X and Y at every range setting.
     *
     * @return array of three doubles — X, Y, Z magnetic flux density in µT
     */
    @Throws(IOException::class)
    fun readMagnetic(): DoubleArray {
        val raw = magConn.read(REG_OUT_X_H_M, 6)
        val mx = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16)
        val mz = Register.toSigned(((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF), 16)
        val my = Register.toSigned(((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF), 16)
        val gainXY = MAG_GAIN_XY[magGn]
        val gainZ = MAG_GAIN_Z[magGn]
        return doubleArrayOf(mx / gainXY * 100.0, my / gainXY * 100.0, mz / gainZ * 100.0)
    }

    /**
     * Convert a 6-byte accelerometer burst to (x, y, z) signed counts at the
     * current mode's resolution (sign-extend the 16-bit pair first, then
     * arithmetic-shift right).
     */
    protected fun convertAccelSample(raw: ByteArray): IntArray {
        val rx = Register.toSigned((raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8), 16)
        val ry = Register.toSigned((raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8), 16)
        val rz = Register.toSigned((raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8), 16)
        val shift = ACCEL_SHIFT[powerMode]
        return intArrayOf(rx shr shift, ry shr shift, rz shr shift)
    }

    /** Sensitivity in g/LSB at the current mode and range. */
    protected fun accelSensitivityG(): Double {
        return ACCEL_SENSITIVITY_MG[powerMode][rangeBits] / 1000.0
    }

    @Throws(IOException::class)
    protected fun accelRead(reg: Int): Int {
        return accelConn.read(reg, 1)[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun accelWrite(reg: Int, value: Int) {
        accelConn.write(reg, byteArrayOf(value.toByte()))
    }

    @Throws(IOException::class)
    protected fun magRead(reg: Int): Int {
        return magConn.read(reg, 1)[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun magWrite(reg: Int, value: Int) {
        magConn.write(reg, byteArrayOf(value.toByte()))
    }

    /** Sleep the given time without propagating interrupts. */
    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        // Accelerometer register map (sub-address bit 7 = auto-increment).
        const val REG_CTRL1_A      = 0x20
        const val REG_CTRL2_A      = 0x21
        const val REG_CTRL3_A      = 0x22
        const val REG_CTRL4_A      = 0x23
        const val REG_CTRL5_A      = 0x24
        const val REG_CTRL6_A      = 0x25
        const val REG_REFERENCE_A  = 0x26
        const val REG_STATUS_A     = 0x27
        const val REG_OUT_X_L_A    = 0x28
        const val REG_FIFO_CTRL_A  = 0x2E
        const val REG_FIFO_SRC_A   = 0x2F
        const val REG_INT1_CFG_A   = 0x30
        const val REG_INT1_SRC_A   = 0x31
        const val REG_INT1_THS_A   = 0x32
        const val REG_INT1_DUR_A   = 0x33
        const val REG_INT2_CFG_A   = 0x34
        const val REG_INT2_SRC_A   = 0x35
        const val REG_INT2_THS_A   = 0x36
        const val REG_INT2_DUR_A   = 0x37
        const val REG_CLICK_CFG_A  = 0x38
        const val REG_CLICK_SRC_A  = 0x39
        const val REG_CLICK_THS_A  = 0x3A
        const val REG_TIME_LIMIT_A = 0x3B
        const val REG_TIME_LAT_A   = 0x3C
        const val REG_TIME_WIN_A   = 0x3D

        // Magnetometer register map (address pointer auto-increments; no auto-inc bit).
        const val REG_CRA_M     = 0x00
        const val REG_CRB_M     = 0x01
        const val REG_MR_M      = 0x02
        const val REG_OUT_X_H_M = 0x03
        const val REG_SR_M      = 0x09
        const val REG_IRA_M     = 0x0A
        const val REG_TEMP_H_M  = 0x31

        /** STATUS_REG_A bit 3 (0x08): new XYZ data available. */
        const val STATUS_ZYXDA = 0x08

        // Sensitivity in mg/LSB, indexed [mode][range]; mode 0 = low-power (8-bit),
        // 1 = normal (10-bit), 2 = high-resolution (12-bit); range 0..3 = ±2/4/8/16 g.
        private val ACCEL_SENSITIVITY_MG = arrayOf(
            doubleArrayOf(16.0, 32.0, 64.0, 192.0),
            doubleArrayOf(4.0, 8.0, 16.0, 48.0),
            doubleArrayOf(1.0, 2.0, 4.0, 12.0)
        )

        // Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
        private val ACCEL_SHIFT = intArrayOf(8, 6, 4)

        // Full scale in g by range bits 0..3.
        private val ACCEL_FULL_SCALE_G = doubleArrayOf(2.0, 4.0, 8.0, 16.0)

        // Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for XY and Z.
        private val MAG_FULL_SCALE_UT = doubleArrayOf(130.0, 190.0, 250.0, 400.0, 470.0, 560.0, 810.0)
        internal val MAG_GAIN_XY = doubleArrayOf(1100.0, 855.0, 670.0, 450.0, 400.0, 330.0, 230.0)
        internal val MAG_GAIN_Z = doubleArrayOf(980.0, 760.0, 600.0, 400.0, 355.0, 295.0, 205.0)

        internal fun fullScaleG(rangeBits: Int): Double = ACCEL_FULL_SCALE_G[rangeBits]

        private fun hex2(b: Byte): String = String.format("%02X", b.toInt() and 0xFF)
    }
}
