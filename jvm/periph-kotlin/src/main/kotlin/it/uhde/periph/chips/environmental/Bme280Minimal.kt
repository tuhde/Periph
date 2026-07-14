package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME280 — combined humidity + pressure + temperature sensor (minimal driver).
 *
 * Reads calibrated temperature (°C), pressure (hPa), and humidity (%RH) via
 * I²C. The chip ID register is verified to be 0x60.
 *
 * Sibling of the BMP280 driver: register-compatible for pressure and
 * temperature, plus an integrated humidity front-end (its own calibration
 * block, control register, output registers, and compensation formula).
 *
 * Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * forced mode.
 *
 * Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 */
open class Bme280Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    addr: Int = 0x76
) {

    companion object {
        const val REG_CALIB       = 0x88
        const val REG_H1          = 0xA1
        const val REG_ID          = 0xD0
        const val REG_SOFT_RST    = 0xE0
        const val REG_CAL_H2      = 0xE1
        const val REG_CTRL_HUM    = 0xF2
        const val REG_STATUS      = 0xF3
        const val REG_CTRL_MEAS   = 0xF4
        const val REG_CONFIG      = 0xF5
        const val REG_DATA        = 0xF7

        const val CHIP_ID         = 0x60
        const val RESET_CMD       = 0xB6
        const val MEAS_TIME_MS    = 9L
    }

    protected var digT1: Int = 0
    protected var digT2: Int = 0
    protected var digT3: Int = 0
    protected var digP1: Int = 0
    protected var digP2: Int = 0
    protected var digP3: Int = 0
    protected var digP4: Int = 0
    protected var digP5: Int = 0
    protected var digP6: Int = 0
    protected var digP7: Int = 0
    protected var digP8: Int = 0
    protected var digP9: Int = 0
    protected var digH1: Int = 0
    protected var digH2: Int = 0
    protected var digH3: Int = 0
    protected var digH4: Int = 0
    protected var digH5: Int = 0
    protected var digH6: Int = 0

    protected var tFine: Int = 0

    protected var ctrlHum: Int = 0x01
    protected var ctrlMeas: Int = 0x25
    protected var config: Int = 0x00

    init {
        val id = transport.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        val chipId = id[0].toInt() and 0xFF
        if (chipId != CHIP_ID) {
            throw IOException("BME280 not found: expected 0x60, got 0x" + Integer.toHexString(chipId))
        }

        readCalibration()

        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), ctrlHum.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ctrlMeas.toByte()))
        transport.write(byteArrayOf(REG_CONFIG.toByte(), config.toByte()))
    }

    /**
     * Read and unpack the 33-byte calibration block from NVM (26 bytes from
     * 0x88 plus 7 bytes from 0xE1).
     *
     * Calibration is little-endian for the 16-bit fields. The humidity block
     * 0xE1–0xE7 packs dig_H4 / dig_H5 into register 0xE5 (lower / upper
     * nibble); both are 12-bit signed values sign-extended to 16-bit.
     *
     * @throws IOException on I²C error
     */
    protected fun readCalibration() {
        val cal = transport.writeRead(byteArrayOf(REG_CALIB.toByte()), 26)
        digT1 = ((cal[1].toInt() and 0xFF) shl 8) or (cal[0].toInt() and 0xFF)
        digT2 = ((cal[3].toInt() and 0xFF) shl 8) or (cal[2].toInt() and 0xFF)
        digT3 = ((cal[5].toInt() and 0xFF) shl 8) or (cal[4].toInt() and 0xFF)
        digP1 = ((cal[7].toInt() and 0xFF) shl 8) or (cal[6].toInt() and 0xFF)
        digP2 = ((cal[9].toInt() and 0xFF) shl 8) or (cal[8].toInt() and 0xFF)
        digP3 = ((cal[11].toInt() and 0xFF) shl 8) or (cal[10].toInt() and 0xFF)
        digP4 = ((cal[13].toInt() and 0xFF) shl 8) or (cal[12].toInt() and 0xFF)
        digP5 = ((cal[15].toInt() and 0xFF) shl 8) or (cal[14].toInt() and 0xFF)
        digP6 = ((cal[17].toInt() and 0xFF) shl 8) or (cal[16].toInt() and 0xFF)
        digP7 = ((cal[19].toInt() and 0xFF) shl 8) or (cal[18].toInt() and 0xFF)
        digP8 = ((cal[21].toInt() and 0xFF) shl 8) or (cal[20].toInt() and 0xFF)
        digP9 = ((cal[23].toInt() and 0xFF) shl 8) or (cal[22].toInt() and 0xFF)
        digH1 = cal[25].toInt() and 0xFF

        val h = transport.writeRead(byteArrayOf(REG_CAL_H2.toByte()), 7)
        digH2 = ((h[1].toInt() and 0xFF) shl 8) or (h[0].toInt() and 0xFF)
        digH3 = h[2].toInt() and 0xFF
        val h4raw = ((h[3].toInt() and 0xFF) shl 4) or (h[4].toInt() and 0x0F)
        val h5raw = ((h[5].toInt() and 0xFF) shl 4) or ((h[4].toInt() shr 4) and 0x0F)
        digH4 = if ((h4raw and 0x800) != 0) (h4raw or 0xF000).toShort().toInt() else h4raw.toShort().toInt()
        digH5 = if ((h5raw and 0x800) != 0) (h5raw or 0xF000).toShort().toInt() else h5raw.toShort().toInt()
        digH6 = h[6].toByte().toInt()
    }

    /**
     * Trigger a forced-mode measurement and burst-read 8 bytes from 0xF7.
     *
     * Writes ctrl_hum (must precede ctrl_meas for humidity oversampling to
     * latch), then writes ctrl_meas with forced mode, waits 9 ms, and reads
     * press[19:0], temp[19:0], and hum[15:0] in a single burst.
     *
     * @return 8-byte raw data array
     * @throws IOException on I²C error
     */
    protected fun triggerAndRead(): ByteArray {
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), ctrlHum.toByte()))
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ((ctrlMeas and 0xFC) or 0x01).toByte()))
        try { Thread.sleep(MEAS_TIME_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return transport.writeRead(byteArrayOf(REG_DATA.toByte()), 8)
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected fun compensateTemperature(adcT: Int): Double {
        val var1 = (((adcT.toLong() shr 3) - (digT1.toLong() shl 1)) * digT2.toLong()) shr 11
        val var2 = ((((adcT.toLong() shr 4) - (digT1.toLong() and 0xFFFFL)) *
                    ((adcT.toLong() shr 4) - (digT1.toLong() and 0xFFFFL))) shr 12) * digT3.toLong() shr 14
        tFine = (var1 + var2).toInt()
        return ((tFine * 5 + 128) shr 8) / 100.0
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected fun compensatePressure(adcP: Int): Double {
        val t = tFine.toLong()
        var var1 = t - 128000L
        var var2 = var1 * var1 * digP6.toLong()
        var2 += (var1 * digP5.toLong()) shl 17
        var2 += digP4.toLong() shl 35
        var1 = ((var1 * var1 * digP3.toLong()) shr 8) + ((var1 * digP2.toLong()) shl 12)
        var1 = ((1L shl 47) + var1) * (digP1.toLong() and 0xFFFFL) shr 33
        if (var1 == 0L) return 0.0
        var p = 1048576L - adcP.toLong()
        p = ((p shl 31) - var2) * 3125L / var1
        var1 = (digP9.toLong() * (p shr 13) * (p shr 13)) shr 25
        var2 = (digP8.toLong() * p) shr 19
        p = ((p + var1 + var2) shr 8) + (digP7.toLong() shl 4)
        return (p / 256.0) / 100.0
    }

    /**
     * Compute humidity compensation using the current tFine value.
     *
     * @param adcH raw 16-bit humidity ADC value
     * @return humidity in %RH
     */
    protected fun compensateHumidity(adcH: Int): Double {
        var v = tFine.toLong() - 76800L
        val vX1 = (((adcH.toLong() shl 14) - (digH4.toLong() shl 20) - (digH5.toLong() * v) + 16384) shr 15)
        v = ((v * digH6.toLong()) shr 10) * (((v * digH3.toLong()) shr 11) + 32768L)
        v = v shr 10
        v += 2097152L
        v = ((v * digH2.toLong()) + 8192L) shr 14
        v = vX1 * v
        val vX2 = (v shr 15) * (v shr 15)
        v -= (((vX2 shr 7) * digH1.toLong()) shr 4)
        if (v < 0) v = 0
        if (v > 419430400L) v = 419430400L
        return (v shr 12) / 1024.0
    }

    /**
     * Read the temperature.
     *
     * Triggers a forced-mode measurement and returns the result in degrees
     * Celsius. Also updates tFine for subsequent pressure/humidity reads.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    fun temperature(): Double {
        val raw = triggerAndRead()
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or ((raw[4].toInt() and 0xFF) shl 4) or ((raw[5].toInt() and 0xFF) shr 4)
        return compensateTemperature(adcT)
    }

    /**
     * Read the pressure.
     *
     * Triggers a forced-mode measurement, compensates temperature first (to
     * populate tFine), then compensates pressure. Returns the result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    fun pressure(): Double {
        val raw = triggerAndRead()
        val adcP = ((raw[0].toInt() and 0xFF) shl 12) or ((raw[1].toInt() and 0xFF) shl 4) or ((raw[2].toInt() and 0xFF) shr 4)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or ((raw[4].toInt() and 0xFF) shl 4) or ((raw[5].toInt() and 0xFF) shr 4)
        compensateTemperature(adcT)
        return compensatePressure(adcP)
    }

    /**
     * Read the humidity.
     *
     * Triggers a forced-mode measurement, compensates temperature first (to
     * populate tFine), then compensates humidity. Returns the result in %RH.
     *
     * @return relative humidity in %RH
     * @throws IOException on I²C error
     */
    fun humidity(): Double {
        val raw = triggerAndRead()
        val adcH = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or ((raw[4].toInt() and 0xFF) shl 4) or ((raw[5].toInt() and 0xFF) shr 4)
        compensateTemperature(adcT)
        return compensateHumidity(adcH)
    }
}
