package it.uhde.periph.chips.pressure

import it.uhde.periph.transport.Transport
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BMP280 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * Reads temperature and pressure via I²C using Bosch's 64-bit integer
 * compensation algorithm. Calibration coefficients are loaded from the chip's
 * NVM during construction. The chip ID register is verified to be 0x58.
 *
 * Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 *
 * Default settings: osrs_t=×1, osrs_p=×1, filter off; measurements are
 * triggered in forced mode (one shot per call).
 */
open class Bmp280Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    addr: Int = 0x76
) {

    companion object {
        const val REG_CALIB     = 0x88
        const val REG_ID        = 0xD0
        const val REG_SOFT_RST  = 0xE0
        const val REG_STATUS    = 0xF3
        const val REG_CTRL_MEAS = 0xF4
        const val REG_CONFIG    = 0xF5
        const val REG_DATA      = 0xF7
        const val CHIP_ID         = 0x58
        const val CHIP_ID_BME280  = 0x60  // same P/T interface; humidity not supported
    }

    // Calibration coefficients
    protected var digT1: Int = 0   // uint16
    protected var digT2: Int = 0   // int16
    protected var digT3: Int = 0   // int16
    protected var digP1: Int = 0   // uint16
    protected var digP2: Int = 0   // int16
    protected var digP3: Int = 0   // int16
    protected var digP4: Int = 0   // int16
    protected var digP5: Int = 0   // int16
    protected var digP6: Int = 0   // int16
    protected var digP7: Int = 0   // int16
    protected var digP8: Int = 0   // int16
    protected var digP9: Int = 0   // int16

    /** tFine shared between temperature and pressure compensation. */
    protected var tFine: Int = 0

    /** ctrl_meas value applied at each measurement. */
    protected var ctrlMeas: Int = 0x25   // osrs_t=×1, osrs_p=×1, mode=forced placeholder
    /** config register value. */
    protected var config: Int = 0x00     // filter off

    init {
        // Verify chip ID
        val id = transport.writeRead(byteArrayOf(REG_ID.toByte()), 1)
        val chipId = id[0].toInt() and 0xFF
        if (chipId != CHIP_ID && chipId != CHIP_ID_BME280) {
            throw IOException(
                "BMP280/BME280 not found: expected 0x58 or 0x60, got 0x${chipId.toString(16)}"
            )
        }
        readCalibration()
    }

    /**
     * Read and unpack the 24-byte calibration block from NVM (0x88–0x9F).
     *
     * Calibration is little-endian: LSB comes first. T1 and P1 are unsigned
     * (uint16); all other coefficients are signed (int16).
     *
     * @throws IOException on I²C error
     */
    protected fun readCalibration() {
        val cal = transport.writeRead(byteArrayOf(REG_CALIB.toByte()), 24)
        val buf = ByteBuffer.wrap(cal).order(ByteOrder.LITTLE_ENDIAN)

        digT1 = buf.short.toInt() and 0xFFFF   // uint16
        digT2 = buf.short.toInt()               // int16
        digT3 = buf.short.toInt()               // int16
        digP1 = buf.short.toInt() and 0xFFFF   // uint16
        digP2 = buf.short.toInt()               // int16
        digP3 = buf.short.toInt()               // int16
        digP4 = buf.short.toInt()               // int16
        digP5 = buf.short.toInt()               // int16
        digP6 = buf.short.toInt()               // int16
        digP7 = buf.short.toInt()               // int16
        digP8 = buf.short.toInt()               // int16
        digP9 = buf.short.toInt()               // int16
    }

    /**
     * Trigger a forced-mode measurement and burst-read 6 bytes from 0xF7.
     *
     * Writes ctrl_meas with mode=01 (forced), waits 7 ms, then reads
     * press[19:0] and temp[19:0] in a single burst.
     *
     * @return 6-byte raw data array: [press_msb, press_lsb, press_xlsb,
     *         temp_msb, temp_lsb, temp_xlsb]
     */
    protected fun readRawData(): ByteArray {
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), ((ctrlMeas and 0xFC) or 0x01).toByte()))
        Thread.sleep(7)
        return transport.writeRead(byteArrayOf(REG_DATA.toByte()), 6)
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected fun compensateTemperature(adcT: Int): Double {
        val var1 = (((adcT.toLong() shr 3) - (digT1.toLong() shl 1)) * digT2.toLong()) shr 11
        val var2 = ((((adcT.toLong() shr 4) - digT1.toLong()) *
                     ((adcT.toLong() shr 4) - digT1.toLong())) shr 12) * digT3.toLong() shr 14
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
        var var1 = tFine.toLong() - 128000L
        var var2 = var1 * var1 * digP6.toLong()
        var2 += (var1 * digP5.toLong()) shl 17
        var2 += digP4.toLong() shl 35
        var1  = ((var1 * var1 * digP3.toLong()) shr 8) + ((var1 * digP2.toLong()) shl 12)
        var1  = ((1L shl 47) + var1) * (digP1.toLong() and 0xFFFFL) shr 33
        if (var1 == 0L) return 0.0   // avoid division by zero
        var p = 1048576L - adcP.toLong()
        p = ((p shl 31) - var2) * 3125L / var1
        var1 = (digP9.toLong() * (p shr 13) * (p shr 13)) shr 25
        var2 = (digP8.toLong() * p) shr 19
        p = ((p + var1 + var2) shr 8) + (digP7.toLong() shl 4)
        return (p / 256.0) / 100.0
    }

    /**
     * Read the temperature.
     *
     * Triggers a forced-mode measurement, runs Bosch 64-bit integer
     * compensation, and returns the result in degrees Celsius. Also updates
     * the internal tFine value used by subsequent pressure reads.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    fun temperature(): Double {
        val raw  = readRawData()
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                   ((raw[4].toInt() and 0xFF) shl 4)  or
                   ((raw[5].toInt() and 0xFF) shr 4)
        return compensateTemperature(adcT)
    }

    /**
     * Read the pressure.
     *
     * Triggers a forced-mode measurement, compensates temperature first
     * (to populate tFine), then compensates pressure. Returns the result
     * in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    fun pressure(): Double {
        val raw  = readRawData()
        val adcP = ((raw[0].toInt() and 0xFF) shl 12) or
                   ((raw[1].toInt() and 0xFF) shl 4)  or
                   ((raw[2].toInt() and 0xFF) shr 4)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                   ((raw[4].toInt() and 0xFF) shl 4)  or
                   ((raw[5].toInt() and 0xFF) shr 4)
        compensateTemperature(adcT)   // populates tFine
        return compensatePressure(adcP)
    }
}
