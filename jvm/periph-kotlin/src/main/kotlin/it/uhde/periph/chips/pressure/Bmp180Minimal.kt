package it.uhde.periph.chips.pressure

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BMP180 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * Reads temperature and pressure via I²C using Bosch's integer compensation
 * algorithm. Calibration coefficients are loaded from the chip's EEPROM during
 * construction and sanity-checked. The chip ID register is verified to be 0x55.
 *
 * Fixed I²C address: 0x77 (hardware-defined, not configurable).
 *
 * Default oversampling setting (OSS): 0 (ultra-low-power).
 */
open class Bmp180Minimal(protected val transport: Transport) {

    companion object {
        const val REG_CAL_START = 0xAA
        const val REG_ID        = 0xD0
        const val REG_SOFT_RST  = 0xE0
        const val REG_CTRL_MEAS = 0xF4
        const val REG_OUT_MSB   = 0xF6
        const val CMD_TEMP      = 0x2E
        const val CMD_PRES_OSS  = 0x34
        const val CHIP_ID       = 0x55
    }

    // Calibration coefficients
    protected var AC1: Int = 0
    protected var AC2: Int = 0
    protected var AC3: Int = 0
    protected var AC4: Int = 0   // uint16 — treat as unsigned when used
    protected var AC5: Int = 0   // uint16
    protected var AC6: Int = 0   // uint16
    protected var B1:  Int = 0
    protected var B2:  Int = 0
    protected var MB:  Int = 0
    protected var MC:  Int = 0
    protected var MD:  Int = 0

    /** Current oversampling setting (0–3). */
    protected var oss: Int = 0

    init {
        // Verify chip ID
        val id = transport.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        if (id[0].toInt() and 0xFF != CHIP_ID) {
            throw IOException(
                "BMP180 not found: expected chip ID 0x55, got 0x${(id[0].toInt() and 0xFF).toString(16)}"
            )
        }
        readCalibration()
    }

    /**
     * Read and unpack the 22-byte calibration block from EEPROM (0xAA–0xBF).
     *
     * @throws IOException on I²C error or invalid calibration data
     */
    protected fun readCalibration() {
        val cal = transport.writeRead(byteArrayOf(REG_CAL_START.toByte()), 22)

        fun s16(hi: Int, lo: Int): Int = (((cal[hi].toInt() and 0xFF) shl 8) or (cal[lo].toInt() and 0xFF)).toShort().toInt()
        fun u16(hi: Int, lo: Int): Int =  ((cal[hi].toInt() and 0xFF) shl 8) or (cal[lo].toInt() and 0xFF)

        AC1 = s16( 0,  1)
        AC2 = s16( 2,  3)
        AC3 = s16( 4,  5)
        AC4 = u16( 6,  7)
        AC5 = u16( 8,  9)
        AC6 = u16(10, 11)
        B1  = s16(12, 13)
        B2  = s16(14, 15)
        MB  = s16(16, 17)
        MC  = s16(18, 19)
        MD  = s16(20, 21)

        checkCalibration()
    }

    /**
     * Sanity-check: no coefficient may be 0x0000 or 0xFFFF.
     *
     * @throws IOException if any coefficient is 0x0000 or 0xFFFF
     */
    protected fun checkCalibration() {
        val raw16 = intArrayOf(
            AC1 and 0xFFFF, AC2 and 0xFFFF, AC3 and 0xFFFF,
            AC4 and 0xFFFF, AC5 and 0xFFFF, AC6 and 0xFFFF,
            B1  and 0xFFFF, B2  and 0xFFFF,
            MB  and 0xFFFF, MC  and 0xFFFF, MD  and 0xFFFF
        )
        val names = arrayOf("AC1","AC2","AC3","AC4","AC5","AC6","B1","B2","MB","MC","MD")
        for (i in raw16.indices) {
            if (raw16[i] == 0x0000 || raw16[i] == 0xFFFF) {
                throw IOException(
                    "BMP180 calibration invalid: ${names[i]} = 0x${raw16[i].toString(16)}"
                )
            }
        }
    }

    /**
     * Trigger a temperature measurement and return the raw ADC value (UT).
     *
     * @return unsigned 16-bit raw temperature
     */
    protected fun readRawTemperature(): Int {
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), CMD_TEMP.toByte()))
        Thread.sleep(5)
        val b = transport.writeRead(byteArrayOf(REG_OUT_MSB.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Trigger a pressure measurement and return the raw ADC value (UP).
     *
     * @param ossMode oversampling setting (0–3)
     * @return raw pressure ADC value (shifted by oss)
     */
    protected fun readRawPressure(ossMode: Int): Long {
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), (CMD_PRES_OSS or (ossMode shl 6)).toByte()))
        Thread.sleep(ossMode * 10L + 5L)
        val b = transport.writeRead(byteArrayOf(REG_OUT_MSB.toByte()), 3)
        val raw = ((b[0].toLong() and 0xFF) shl 16) or ((b[1].toLong() and 0xFF) shl 8) or (b[2].toLong() and 0xFF)
        return raw shr (8 - ossMode)
    }

    /**
     * Compute B5 from a raw temperature value (UT).
     *
     * @param UT raw temperature ADC value
     * @return B5 (shared intermediate used by both temperature and pressure compensation)
     */
    protected fun computeB5(UT: Int): Long {
        val X1 = ((UT.toLong() - AC6.toLong()) * AC5.toLong()) shr 15
        val X2 = (MC.toLong() shl 11) / (X1 + MD.toLong())
        return X1 + X2
    }

    /**
     * Read the temperature.
     *
     * Triggers a temperature measurement, runs Bosch integer compensation,
     * and returns the result in degrees Celsius.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    fun temperature(): Double {
        val UT = readRawTemperature()
        val B5 = computeB5(UT)
        val T  = (B5 + 8) shr 4
        return T / 10.0
    }

    /**
     * Read the pressure.
     *
     * Re-reads temperature internally to refresh B5, then triggers a pressure
     * measurement. Runs Bosch integer compensation and returns the result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    fun pressure(): Double {
        val UT = readRawTemperature()
        val B5 = computeB5(UT)
        val UP = readRawPressure(oss)

        val B6 = B5 - 4000L
        var X1 = (B2.toLong() * ((B6 * B6) shr 12)) shr 11
        var X2 = (AC2.toLong() * B6) shr 11
        var X3 = X1 + X2
        val B3 = (((AC1.toLong() * 4L + X3) shl oss) + 2L) shr 2

        X1 = (AC3.toLong() * B6) shr 13
        X2 = (B1.toLong() * ((B6 * B6) shr 12)) shr 16
        X3 = ((X1 + X2) + 2L) shr 2
        val B4 = (AC4.toLong() and 0xFFFFFFFFL) * (X3 + 32768L) ushr 15
        val B7 = (UP - B3) * (50000L shr oss)

        var p: Long = if (B7 < 0x80000000L) {
            (B7 * 2L) / B4
        } else {
            (B7 / B4) * 2L
        }

        X1 = (p shr 8) * (p shr 8)
        X1 = (X1 * 3038L) shr 16
        X2 = (-7357L * p) shr 16
        p  = p + ((X1 + X2 + 3791L) shr 4)

        return p / 100.0
    }
}
