package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * LSM303DLHC e-compass module: 3-axis MEMS accelerometer plus 3-axis magnetometer
 * behind two independent I²C slaves (STMicroelectronics), minimal interface.
 *
 * The module is one logical device with two connections, the same pattern as `MPU9250`:
 * the accelerometer is on [accelConnection] (fixed address 0x19) and the magnetometer on
 * [magConnection] (fixed address 0x1E). Both must be on the same bus. Each connection is a
 * [RegisterConnection] with 1-byte register addresses.
 *
 * The two slaves frame sub-addresses differently: the accelerometer ORs bit 7 into the
 * sub-address for multi-byte transfers (auto-increment), while the magnetometer's address
 * pointer always auto-increments and needs no such bit. The driver handles both itself.
 *
 * Initialisation verifies the magnetometer identity (`0x48 0x34 0x33`, ASCII "H43"; the
 * accelerometer has no identity register), then writes every control register explicitly.
 * Defaults:
 * - Accelerometer: ±2 g, 100 Hz, high-resolution (12-bit, 1 mg/LSB), X/Y/Z enabled, BDU=1,
 *   high-pass bypassed, FIFO bypassed, interrupts unrouted
 * - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz, continuous conversion, temperature sensor off
 *
 * The datasheet revision committed with this driver does not document the normal /
 * low-power resolution split; the mode-dependent shifts and sensitivities follow the LIS3DH
 * sibling and are unverified on hardware.
 *
 * @property accelConnection register connection to the accelerometer (address 0x19)
 * @property magConnection register connection to the magnetometer (address 0x1E)
 * @throws IOException on bus error or if the magnetometer identity does not match
 */
