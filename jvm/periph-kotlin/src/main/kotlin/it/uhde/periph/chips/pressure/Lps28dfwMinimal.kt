package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * LPS28DFW — dual full-scale digital barometer (minimal driver).
 *
 * Reads absolute pressure and temperature from the STMicroelectronics
 * CCLGA-7L water-resistant sensor. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDD). Pressure is 24-bit signed, temperature is 16-bit signed.
 *
 * Default configuration (baked in at construction):
 *  - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
 *  - AVG = 0b010 (16 samples)
 *  - ODR = 0b0100 (25 Hz)
 *  - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)
 */
open class Lps28dfwMinimal(protected val connection: Connection) {

    companion object {
        /** WHO_AM_I value: fixed device identifier for LPS28DFW. */
        const val CHIP_ID = 0xB4

        /** Full-scale mode 1: 0–1260 hPa, 4096 LSB/hPa. */
        const val FS_MODE_1 = 0
        /** Full-scale mode 2: 0–4060 hPa, 2048 LSB/hPa. */
        const val FS_MODE_2 = 1

        protected const val REG_INTERRUPT_CFG = 0x0B
        protected const val REG_WHO_AM_I      = 0x0F
        protected const val REG_CTRL_REG1     = 0x10
        protected const val REG_CTRL_REG2     = 0x11
        protected const val REG_STATUS        = 0x27
        protected const val REG_PRESS_OUT_XL  = 0x28
        protected const val REG_PRESS_OUT_L   = 0x29
        protected const val REG_PRESS_OUT_H   = 0x2A
        protected const val REG_TEMP_OUT_L    = 0x2B
        protected const val REG_TEMP_OUT_H    = 0x2C

        protected const val SENSITIVITY_MODE1 = 4096.0
        protected const val SENSITIVITY_MODE2 = 2048.0
    }

    protected var fsMode: Int = 0
    protected var odr: Int = 0x04
    protected var avg: Int = 0x02
    protected var lpfEn: Int = 1
    protected var lpfCfg: Int = 0
    protected var bdu: Int = 1

    init {
        val id = connection.writeRead(byteArrayOf(REG_WHO_AM_I.toByte()), 1)
        if ((id[0].toInt() and 0xFF) != CHIP_ID) {
            throw IOException("LPS28DFW WHO_AM_I mismatch: expected 0x" +
                    Integer.toHexString(CHIP_ID) + ", got 0x" +
                    Integer.toHexString(id[0].toInt() and 0xFF))
        }
        try { Thread.sleep(2) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val ctrl2 = (fsMode shl 6) or (lpfCfg shl 5) or (lpfEn shl 4) or (bdu shl 3)
        writeReg(REG_CTRL_REG2, ctrl2)
        val ctrl1 = (odr shl 3) or (avg and 0x07)
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    /**
     * Write one byte to a register.
     *
     * @param reg register address
     * @param value value to write
     */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    /**
     * Read raw 24-bit pressure value (signed).
     */
    protected fun readPressureRaw(): Int {
        val b = connection.writeRead(byteArrayOf(REG_PRESS_OUT_XL.toByte()), 3)
        var v = ((b[2].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
        if ((v and 0x800000) != 0) v = v or 0xFF000000.toInt()
        return v
    }

    /**
     * Read raw 16-bit temperature value (signed).
     */
    protected fun readTemperatureRaw(): Int {
        val b = connection.writeRead(byteArrayOf(REG_TEMP_OUT_L.toByte()), 2)
        return (b[1].toInt() shl 8 or b[0].toInt()).toShort().toInt()
    }

    /** Read the absolute pressure in hPa. */
    fun readPressure(): Double {
        val raw = readPressureRaw()
        val sens = if (fsMode == 0) SENSITIVITY_MODE1 else SENSITIVITY_MODE2
        return raw / sens
    }

    /** Read the sensor temperature in °C. */
    fun readTemperature(): Double = readTemperatureRaw() / 100.0
}