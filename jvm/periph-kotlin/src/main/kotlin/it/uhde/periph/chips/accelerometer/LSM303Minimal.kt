package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.RegisterConnection

internal val MAG_CODES = intArrayOf(0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)
internal val MAG_RANGES = intArrayOf(130, 190, 250, 400, 470, 560, 810)
internal val MAG_XY = doubleArrayOf(1100.0, 855.0, 670.0, 450.0, 400.0, 330.0, 230.0)
internal val MAG_Z = doubleArrayOf(980.0, 760.0, 600.0, 400.0, 355.0, 295.0, 205.0)
internal val MAG_RATES = doubleArrayOf(0.75, 1.5, 3.0, 7.5, 15.0, 30.0, 75.0, 220.0)
internal val MAG_RATE_BITS = intArrayOf(0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C)


/**
 * LSM303 accelerometer + magnetometer e-compass — minimal interface.
 *
 * The accelerometer is at 0x19, the magnetometer at 0x1E. Accelerometer
 * multi-byte transfers OR 0x80 into the sub-address; the magnetometer
 * pointer always auto-increments.
 */
open class LSM303Minimal(
    protected val accelConnection: RegisterConnection,
    protected val magConnection: RegisterConnection
) {
    companion object {
        protected const val REG_CTRL_REG1 = 0x20
        protected const val REG_CTRL_REG2 = 0x21
        protected const val REG_CTRL_REG3 = 0x22
        protected const val REG_CTRL_REG4 = 0x23
        protected const val REG_CTRL_REG5 = 0x24
        protected const val REG_CTRL_REG6 = 0x25
        protected const val REG_REFERENCE = 0x26
        protected const val REG_STATUS = 0x27
        protected const val REG_OUT_X_L = 0x28
        protected const val REG_OUT_X_H = 0x29
        protected const val REG_OUT_Y_L = 0x2A
        protected const val REG_OUT_Y_H = 0x2B
        protected const val REG_OUT_Z_L = 0x2C
        protected const val REG_OUT_Z_H = 0x2D
        protected const val REG_FIFO_CTRL = 0x2E
        protected const val REG_FIFO_SRC = 0x2F
        protected const val REG_INT1_CFG = 0x30
        protected const val REG_INT1_SRC = 0x31
        protected const val REG_INT1_THS = 0x32
        protected const val REG_INT1_DUR = 0x33
        protected const val REG_INT2_CFG = 0x34
        protected const val REG_INT2_SRC = 0x35
        protected const val REG_INT2_THS = 0x36
        protected const val REG_INT2_DUR = 0x37
        protected const val REG_CLICK_CFG = 0x38
        protected const val REG_CLICK_SRC = 0x39
        protected const val REG_CLICK_THS = 0x3A
        protected const val REG_TIME_LIMIT = 0x3B
        protected const val REG_TIME_LATENCY = 0x3C
        protected const val REG_TIME_WINDOW = 0x3D

        protected const val MAG_REG_CRA = 0x00
        protected const val MAG_REG_CRB = 0x01
        protected const val MAG_REG_MR = 0x02
        protected const val MAG_REG_OUT_X_H = 0x03
        protected const val MAG_REG_SR = 0x09
        protected const val MAG_REG_IRA = 0x0A
        protected const val MAG_REG_TEMP_H = 0x31
        protected const val MAG_REG_TEMP_L = 0x32

        protected const val CTRL_REG4_DEFAULT = 0x88
        protected const val CTRL_REG1_DEFAULT = 0x57
        protected const val AUTO_INCREMENT = 0x80
        protected const val BOOT_DELAY_MS = 5

        const val MODE_LOW_POWER = 0
        const val MODE_NORMAL = 1
        const val MODE_HIGH_RESOLUTION = 2

        const val RANGE_2G = 2
        const val RANGE_4G = 4
        const val RANGE_8G = 8
        const val RANGE_16G = 16

        private val RANGES = intArrayOf(2, 4, 8, 16)
        private val RATE_CODES = intArrayOf(0, 1, 10, 25, 50, 100, 200, 400, 1620, 1344, 5376)
        private val RATE_BITS = intArrayOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0x90)

    }

    protected var rangeG = RANGE_2G
    protected var operatingMode = MODE_HIGH_RESOLUTION
    protected var rateHz = 100
    protected var magGainCode = 0x20
    protected var magRateHz = 15.0

    init {
        runInitSequence()
    }

    protected fun runInitSequence() {
        sleepMs(BOOT_DELAY_MS)
        val identity = magConnection.read(MAG_REG_IRA, 3)
        if (identity[0] != 0x48.toByte() || identity[1] != 0x34.toByte() || identity[2] != 0x33.toByte()) {
            throw java.io.IOException("LSM303 magnetometer identity: expected 48 34 33")
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
        sleepMs(maxOf(settleMs(), kotlin.math.ceil(1000.0 / magRateHz).toInt()))
    }

    protected fun writeReg(reg: Int, value: Int) {
        accelConnection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun readReg(reg: Int): Int = accelConnection.read(reg, 1)[0].toInt() and 0xFF

    protected fun readBurst(reg: Int, length: Int): ByteArray =
        accelConnection.read(if (length > 1) reg or AUTO_INCREMENT else reg, length)

    protected fun magWriteReg(reg: Int, value: Int) {
        magConnection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun magReadReg(reg: Int): Int = magConnection.read(reg, 1)[0].toInt() and 0xFF

    protected fun magReadBurst(reg: Int, length: Int): ByteArray = magConnection.read(reg, length)

    protected fun settleMs(): Int {
        if (rateHz <= 0) return 0
        val period = 1000.0 / rateHz
        val turnOn = when (operatingMode) {
            MODE_LOW_POWER -> 1.0
            MODE_NORMAL -> 1.6
            else -> 7.0 * period
        }
        return kotlin.math.ceil(maxOf(turnOn, period)).toInt()
    }

    protected fun decodeAxis(low: Byte, high: Byte): Int {
        val raw = (low.toInt() and 0xFF) or ((high.toInt() and 0xFF) shl 8)
        val signed = raw.toShort().toInt()
        val shift = when (operatingMode) {
            MODE_LOW_POWER -> 8
            MODE_NORMAL -> 6
            else -> 4
        }
        return signed shr shift
    }

    protected fun sensitivityMg(): Double {
        var index = 0
        for (i in RANGES.indices) if (RANGES[i] == rangeG) index = i
        return when (operatingMode) {
            MODE_LOW_POWER -> doubleArrayOf(16.0, 32.0, 64.0, 192.0)[index]
            MODE_NORMAL -> doubleArrayOf(4.0, 8.0, 16.0, 48.0)[index]
            else -> doubleArrayOf(1.0, 2.0, 4.0, 12.0)[index]
        }
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return {x, y, z} in g
     */
    fun readAcceleration(): DoubleArray {
        val raw = readBurst(REG_OUT_X_L, 6)
        val scale = sensitivityMg() / 1000.0
        return doubleArrayOf(
            decodeAxis(raw[0], raw[1]) * scale,
            decodeAxis(raw[2], raw[3]) * scale,
            decodeAxis(raw[4], raw[5]) * scale
        )
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * @return {x, y, z} in µT
     */
    fun readMagnetic(): DoubleArray {
        val raw = magReadBurst(MAG_REG_OUT_X_H, 6)
        var gainIndex = 0
        for (i in MAG_CODES.indices) if (MAG_CODES[i] == magGainCode) gainIndex = i
        val xy = MAG_XY[gainIndex]
        val z = MAG_Z[gainIndex]
        val x = ((raw[0].toInt() and 0xFF) shl 8 or (raw[1].toInt() and 0xFF)).toShort() / xy * 100
        val zz = ((raw[2].toInt() and 0xFF) shl 8 or (raw[3].toInt() and 0xFF)).toShort() / z * 100
        val y = ((raw[4].toInt() and 0xFF) shl 8 or (raw[5].toInt() and 0xFF)).toShort() / xy * 100
        return doubleArrayOf(x, y, zz)
    }

    protected fun sleepMs(ms: Int) {
        if (ms > 0) Thread.sleep(ms.toLong())
    }

    protected fun roundHalfAway(value: Double): Int =
        if (value >= 0) kotlin.math.floor(value + 0.5).toInt() else -kotlin.math.floor(-value + 0.5).toInt()

    protected fun rateBit(rate: Int): Int {
        for (i in RATE_CODES.indices) if (RATE_CODES[i] == rate) return RATE_BITS[i]
        return -1
    }

    protected fun isLowPowerOnly(rate: Int): Boolean = rate == 1620 || rate == 5376

    protected fun isNormalHighResOnly(rate: Int): Boolean = rate == 1344
}