open class LSM303Minimal @JvmOverloads @Throws(IOException::class) constructor(
    protected val accelConnection: RegisterConnection,
    protected val magConnection: RegisterConnection
) {

    /** Accelerometer full-scale code (0 ±2 g, 1 ±4 g, 2 ±8 g, 3 ±16 g). */
    protected var accelFs: Int = 0

    /** Accelerometer mode (0 low-power, 1 normal, 2 high-resolution). */
    protected var accelMode: Int = 2

    /** Accelerometer output data rate in Hz (0 = power-down). */
    protected var odrHz: Int = 100

    /** Magnetometer X/Y gain in LSB/gauss. */
    protected var magGainXy: Double = 1100.0

    /** Magnetometer Z gain in LSB/gauss. */
    protected var magGainZ: Double = 980.0

    /** Magnetometer full scale in µT. */
    protected var magRangeUt: Int = 130

    init {
        sleepMs(5)

        val id = magConnection.read(REG_IRA_REG_M, 3)
        for (i in 0 until 3) {
            if ((id[i].toInt() and 0xFF) != MAG_ID[i]) {
                throw IOException(String.format(
                    "LSM303 magnetometer identity: expected 0x483433, got 0x%02X%02X%02X",
                    id[0].toInt() and 0xFF, id[1].toInt() and 0xFF, id[2].toInt() and 0xFF))
            }
        }

        accelWrite(REG_CTRL_REG2_A, 0x00)
        accelWrite(REG_CTRL_REG3_A, 0x00)
        accelWrite(REG_CTRL_REG5_A, 0x00)
        accelWrite(REG_CTRL_REG6_A, 0x00)
        accelWrite(REG_FIFO_CTRL_REG_A, 0x00)
        accelWrite(REG_CTRL_REG4_A, 0x88)  // BDU=1, FS=±2 g, HR=1
        accelWrite(REG_CTRL_REG1_A, 0x57)  // ODR=100 Hz, LPen=0, X/Y/Z enabled

        magWrite(REG_CRA_REG_M, 0x10)      // 15 Hz, temperature off
        magWrite(REG_CRB_REG_M, 0x20)      // ±1.3 gauss
        magWrite(REG_MR_REG_M, 0x00)       // continuous conversion (reset value is sleep)

        sleepMs(70)
    }

    /**
     * Read 3-axis acceleration.
     *
     * Burst-reads `OUT_X_L_A`…`OUT_Z_H_A` in one transaction so the three axes come from the
     * same sample. Left-justified 16-bit data is sign-extended and shifted by the current mode.
     *
     * @return array `[x, y, z]` in *g*
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readAcceleration(): DoubleArray = convertAccel(accelConnection.read(REG_OUT_X_L_A or ACCEL_AUTO_INC, 6))

    /**
     * Read 3-axis magnetic flux density.
     *
     * Burst-reads `OUT_X_H_M`…`OUT_Y_L_M` (six bytes, high byte first), reorders the wire order
     * X, Z, Y into X, Y, Z, and converts to µT with the per-axis gain (Z uses its own gain).
     *
     * @return array `[x, y, z]` in µT
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readMagnetic(): DoubleArray {
        val c = readMagCounts()
        return doubleArrayOf(
            c[0] / magGainXy * 100.0,
            c[1] / magGainXy * 100.0,
            c[2] / magGainZ * 100.0
        )
    }

    /** Convert a 6-byte accelerometer burst (X, Y, Z, low byte first) to *g*. */
    protected fun convertAccel(raw: ByteArray): DoubleArray {
        val c = accelCounts(raw)
        val mgPerLsb = ACCEL_MG_PER_LSB[accelMode][accelFs].toDouble()
        return doubleArrayOf(c[0] * mgPerLsb / 1000.0, c[1] * mgPerLsb / 1000.0, c[2] * mgPerLsb / 1000.0)
    }

    /** Convert a 6-byte accelerometer burst to signed counts at the current resolution. */
    protected fun accelCounts(raw: ByteArray): IntArray {
        val shift = ACCEL_SHIFT[accelMode]
        return IntArray(3) { i ->
            val v = Register.toSigned((raw[2 * i].toInt() and 0xFF) or ((raw[2 * i + 1].toInt() and 0xFF) shl 8), 16)
            v shr shift
        }
    }

    /** Read the six magnetometer output bytes and return signed counts in X, Y, Z order. */
    @Throws(IOException::class)
    protected fun readMagCounts(): IntArray {
        val raw = magConnection.read(REG_OUT_X_H_M, 6)
        val x = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16)
        val z = Register.toSigned(((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF), 16)
        val y = Register.toSigned(((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF), 16)
        return intArrayOf(x, y, z)
    }

    /** Full scale in *g* for the current FS code (2, 4, 8 or 16). */
    protected fun fullScaleG(): Int = 2 shl accelFs

    /** 7-bit threshold code for a threshold in *g* (1 LSb = full scale / 128). */
    protected fun thresholdCode(thresholdG: Double): Int =
        clamp(Math.round(thresholdG * 128.0 / fullScaleG()).toInt(), 0, 127)

    /** Convert a time in ms to a count of accelerometer output periods (1 LSb = 1/ODR), clamped to [max]. */
    protected fun timeCode(timeMs: Double, max: Int): Int =
        clamp(Math.round(timeMs * odrHz / 1000.0).toInt(), 0, max)

    /** Write one accelerometer register. */
    @Throws(IOException::class)
    protected fun accelWrite(reg: Int, value: Int) {
        accelConnection.write(reg, byteArrayOf(value.toByte()))
    }

    /** Read one accelerometer register. */
    @Throws(IOException::class)
    protected fun accelRead(reg: Int): Int = accelConnection.read(reg, 1)[0].toInt() and 0xFF

    /** Read-modify-write: replace the bits in [mask] with `value and mask`. */
    @Throws(IOException::class)
    protected fun accelUpdate(reg: Int, mask: Int, value: Int) {
        val r = accelRead(reg)
        accelWrite(reg, (r and mask.inv()) or (value and mask))
    }

    /** Write one magnetometer register. */
    @Throws(IOException::class)
    protected fun magWrite(reg: Int, value: Int) {
        magConnection.write(reg, byteArrayOf(value.toByte()))
    }

    /** Read one magnetometer register. */
    @Throws(IOException::class)
    protected fun magRead(reg: Int): Int = magConnection.read(reg, 1)[0].toInt() and 0xFF

    /** Read-modify-write on a magnetometer register. */
    @Throws(IOException::class)
    protected fun magUpdate(reg: Int, mask: Int, value: Int) {
        val r = magRead(reg)
        magWrite(reg, (r and mask.inv()) or (value and mask))
    }

    /** Sleep without propagating interruption; restores the interrupt flag. */
    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        internal const val REG_CTRL_REG1_A = 0x20
        internal const val REG_CTRL_REG2_A = 0x21
        internal const val REG_CTRL_REG3_A = 0x22
        internal const val REG_CTRL_REG4_A = 0x23
        internal const val REG_CTRL_REG5_A = 0x24
        internal const val REG_CTRL_REG6_A = 0x25
        internal const val REG_REFERENCE_A = 0x26
        internal const val REG_STATUS_REG_A = 0x27
        internal const val REG_OUT_X_L_A = 0x28
        internal const val REG_FIFO_CTRL_REG_A = 0x2E
        internal const val REG_FIFO_SRC_REG_A = 0x2F
        internal const val REG_INT1_CFG_A = 0x30
        internal const val REG_INT1_SOURCE_A = 0x31
        internal const val REG_INT1_THS_A = 0x32
        internal const val REG_INT1_DURATION_A = 0x33
        internal const val REG_INT2_CFG_A = 0x34
        internal const val REG_INT2_SOURCE_A = 0x35
        internal const val REG_INT2_THS_A = 0x36
        internal const val REG_INT2_DURATION_A = 0x37
        internal const val REG_CLICK_CFG_A = 0x38
        internal const val REG_CLICK_SRC_A = 0x39
        internal const val REG_CLICK_THS_A = 0x3A
        internal const val REG_TIME_LIMIT_A = 0x3B
        internal const val REG_TIME_LATENCY_A = 0x3C
        internal const val REG_TIME_WINDOW_A = 0x3D

        internal const val REG_CRA_REG_M = 0x00
        internal const val REG_CRB_REG_M = 0x01
        internal const val REG_MR_REG_M = 0x02
        internal const val REG_OUT_X_H_M = 0x03
        internal const val REG_SR_REG_M = 0x09
        internal const val REG_IRA_REG_M = 0x0A
        internal const val REG_TEMP_OUT_H_M = 0x31

        /** Bit 7 of the accelerometer sub-address: auto-increment for multi-byte transfers. */
        internal const val ACCEL_AUTO_INC = 0x80

        /** Magnetometer identity bytes IRA, IRB, IRC ("H43"). */
        internal val MAG_ID = intArrayOf(0x48, 0x34, 0x33)

        /** Accelerometer right-shift per mode (index: 0 low-power, 1 normal, 2 high-resolution). */
        internal val ACCEL_SHIFT = intArrayOf(8, 6, 4)

        /** Accelerometer mg/LSB, indexed [mode][FS code] (FS code: 0 ±2 g … 3 ±16 g). */
        internal val ACCEL_MG_PER_LSB = arrayOf(
            intArrayOf(16, 32, 64, 192),  // low-power (8-bit)
            intArrayOf(4, 8, 16, 48),     // normal (10-bit)
            intArrayOf(1, 2, 4, 12)       // high-resolution (12-bit)
        )

        /** Magnetometer X/Y gain in LSB/gauss, indexed by GN code − 1 (codes 001…111). */
        internal val MAG_GAIN_XY = intArrayOf(1100, 855, 670, 450, 400, 330, 230)

        /** Magnetometer Z gain in LSB/gauss, indexed by GN code − 1 (Z differs from X/Y). */
        internal val MAG_GAIN_Z = intArrayOf(980, 760, 600, 400, 355, 295, 205)

        /** Magnetometer full scale in µT, indexed by GN code − 1. */
        internal val MAG_RANGE_UT = intArrayOf(130, 190, 250, 400, 470, 560, 810)

        /** Clamp [v] into `[lo, hi]`. */
        internal fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    }
}
