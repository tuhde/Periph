package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * BMA180 — 3-axis MEMS accelerometer (Bosch Sensortec) — minimal interface.
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration is
 * required beyond the connection. Communicates over I²C at address 0x40
 * (SDO = GND) or 0x41 (SDO = VDDIO).
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (4096 LSB/g)
 * - Bandwidth 150 Hz low-pass
 * - mode_config = 00 (low-noise, factory-calibrated)
 * - 14-bit readout, shadow_dis = 0
 * - All interrupt enables left untouched
 * - Calibration bits preserved everywhere
 */
open class Bma180Minimal @JvmOverloads constructor(
    protected val connection: RegisterConnection,
) {
    protected var rangeG: Float = 2.0f
    protected var rangeBits: Int = RANGE_2G_MASK

    companion object {
        // Register map (0x00..0x3A).
        const val REG_CHIP_ID          = 0x00
        const val REG_VERSION          = 0x01
        const val REG_ACC_X_LSB        = 0x02
        const val REG_ACC_X_MSB        = 0x03
        const val REG_ACC_Y_LSB        = 0x04
        const val REG_ACC_Y_MSB        = 0x05
        const val REG_ACC_Z_LSB        = 0x06
        const val REG_ACC_Z_MSB        = 0x07
        const val REG_TEMP             = 0x08
        const val REG_STATUS_REG1      = 0x09
        const val REG_STATUS_REG2      = 0x0A
        const val REG_STATUS_REG3      = 0x0B
        const val REG_STATUS_REG4      = 0x0C
        const val REG_CTRL_REG0        = 0x0D
        const val REG_CTRL_REG1        = 0x0E
        const val REG_CTRL_REG2        = 0x0F
        const val REG_RESET            = 0x10
        const val REG_BW_TCS           = 0x20
        const val REG_CTRL_REG3        = 0x21
        const val REG_CTRL_REG4        = 0x22
        const val REG_HY               = 0x23
        const val REG_SLOPE_TAPSENS    = 0x24
        const val REG_HIGH_LOW_INFO    = 0x25
        const val REG_LOW_DUR          = 0x26
        const val REG_HIGH_DUR         = 0x27
        const val REG_TAPSENS_TH       = 0x28
        const val REG_LOW_TH           = 0x29
        const val REG_HIGH_TH          = 0x2A
        const val REG_SLOPE_TH         = 0x2B
        const val REG_CD1              = 0x2C
        const val REG_CD2              = 0x2D
        const val REG_TCO_X            = 0x2E
        const val REG_TCO_Y            = 0x2F
        const val REG_TCO_Z            = 0x30
        const val REG_GAIN_T           = 0x31
        const val REG_GAIN_X           = 0x32
        const val REG_GAIN_Y           = 0x33
        const val REG_GAIN_Z           = 0x34
        const val REG_OFFSET_LSB1      = 0x35
        const val REG_OFFSET_LSB2      = 0x36
        const val REG_OFFSET_T         = 0x37
        const val REG_OFFSET_X         = 0x38
        const val REG_OFFSET_Y         = 0x39
        const val REG_OFFSET_Z         = 0x3A

        const val CHIP_ID_VALUE = 0x03
        const val CHIP_ID_MASK  = 0x07

        const val CTRL_REG0_EE_W       = 0x10
        const val CTRL_REG0_RESET_INT  = 0x40
        const val CTRL_REG0_UPDATE_IMG = 0x20
        const val CTRL_REG0_ST0        = 0x04
        const val CTRL_REG0_SLEEP      = 0x02

        const val SOFT_RESET_CMD = 0xB6

        const val RANGE_1G_MASK   = 0x00
        const val RANGE_1_5G_MASK = 0x02
        const val RANGE_2G_MASK   = 0x04
        const val RANGE_3G_MASK   = 0x06
        const val RANGE_4G_MASK   = 0x08
        const val RANGE_8G_MASK   = 0x0A
        const val RANGE_16G_MASK  = 0x0C

        const val FULL_SCALE_1G   = 8192.0f
        const val FULL_SCALE_1_5G = 5460.0f
        const val FULL_SCALE_2G   = 4096.0f
        const val FULL_SCALE_3G   = 2730.0f
        const val FULL_SCALE_4G   = 2048.0f
        const val FULL_SCALE_8G   = 1024.0f
        const val FULL_SCALE_16G  = 512.0f

        private val BW_HZ = intArrayOf(10, 20, 40, 75, 150, 300, 600, 1200)
        private val BW_CODE = intArrayOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70)
        private val TAP_MS = intArrayOf(50, 75, 100, 150, 250, 500, 750, 1000)
        private val TAP_CODE = intArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        private val DUR_LSB_MS = 2.085f
    }

    init {
        // First transaction must NOT be an acc LSB read.
        val id = readReg(REG_CHIP_ID)
        if ((id and CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw IOException(String.format(
                "BMA180 CHIP_ID: expected 0x%02X, got 0x%02X",
                CHIP_ID_VALUE, id and CHIP_ID_MASK))
        }
        // ee_w = 1.
        val ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 or CTRL_REG0_EE_W)
        // range = ±2 g.
        val olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 and 0xF1) or RANGE_2G_MASK)
        // bw = 150 Hz.
        val bw = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (bw and 0x0F) or 0x40)
    }

    /** Read 3-axis linear acceleration in *g*. */
    @Throws(IOException::class)
    open fun read(): FloatArray {
        val raw = connection.read(REG_ACC_X_LSB, 6)
        val rx = Register.toSigned(((raw[1].toInt() and 0xFF) shl 6) or ((raw[0].toInt() and 0xFF) shr 2), 14)
        val ry = Register.toSigned(((raw[3].toInt() and 0xFF) shl 6) or ((raw[2].toInt() and 0xFF) shr 2), 14)
        val rz = Register.toSigned(((raw[5].toInt() and 0xFF) shl 6) or ((raw[4].toInt() and 0xFF) shr 2), 14)
        val scale: Float = when {
            rangeG == 1.0f -> FULL_SCALE_1G
            rangeG == 1.5f -> FULL_SCALE_1_5G
            rangeG == 2.0f -> FULL_SCALE_2G
            rangeG == 3.0f -> FULL_SCALE_3G
            rangeG == 4.0f -> FULL_SCALE_4G
            rangeG == 8.0f -> FULL_SCALE_8G
            rangeG == 16.0f -> FULL_SCALE_16G
            else -> FULL_SCALE_2G
        }
        return floatArrayOf(rx / scale, ry / scale, rz / scale)
    }

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf((value and 0xFF).toByte()))
    }

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int {
        return connection.read(reg, 1)[0].toInt() and 0xFF
    }
}