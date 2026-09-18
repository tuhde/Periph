package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.Connection

/**
 * L3GD20H (and L3GD20) three-axis MEMS gyroscope — minimal driver.
 *
 * Provides angular rate readings on the X, Y, and Z axes with no
 * configuration beyond the connection. I²C address is 0x6A (SA0/SDO=GND) or
 * 0x6B (SA0/SDO=VCC). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 * Default configuration: 95 Hz ODR, default bandwidth, ±250 dps
 * full scale, BDU=1, all axes enabled, 250 ms startup delay.
 *
 * @param connection I²C connection bound to the chip.
 * @param spi true for SPI bus, false for I²C.
 */
@JvmOverloads
open class L3gd20hMinimal @JvmOverloads constructor(
    protected val connection: Connection,
    spi: Boolean = false
) {
    companion object {
        const val REG_WHO_AM_I      = 0x0F
        const val REG_CTRL_REG1     = 0x20
        const val REG_CTRL_REG2     = 0x21
        const val REG_CTRL_REG3     = 0x22
        const val REG_CTRL_REG4     = 0x23
        const val REG_CTRL_REG5     = 0x24
        const val REG_OUT_TEMP      = 0x26
        const val REG_STATUS        = 0x27
        const val REG_OUT_X_L       = 0x28
        const val REG_OUT_X_H       = 0x29
        const val REG_OUT_Y_L       = 0x2A
        const val REG_OUT_Y_H       = 0x2B
        const val REG_OUT_Z_L       = 0x2C
        const val REG_OUT_Z_H       = 0x2D
        const val REG_FIFO_CTRL     = 0x2E
        const val REG_FIFO_SRC      = 0x2F
        const val REG_INT1_CFG      = 0x30
        const val REG_INT1_SRC      = 0x31
        const val REG_INT1_TSH_XH   = 0x32
        const val REG_INT1_TSH_XL   = 0x33
        const val REG_INT1_TSH_YH   = 0x34
        const val REG_INT1_TSH_YL   = 0x35
        const val REG_INT1_TSH_ZH   = 0x36
        const val REG_INT1_TSH_ZL   = 0x37
        const val REG_INT1_DURATION = 0x38

        const val WHO_AM_I_L3GD20  = 0xD4
        const val WHO_AM_I_L3GD20H = 0xD7
        const val CTRL_REG1_DEFAULT = 0x0F
        const val CTRL_REG4_DEFAULT = 0x80

        private fun sensitivity(fullScale: Int): Float {
            return when (fullScale) {
                250  -> 8.75e-3f
                500  -> 17.5e-3f
                2000 -> 70.0e-3f
                else -> 8.75e-3f
            }
        }

        private fun int16Le(data: ByteArray, offset: Int): Short {
            val v = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            return v.toShort()
        }
    }

    protected val spi: Boolean = spi
    protected var fullScale: Int = 250

    init {
        val id = connection.writeRead(byteArrayOf(REG_WHO_AM_I.toByte()), 1)
        val who = id[0].toInt() and 0xFF
        if (who != WHO_AM_I_L3GD20 && who != WHO_AM_I_L3GD20H) {
            throw IOException("L3GD20H not found: WHO_AM_I expected 0xD4 or 0xD7, got 0x${who.toString(16)}")
        }
        connection.write(byteArrayOf(REG_CTRL_REG4.toByte(), CTRL_REG4_DEFAULT.toByte()))
        connection.write(byteArrayOf(REG_CTRL_REG1.toByte(), CTRL_REG1_DEFAULT.toByte()))
        Thread.sleep(250)
    }

    protected fun writeReg(reg: Int, value: Int) {
        val addr = if (spi) reg and 0x3F else reg
        connection.write(byteArrayOf(addr.toByte(), (value and 0xFF).toByte()))
    }

    /**
     * Read angular rate on all three axes as a single burst transaction.
     *
     * Burst-reads OUT_X_L through OUT_Z_H (registers 0x28–0x2D, 6 bytes,
     * little-endian), unpacks the three signed 16-bit values, and converts
     * them to rad/s using the current full-scale sensitivity.
     *
     * @return FloatArray {x, y, z} angular rates in rad/s.
     */
    fun gyro(): FloatArray {
        val raw = if (spi) {
            connection.write(byteArrayOf((REG_OUT_X_L or 0xC0).toByte()))
            connection.read(6)
        } else {
            connection.writeRead(byteArrayOf((REG_OUT_X_L or 0x80).toByte()), 6)
        }
        val sens = sensitivity(fullScale)
        val k = Math.PI.toFloat() / 180.0f
        return floatArrayOf(
            int16Le(raw, 0) * sens * k,
            int16Le(raw, 2) * sens * k,
            int16Le(raw, 4) * sens * k
        )
    }
}