package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * BMP384 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * Reads temperature and pressure via I²C using Bosch's floating-point
 * compensation algorithm. Calibration coefficients are loaded from the chip's
 * NVM during construction. The chip ID register is verified to be 0x50.
 *
 * Default settings: osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz, normal mode.
 */
open class Bmp384Minimal @JvmOverloads constructor(
    protected val connection: Connection,
    addr: Int = 0x76
) {
    companion object {
        const val REG_CHIP_ID   = 0x00
        const val REG_STATUS    = 0x03
        const val REG_DATA_0    = 0x04
        const val REG_PWR_CTRL  = 0x1B
        const val REG_OSR       = 0x1C
        const val REG_ODR       = 0x1D
        const val REG_CONFIG    = 0x1F
        const val REG_CMD       = 0x7E
        const val REG_CAL_START = 0x31
        const val REG_CAL_LEN   = 21

        const val CHIP_ID        = 0x50
        const val SOFT_RESET_CMD = 0xB6
        const val FIFO_FLUSH_CMD = 0xB0

        const val MODE_SLEEP  = 0x00
        const val MODE_FORCED = 0x01
        const val MODE_NORMAL = 0x03

        const val PWR_PRESS_EN = 0x01
        const val PWR_TEMP_EN  = 0x02
    }

    /** Pressure oversampling index (0–5). */
    protected var osrP: Int = 4
    /** Temperature oversampling index (0–5). */
    protected var osrT: Int = 1
    /** IIR filter coefficient index (0–7). */
    protected var iir: Int = 2
    /** Output data rate selector (0x00–0x11). */
    protected var odr: Int = 0x03
    /** Power-mode bits in PWR_CTRL. */
    protected var powerMode: Int = MODE_NORMAL

    protected var parT1: Double = 0.0
    protected var parT2: Double = 0.0
    protected var parT3: Double = 0.0
    protected var parP1: Double = 0.0
    protected var parP2: Double = 0.0
    protected var parP3: Double = 0.0
    protected var parP4: Double = 0.0
    protected var parP5: Double = 0.0
    protected var parP6: Double = 0.0
    protected var parP7: Double = 0.0
    protected var parP8: Double = 0.0
    protected var parP9: Double = 0.0
    protected var parP10: Double = 0.0
    protected var parP11: Double = 0.0

    /** Compensated temperature cached for the pressure compensation. */
    protected var tLin: Double = 0.0

    init {
        val id = connection.writeRead(byteArrayOf(REG_CHIP_ID.toByte()), 1)
        if ((id[0].toInt() and 0xFF) != CHIP_ID) {
            throw IOException(
                "BMP384 not found: expected 0x50, got 0x%02X".format(id[0].toInt() and 0xFF)
            )
        }
        readCalibration()
        applyConfig()
    }

    /** Read and unpack the 21-byte calibration block from NVM (0x31–0x45). */
    protected fun readCalibration() {
        val cal = connection.writeRead(byteArrayOf(REG_CAL_START.toByte()), REG_CAL_LEN)

        val nvmT1  = readU16LE(cal, 0)
        val nvmT2  = readU16LE(cal, 2)
        val nvmT3  = readS8(cal, 4)
        val nvmP1  = readS16LE(cal, 5)
        val nvmP2  = readS16LE(cal, 7)
        val nvmP3  = readS8(cal, 9)
        val nvmP4  = readS8(cal, 10)
        val nvmP5  = readU16LE(cal, 11)
        val nvmP6  = readU16LE(cal, 13)
        val nvmP7  = readS8(cal, 15)
        val nvmP8  = readS8(cal, 16)
        val nvmP9  = readS16LE(cal, 17)
        val nvmP10 = readS8(cal, 19)
        val nvmP11 = readS8(cal, 20)

        parT1  = nvmT1  * 256.0
        parT2  = nvmT2  / Math.pow(2.0, 30.0)
        parT3  = nvmT3  / Math.pow(2.0, 48.0)
        parP1  = (nvmP1  - Math.pow(2.0, 14.0)) / Math.pow(2.0, 20.0)
        parP2  = (nvmP2  - Math.pow(2.0, 14.0)) / Math.pow(2.0, 29.0)
        parP3  = nvmP3  / Math.pow(2.0, 32.0)
        parP4  = nvmP4  / Math.pow(2.0, 37.0)
        parP5  = nvmP5  * 8.0
        parP6  = nvmP6  / Math.pow(2.0, 6.0)
        parP7  = nvmP7  / Math.pow(2.0, 8.0)
        parP8  = nvmP8  / Math.pow(2.0, 15.0)
        parP9  = nvmP9  / Math.pow(2.0, 48.0)
        parP10 = nvmP10 / Math.pow(2.0, 48.0)
        parP11 = nvmP11 / Math.pow(2.0, 65.0)
    }

    /** Write OSR, CONFIG, ODR, and PWR_CTRL with the currently-cached settings. */
    protected fun applyConfig() {
        val osrReg = (osrT shl 3) or (osrP shl 0)
        val configReg = (iir shl 1)
        val pwrReg = (powerMode shl 4) or PWR_TEMP_EN or PWR_PRESS_EN
        writeReg(REG_OSR,      osrReg)
        writeReg(REG_CONFIG,   configReg)
        writeReg(REG_ODR,      odr)
        writeReg(REG_PWR_CTRL, pwrReg)
    }

    /** Write a single byte to a register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    /** Read a single byte from a register. */
    protected fun readReg(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /** Burst-read 6 bytes from DATA_0..DATA_5. Returns (uncomp_press, uncomp_temp). */
    protected fun readBurst(): IntArray {
        val raw = connection.writeRead(byteArrayOf(REG_DATA_0.toByte()), 6)
        val uncompPress = ((raw[2].toInt() and 0xFF) shl 16) or ((raw[1].toInt() and 0xFF) shl 8) or (raw[0].toInt() and 0xFF)
        val uncompTemp  = ((raw[5].toInt() and 0xFF) shl 16) or ((raw[4].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        return intArrayOf(uncompPress, uncompTemp)
    }

    /** Compute temperature compensation and update tLin. */
    protected fun compensateTemperature(uncompTemp: Int): Double {
        val partial1 = uncompTemp - parT1
        val partial2 = partial1 * parT2
        tLin = partial2 + (partial1 * partial1) * parT3
        return tLin
    }

    /** Compute pressure compensation using the current tLin value. */
    protected fun compensatePressure(uncompPress: Int): Double {
        val partial1a = parP6 * tLin
        val partial2a = parP7 * tLin * tLin
        val partial3a = parP8 * tLin * tLin * tLin
        val partialOut1 = parP5 + partial1a + partial2a + partial3a

        val partial1b = parP2 * tLin
        val partial2b = parP3 * tLin * tLin
        val partial3b = parP4 * tLin * tLin * tLin
        val partialOut2 = uncompPress * (parP1 + partial1b + partial2b + partial3b)

        val partial1c = uncompPress.toDouble() * uncompPress
        val partial2c = parP9 + parP10 * tLin
        val partial3c = partial1c * partial2c
        val partial4 = partial3c + uncompPress.toDouble() * uncompPress * uncompPress * parP11

        return partialOut1 + partialOut2 + partial4
    }

    /** Read the temperature. */
    fun temperature(): Double {
        if (powerMode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED shl 4) or PWR_TEMP_EN or PWR_PRESS_EN)
            Thread.sleep(40)
        }
        val burst = readBurst()
        return compensateTemperature(burst[1])
    }

    /** Read the pressure. */
    fun pressure(): Double {
        if (powerMode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED shl 4) or PWR_TEMP_EN or PWR_PRESS_EN)
            Thread.sleep(40)
        }
        val burst = readBurst()
        compensateTemperature(burst[1])
        return compensatePressure(burst[0]) / 100.0
    }

    private fun readU16LE(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readS16LE(buf: ByteArray, off: Int): Int {
        val v = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun readS8(buf: ByteArray, off: Int): Int {
        val v = buf[off].toInt() and 0xFF
        return if (v >= 0x80) v - 0x100 else v
    }
}
