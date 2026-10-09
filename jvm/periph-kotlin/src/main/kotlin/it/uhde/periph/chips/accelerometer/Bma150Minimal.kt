package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BMA150 — 3-axis MEMS accelerometer (Bosch Sensortec) — minimal interface.
 *
 * Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
 * selectable full-scale range. Communicates over I²C at the fixed address
 * 0x38.
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (256 LSB/g)
 * - Bandwidth 100 Hz
 * - Calibration bits 7:5 of `RANGE_BW` (0x14) preserved
 * - `shadow_dis` = 0 (LSB-then-MSB ordering enforced)
 */
open class Bma150Minimal @JvmOverloads constructor(
    protected val conn: RegisterConnection
) {
    companion object {
        const val REG_CHIP_ID          = 0x00
        const val REG_VERSION          = 0x01
        const val REG_ACC_X_LSB        = 0x02
        const val REG_ACC_X_MSB        = 0x03
        const val REG_ACC_Y_LSB        = 0x04
        const val REG_ACC_Y_MSB        = 0x05
        const val REG_ACC_Z_LSB        = 0x06
        const val REG_ACC_Z_MSB        = 0x07
        const val REG_TEMP             = 0x08
        const val REG_STATUS           = 0x09
        const val REG_CTRL             = 0x0A
        const val REG_INT_CTRL         = 0x0B
        const val REG_LG_THRES         = 0x0C
        const val REG_LG_DUR           = 0x0D
        const val REG_HG_THRES         = 0x0E
        const val REG_HG_DUR           = 0x0F
        const val REG_ANY_MOTION_THRES = 0x10
        const val REG_HYST_DUR         = 0x11
        const val REG_CUSTOMER_1       = 0x12
        const val REG_CUSTOMER_2       = 0x13
        const val REG_RANGE_BW         = 0x14
        const val REG_CONFIG           = 0x15

        const val CHIP_ID_VALUE = 0x02
        const val CHIP_ID_MASK  = 0x07

        const val RANGE_2G_MASK = 0x00
        const val RANGE_4G_MASK = 0x08
        const val RANGE_8G_MASK = 0x10

        const val BW_25    = 0x00
        const val BW_50    = 0x01
        const val BW_100   = 0x02
        const val BW_190   = 0x03
        const val BW_375   = 0x04
        const val BW_750   = 0x05
        const val BW_1500  = 0x06

        const val FULL_SCALE_2G = 256.0f
        const val FULL_SCALE_4G = 128.0f
        const val FULL_SCALE_8G = 64.0f
    }

    protected var rangeG: Int = 2

    init {
        val chipId = readReg(REG_CHIP_ID)
        if ((chipId and CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw IOException("BMA150 CHIP_ID: expected 0x${Integer.toHexString(CHIP_ID_VALUE)}, got 0x${Integer.toHexString(chipId and CHIP_ID_MASK)}")
        }
        val rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb and 0xE0) or RANGE_2G_MASK or BW_100)
    }

    protected fun writeReg(reg: Int, value: Int) {
        conn.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    protected fun readReg(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF

    protected fun readBurst(reg: Int, n: Int): ByteArray = conn.read(reg, n)

    /**
     * Read 3-axis linear acceleration in *g*.
     *
     * @return Triple of (x, y, z) acceleration in *g*.
     */
    fun read(): DoubleArray {
        val raw = readBurst(REG_ACC_X_LSB, 6)
        val rx = Register.toSigned(((raw[1].toInt() and 0xFF) shl 2) or ((raw[0].toInt() and 0xC0) shr 6), 10)
        val ry = Register.toSigned(((raw[3].toInt() and 0xFF) shl 2) or ((raw[2].toInt() and 0xC0) shr 6), 10)
        val rz = Register.toSigned(((raw[5].toInt() and 0xFF) shl 2) or ((raw[4].toInt() and 0xC0) shr 6), 10)
        val scale = when (rangeG) {
            4 -> FULL_SCALE_4G
            8 -> FULL_SCALE_8G
            else -> FULL_SCALE_2G
        }
        return doubleArrayOf(rx / scale, ry / scale, rz / scale)
    }
}
