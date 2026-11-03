package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

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
open class LSM303Minimal constructor(
    protected val accelConnection: RegisterConnection,
    protected val magConnection: RegisterConnection
) {
    companion object {
        // --- Accelerometer registers ---------------------------------------
        const val REG_CTRL1_A = 0x20
        const val REG_CTRL2_A = 0x21
        const val REG_CTRL3_A = 0x22
        const val REG_CTRL4_A = 0x23
        const val REG_CTRL5_A = 0x24
        const val REG_CTRL6_A = 0x25
        const val REG_REFERENCE_A = 0x26
        const val REG_STATUS_A = 0x27
        const val REG_OUT_X_L_A = 0x28
        const val REG_FIFO_CTRL_A = 0x2E
        const val REG_FIFO_SRC_A = 0x2F
        const val REG_INT1_CFG_A = 0x30
        const val REG_INT1_SOURCE_A = 0x31
        const val REG_INT1_THS_A = 0x32
        const val REG_INT1_DURATION_A = 0x33
        const val REG_INT2_CFG_A = 0x34
        const val REG_INT2_SOURCE_A = 0x35
        const val REG_INT2_THS_A = 0x36
        const val REG_INT2_DURATION_A = 0x37
        const val REG_CLICK_CFG_A = 0x38
        const val REG_CLICK_SRC_A = 0x39
        const val REG_CLICK_THS_A = 0x3A
        const val REG_TIME_LIMIT_A = 0x3B
        const val REG_TIME_LATENCY_A = 0x3C
        const val REG_TIME_WINDOW_A = 0x3D

        // --- Magnetometer registers ----------------------------------------
        const val REG_CRA_M = 0x00
        const val REG_CRB_M = 0x01
        const val REG_MR_M = 0x02
        const val REG_OUT_X_H_M = 0x03
        const val REG_SR_M = 0x09
        const val REG_IRA_M = 0x0A
        const val REG_TEMP_OUT_H_M = 0x31

        /** Fixed I²C slave address of the accelerometer. */
        const val I2C_ADDRESS_ACCEL = 0x19
        /** Fixed I²C slave address of the magnetometer. */
        const val I2C_ADDRESS_MAG = 0x1E

        /** The magnetometer identity (IRA/IRB/IRC) of a real part. */
        val MAG_IDENTITY = byteArrayOf(0x48, 0x34, 0x33)

        /** Bit 7 of the accelerometer sub-address enables address auto-increment. */
        const val ACCEL_AUTO_INCREMENT = 0x80

        /** Accelerometer operating mode: low-power (8-bit output). */
        const val MODE_LOW_POWER = 0
        /** Accelerometer operating mode: normal (10-bit output). */
        const val MODE_NORMAL = 1
        /** Accelerometer operating mode: high-resolution (12-bit output). */
        const val MODE_HIGH_RESOLUTION = 2
    }

    protected var operatingMode: Int = MODE_HIGH_RESOLUTION
    protected var fsG: Int = 2
    protected var odrHz: Double = 100.0
    protected var magRangeUt: Int = 130

    init {
        init()
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * Verifies the magnetometer identity, clears the accelerometer's interrupt
     * routing, FIFO and high-pass configuration, selects ±2 *g* / 100 Hz /
     * high-resolution on the accelerometer and ±130 µT / 15 Hz / continuous
     * conversion on the magnetometer.
     *
     * @throws IOException on bus error or wrong magnetometer identity
     */
    protected fun init() {
        // Boot download of the factory trim values.
        sleepMs(5)

        val identity = magRead(REG_IRA_M, 3)
        if (identity[0] != MAG_IDENTITY[0] || identity[1] != MAG_IDENTITY[1] ||
            identity[2] != MAG_IDENTITY[2]) {
            throw IOException(
                "LSM303 magnetometer not found: expected identity 0x483433, got " +
                    "0x%02X%02X%02X".format(
                        identity[0].toInt() and 0xFF,
                        identity[1].toInt() and 0xFF,
                        identity[2].toInt() and 0xFF
                    )
            )
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

        operatingMode = MODE_HIGH_RESOLUTION
        fsG = 2
        odrHz = 100.0
    }

    /**
     * Sleep for the given number of milliseconds.
     *
     * @param ms delay in milliseconds
     */
    protected fun sleepMs(ms: Int) {
        try {
            Thread.sleep(ms.toLong())
        } catch (e: InterruptedException) {
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
    protected fun accelRead(reg: Int, length: Int): ByteArray {
        val addr = if (length > 1) (reg or ACCEL_AUTO_INCREMENT) else reg
        return accelConnection.read(addr, length)
    }

    /** Write one byte to an accelerometer register. */
    protected fun accelWrite(reg: Int, value: Int) {
        accelConnection.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    /**
     * Read bytes from a magnetometer register.
     *
     * The magnetometer address pointer always auto-increments, so no
     * auto-increment bit is needed.
     */
    protected fun magRead(reg: Int, length: Int): ByteArray =
        magConnection.read(reg, length)

    /** Write one byte to a magnetometer register. */
    protected fun magWrite(reg: Int, value: Int) {
        magConnection.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    /** Read a single accelerometer register. */
    protected fun accelReadReg(reg: Int): Int = accelRead(reg, 1)[0].toInt() and 0xFF

    /** Read a single magnetometer register. */
    protected fun magReadReg(reg: Int): Int = magRead(reg, 1)[0].toInt() and 0xFF

    // --- Minimal API ---------------------------------------------------------

    /**
     * Read the acceleration.
     *
     * Burst-reads the six output registers so all three axes belong to the
     * same sample.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*.
     */
    fun readAcceleration(): DoubleArray {
        val raw = accelRead(REG_OUT_X_L_A, 6)
        return convertAccel(raw)
    }

    /**
     * Read the magnetic flux density.
     *
     * Burst-reads the six output registers (X, Z, Y on the wire) and reorders
     * them to X, Y, Z.
     *
     * @return array of three doubles — X, Y, Z magnetic flux density in µT.
     */
    fun readMagnetic(): DoubleArray {
        val raw = magRead(REG_OUT_X_H_M, 6)
        val v = reorderMag(raw)
        return doubleArrayOf(
            v[0] / magGainX() * 100.0,
            v[1] / magGainX() * 100.0,
            v[2] / magGainZ() * 100.0
        )
    }

    // --- Conversion helpers ---------------------------------------------------

    /** Return the right-shift that turns the raw 16-bit pair into counts. */
    protected fun shift(): Int = when (operatingMode) {
        MODE_LOW_POWER -> 8
        MODE_NORMAL -> 6
        else -> 4
    }

    /** Return the sensitivity in mg/LSB for the current mode and range. */
    protected fun sensitivityMg(): Double {
        val table = when (operatingMode) {
            MODE_LOW_POWER -> doubleArrayOf(16.0, 32.0, 64.0, 192.0)
            MODE_NORMAL -> doubleArrayOf(4.0, 8.0, 16.0, 48.0)
            else -> doubleArrayOf(1.0, 2.0, 4.0, 12.0)
        }
        val index = when (fsG) {
            4 -> 1
            8 -> 2
            16 -> 3
            else -> 0
        }
        return table[index]
    }

    /** Convert a six-byte accelerometer burst into (x, y, z) in *g*. */
    protected fun convertAccel(raw: ByteArray): DoubleArray {
        val s = shift()
        val sens = sensitivityMg() / 1000.0
        val x = Register.toSigned(
            (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8), 16
        ) shr s
        val y = Register.toSigned(
            (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8), 16
        ) shr s
        val z = Register.toSigned(
            (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8), 16
        ) shr s
        return doubleArrayOf(x * sens, y * sens, z * sens)
    }

    /** Convert a six-byte accelerometer burst into raw counts. */
    protected fun rawAccel(raw: ByteArray): IntArray {
        val s = shift()
        val x = Register.toSigned(
            (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8), 16
        ) shr s
        val y = Register.toSigned(
            (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8), 16
        ) shr s
        val z = Register.toSigned(
            (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8), 16
        ) shr s
        return intArrayOf(x, y, z)
    }

    /** Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z). */
    protected fun reorderMag(raw: ByteArray): IntArray {
        val x = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16)
        val z = Register.toSigned(((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF), 16)
        val y = Register.toSigned(((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF), 16)
        return intArrayOf(x, y, z)
    }

    /** Return the X/Y gain in LSB per gauss for the current range. */
    protected fun magGainX(): Double = when (magRangeUt) {
        190 -> 855.0
        250 -> 670.0
        400 -> 450.0
        470 -> 400.0
        560 -> 330.0
        810 -> 230.0
        else -> 1100.0
    }

    /** Return the Z gain in LSB per gauss for the current range. */
    protected fun magGainZ(): Double = when (magRangeUt) {
        190 -> 760.0
        250 -> 600.0
        400 -> 400.0
        470 -> 355.0
        560 -> 295.0
        810 -> 205.0
        else -> 980.0
    }
}
