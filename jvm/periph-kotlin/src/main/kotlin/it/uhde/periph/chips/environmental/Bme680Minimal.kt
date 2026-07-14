package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME680 — 4-in-1 environmental sensor: temperature, pressure, humidity, and
 * gas resistance (minimal driver).
 *
 * Reads calibrated temperature (°C), pressure (hPa), humidity (%RH), and gas
 * sensor resistance (Ω) via I²C. Calibration coefficients are loaded from
 * three non-contiguous NVM regions during construction. The chip ID register
 * is verified to be 0x61.
 *
 * Each public read method triggers a forced-mode TPHG cycle, waits for
 * completion (~200 ms with default heater settings), and burst-reads all
 * 13 output bytes in a single transaction.
 *
 * Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * heater profile 0 at 320 °C for 150 ms with gas conversion enabled.
 *
 * Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 */
open class Bme680Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    addr: Int = 0x76
) {

    companion object {
        const val REG_CHIP_ID       = 0xD0
        const val REG_SOFT_RESET    = 0xE0
        const val REG_CTRL_GAS_0    = 0x70
        const val REG_CTRL_GAS_1    = 0x71
        const val REG_CTRL_HUM      = 0x72
        const val REG_CTRL_MEAS     = 0x74
        const val REG_CONFIG        = 0x75
        const val REG_DATA          = 0x1F
        const val REG_RES_HEAT_VAL  = 0x00
        const val REG_RES_HEAT_RNG  = 0x02
        const val REG_RANGE_SW_ERR  = 0x04
        const val REG_CALIB_BLOCK1  = 0x8A
        const val REG_CALIB_BLOCK2  = 0xE1
        const val REG_RES_HEAT_BASE = 0x5A
        const val REG_GAS_WAIT_BASE = 0x64
        const val CHIP_ID           = 0x61

        private val CONST_ARRAY1 = longArrayOf(
            2147483647L, 2147483647L, 2147483647L, 2147483647L, 2147483647L,
            2126008810L, 2147483647L, 2130303777L, 2147483647L, 2147483647L,
            2143188679L, 2136746228L, 2147483647L, 2126008810L, 2147483647L,
            2147483647L
        )

        private val CONST_ARRAY2 = longArrayOf(
            4096000000L, 2048000000L, 1024000000L, 512000000L, 255744255L,
            127110228L, 64000000L, 32258064L, 16016016L, 8000000L,
            4000000L, 2000000L, 1000000L, 500000L, 250000L,
            125000L
        )
    }

    protected var parT1: Int = 0
    protected var parT2: Int = 0
    protected var parT3: Int = 0
    protected var parP1: Int = 0
    protected var parP2: Int = 0
    protected var parP3: Int = 0
    protected var parP4: Int = 0
    protected var parP5: Int = 0
    protected var parP6: Int = 0
    protected var parP7: Int = 0
    protected var parP8: Int = 0
    protected var parP9: Int = 0
    protected var parP10: Int = 0
    protected var parH1: Int = 0
    protected var parH2: Int = 0
    protected var parH3: Int = 0
    protected var parH4: Int = 0
    protected var parH5: Int = 0
    protected var parH6: Int = 0
    protected var parH7: Int = 0
    protected var parG1: Int = 0
    protected var parG2: Int = 0
    protected var parG3: Int = 0
    protected var resHeatVal: Int = 0
    protected var resHeatRange: Int = 0
    protected var rangeSwitchingError: Int = 0

    protected var tFine: Int = 0
    protected var ambientTemp: Double = 25.0

    protected var osrsH: Int = 1
    protected var osrsT: Int = 1
    protected var osrsP: Int = 1
    protected var filterCoeff: Int = 0
    protected var ctrlGas1: Int = 0x10

    protected var lastGasValid: Boolean = false
    protected var lastHeatStable: Boolean = false

    init {
        val id = transport.writeRead(byteArrayOf(REG_CHIP_ID.toByte()), 1)
        val chipId = id[0].toInt() and 0xFF
        if (chipId != CHIP_ID) {
            throw IOException(
                "BME680 not found: expected 0x61, got 0x${chipId.toString(16)}"
            )
        }
        readCalibration()
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), (osrsH and 0x07).toByte()))
        transport.write(
            byteArrayOf(
                REG_CTRL_MEAS.toByte(),
                (((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or 0x00).toByte()
            )
        )
        transport.write(byteArrayOf(REG_CONFIG.toByte(), ((filterCoeff and 0x07) shl 2).toByte()))
        configureHeaterProfile(0, 320, 150)
        transport.write(byteArrayOf(REG_CTRL_GAS_1.toByte(), ctrlGas1.toByte()))
    }

    /**
     * Read and unpack all 28 calibration parameters from three NVM regions.
     *
     * Block 1 (23 bytes from 0x8A): temperature and pressure coefficients.
     * Block 2 (14 bytes from 0xE1): humidity, gas, and par_T1 coefficients.
     * Single bytes at 0x00, 0x02, 0x04: heater calibration values.
     *
     * @throws IOException on I²C error
     */
    protected fun readCalibration() {
        val b1 = transport.writeRead(byteArrayOf(REG_CALIB_BLOCK1.toByte()), 23)
        val b2 = transport.writeRead(byteArrayOf(REG_CALIB_BLOCK2.toByte()), 14)

        parT2 = ((b1[1].toInt() and 0xFF) shl 8) or (b1[0].toInt() and 0xFF)
        if (parT2 > 32767) parT2 -= 65536
        parT3 = b1[2].toInt()
        parP1 = ((b1[5].toInt() and 0xFF) shl 8) or (b1[4].toInt() and 0xFF)
        parP2 = ((b1[7].toInt() and 0xFF) shl 8) or (b1[6].toInt() and 0xFF)
        if (parP2 > 32767) parP2 -= 65536
        parP3 = b1[8].toInt()
        parP4 = ((b1[11].toInt() and 0xFF) shl 8) or (b1[10].toInt() and 0xFF)
        if (parP4 > 32767) parP4 -= 65536
        parP5 = ((b1[13].toInt() and 0xFF) shl 8) or (b1[12].toInt() and 0xFF)
        if (parP5 > 32767) parP5 -= 65536
        parP7 = b1[14].toInt()
        parP6 = b1[15].toInt()
        parP8 = ((b1[19].toInt() and 0xFF) shl 8) or (b1[18].toInt() and 0xFF)
        if (parP8 > 32767) parP8 -= 65536
        parP9 = ((b1[21].toInt() and 0xFF) shl 8) or (b1[20].toInt() and 0xFF)
        if (parP9 > 32767) parP9 -= 65536
        parP10 = b1[22].toInt() and 0xFF

        parH2 = ((b2[0].toInt() and 0xFF) shl 4) or ((b2[1].toInt() and 0xFF) shr 4)
        parH1 = ((b2[2].toInt() and 0xFF) shl 4) or (b2[1].toInt() and 0x0F)
        parH3 = b2[3].toInt()
        parH4 = b2[4].toInt()
        parH5 = b2[5].toInt()
        parH6 = b2[6].toInt() and 0xFF
        parH7 = b2[7].toInt()
        parT1 = ((b2[9].toInt() and 0xFF) shl 8) or (b2[8].toInt() and 0xFF)
        parG2 = ((b2[11].toInt() and 0xFF) shl 8) or (b2[10].toInt() and 0xFF)
        if (parG2 > 32767) parG2 -= 65536
        parG1 = b2[12].toInt()
        parG3 = b2[13].toInt()

        val rhv = transport.writeRead(byteArrayOf(REG_RES_HEAT_VAL.toByte()), 1)
        resHeatVal = rhv[0].toInt()
        val rhr = transport.writeRead(byteArrayOf(REG_RES_HEAT_RNG.toByte()), 1)
        resHeatRange = (rhr[0].toInt() and 0xFF) shr 4 and 0x03
        val rse = transport.writeRead(byteArrayOf(REG_RANGE_SW_ERR.toByte()), 1)
        rangeSwitchingError = signExtend4((rse[0].toInt() and 0xFF) shr 4)
    }

    /**
     * Configure a heater profile with the given target temperature and duration.
     *
     * Computes the heater resistance register value from the target temperature
     * and the current ambient temperature estimate, encodes the gas wait time,
     * and writes both to the chip.
     *
     * @param profile heater profile index (0–9)
     * @param targetTempC target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    protected fun configureHeaterProfile(profile: Int, targetTempC: Int, durationMs: Int) {
        val resHeat = calcHeaterResistance(targetTempC, ambientTemp.toInt())
        transport.write(
            byteArrayOf(
                (REG_RES_HEAT_BASE + profile).toByte(),
                resHeat.toByte()
            )
        )
        val gasWait = encodeGasWait(durationMs)
        transport.write(
            byteArrayOf(
                (REG_GAS_WAIT_BASE + profile).toByte(),
                gasWait.toByte()
            )
        )
    }

    /**
     * Trigger a forced-mode TPHG cycle and burst-read 13 output bytes.
     *
     * Writes ctrl_hum then ctrl_meas (in that order, as required by the chip),
     * sleeps 200 ms for the measurement to complete, and reads registers
     * 0x1F–0x2B in a single transaction.
     *
     * @return 13-byte raw data array: press[0..2], temp[3..5], hum[6..7],
     *         status[8..10], gas[11..12]
     * @throws IOException on I²C error
     */
    protected fun readRawData(): ByteArray {
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), (osrsH and 0x07).toByte()))
        val meas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or 0x01
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), meas.toByte()))
        Thread.sleep(200)
        return transport.writeRead(byteArrayOf(REG_DATA.toByte()), 13)
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected fun compensateTemperature(adcT: Int): Double {
        val var1 = (adcT shr 3) - (parT1 shl 1)
        val var2 = (var1.toLong() * parT2) shr 11
        val var3 = ((((var1 shr 1).toLong() * (var1 shr 1)) shr 12) * (parT3.toLong() shl 4)) shr 14
        tFine = (var2 + var3).toInt()
        return ((tFine * 5 + 128) shr 8) / 100.0
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * Uses 32-bit integer arithmetic with Long intermediates to avoid overflow.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected fun compensatePressure(adcP: Int): Double {
        var var1 = (tFine.toLong() shr 1) - 64000L
        var var2 = ((((var1 shr 2) * (var1 shr 2)) shr 11) * parP6.toLong()) shr 2
        var2 += (var1 * parP5.toLong()) shl 1
        var2 = (var2 shr 2) + (parP4.toLong() shl 16)
        var1 = ((((((var1 shr 2) * (var1 shr 2)) shr 13) * (parP3.toLong() shl 5)) shr 3) +
                ((parP2.toLong() * var1) shr 1))
        var1 = var1 shr 18
        var1 = ((32768L + var1) * parP1.toLong()) shr 15
        if (var1 == 0L) return 0.0
        var pressComp = 1048576L - adcP.toLong()
        pressComp = (pressComp - (var2 shr 12)) * 3125L
        pressComp = if (pressComp >= (1L shl 30)) {
            (pressComp / var1) shl 1
        } else {
            (pressComp shl 1) / var1
        }
        val pVar1 = (parP9.toLong() * (((pressComp shr 3) * (pressComp shr 3)) shr 13)) shr 12
        val pVar2 = ((pressComp shr 2) * parP8.toLong()) shr 13
        val pVar3 = ((pressComp shr 8) * (pressComp shr 8) * (pressComp shr 8) * parP10.toLong()) shr 17
        pressComp += (pVar1 + pVar2 + pVar3 + (parP7.toLong() shl 7)) shr 4
        return pressComp / 100.0
    }

    /**
     * Compute humidity compensation using the current tFine value.
     *
     * @param humAdc raw 16-bit humidity ADC value
     * @return humidity in %RH, clamped to [0, 100]
     */
    protected fun compensateHumidity(humAdc: Int): Double {
        val tempScaled = tFine
        val var1 = humAdc - ((parH1 shl 4) + (((tempScaled * parH3) / 100) shr 1))
        val var2 = (parH2.toLong() *
                ((((tempScaled.toLong() * parH4) / 100) +
                        (((tempScaled.toLong() * ((tempScaled.toLong() * parH5) / 100)) shr 6) / 100) +
                        (1L shl 14)))) shr 10
        val var3 = var1.toLong() * var2
        val var4 = ((parH6.toLong() shl 7) + ((tempScaled.toLong() * parH7) / 100)) shr 4
        val var5 = ((var3 shr 14) * (var3 shr 14)) shr 10
        val var6 = (var4 * var5) shr 1
        var humComp = (((var3 + var6) shr 10) * 1000L) shr 12
        if (humComp > 100000L) humComp = 100000L
        if (humComp < 0L) humComp = 0L
        return humComp / 1000.0
    }

    /**
     * Compute gas sensor resistance from the raw ADC, range code, and
     * range-switching error using 64-bit lookup tables.
     *
     * @param gasAdc raw 10-bit gas ADC value
     * @param gasRange gas range code (0–15), selects lookup table entry
     * @return gas resistance in Ω
     */
    protected fun compensateGasResistance(gasAdc: Int, gasRange: Int): Double {
        val var1 = ((1340L + 5L * rangeSwitchingError) * CONST_ARRAY1[gasRange]) shr 16
        val var2 = ((gasAdc.toLong() shl 15) - (1L shl 24)) + var1
        val gasRes = ((CONST_ARRAY2[gasRange] * var1) shr 9) + (var2 shr 1)
        return gasRes.toDouble() / var2.toDouble()
    }

    /**
     * Compute the heater resistance register value for a target temperature.
     *
     * @param targetTempC desired heater temperature in °C
     * @param ambTempC ambient temperature in °C (from on-chip reading or estimate)
     * @return 8-bit heater resistance value to write to res_heat_x
     */
    protected fun calcHeaterResistance(targetTempC: Int, ambTempC: Int): Int {
        val var1 = ((ambTempC.toLong() * parG3) / 10) shl 8
        val var2 = (parG1.toLong() + 784) *
                (((((parG2.toLong() + 154009) * targetTempC * 5) / 100) + 3276800L) / 10)
        val var3 = var1 + (var2 shr 1)
        val var4 = var3 / (resHeatRange + 4)
        val var5 = (131L * resHeatVal) + 65536L
        val resHeatX100 = ((var4 / var5) - 250) * 34
        val resHeatX = ((resHeatX100 + 50) / 100).toInt()
        return if (resHeatX > 255) 255 else if (resHeatX < 0) 0 else resHeatX
    }

    /**
     * Encode a heater on-time duration into the gas_wait register format.
     *
     * Uses the smallest multiplier (×1, ×4, ×16, ×64) that fits the
     * requested duration within the 6-bit timer field.
     *
     * @param targetMs desired duration in ms (1–4032)
     * @return encoded gas_wait byte
     */
    protected fun encodeGasWait(targetMs: Int): Int {
        return when {
            targetMs <= 0x3F -> targetMs
            targetMs <= 0x3F * 4 -> (1 shl 6) or (targetMs / 4)
            targetMs <= 0x3F * 16 -> (2 shl 6) or (targetMs / 16)
            else -> (3 shl 6) or minOf(targetMs / 64, 0x3F)
        }
    }

    private fun signExtend4(v: Int): Int = if (v and 0x08 != 0) v - 16 else v

    /**
     * Read the temperature.
     *
     * Triggers a forced-mode TPHG cycle, compensates temperature, and updates
     * the internal ambient temperature estimate used for heater calculations.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    fun temperature(): Double {
        val raw = readRawData()
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                ((raw[4].toInt() and 0xFF) shl 4) or
                ((raw[5].toInt() and 0xFF) shr 4)
        val t = compensateTemperature(adcT)
        ambientTemp = t
        return t
    }

    /**
     * Read the pressure.
     *
     * Triggers a forced-mode TPHG cycle, compensates temperature first
     * (to populate tFine), then compensates pressure.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    fun pressure(): Double {
        val raw = readRawData()
        val adcP = ((raw[0].toInt() and 0xFF) shl 12) or
                ((raw[1].toInt() and 0xFF) shl 4) or
                ((raw[2].toInt() and 0xFF) shr 4)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                ((raw[4].toInt() and 0xFF) shl 4) or
                ((raw[5].toInt() and 0xFF) shr 4)
        compensateTemperature(adcT)
        return compensatePressure(adcP)
    }

    /**
     * Read the humidity.
     *
     * Triggers a forced-mode TPHG cycle, compensates temperature first
     * (to populate tFine), then compensates humidity.
     *
     * @return humidity in %RH
     * @throws IOException on I²C error
     */
    fun humidity(): Double {
        val raw = readRawData()
        val humAdc = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                ((raw[4].toInt() and 0xFF) shl 4) or
                ((raw[5].toInt() and 0xFF) shr 4)
        compensateTemperature(adcT)
        return compensateHumidity(humAdc)
    }

    /**
     * Read the gas sensor resistance.
     *
     * Triggers a forced-mode TPHG cycle and extracts the 10-bit gas ADC,
     * range code, and validity flags from the output registers. Returns
     * [Double.NaN] if the gas measurement was invalid or the heater did
     * not stabilize.
     *
     * @return gas resistance in Ω, or NaN if the reading is invalid
     * @throws IOException on I²C error
     */
    fun gasResistance(): Double {
        val raw = readRawData()
        val gasAdc = ((raw[11].toInt() and 0xFF) shl 2) or ((raw[12].toInt() and 0xFF) shr 6)
        val gasRange = raw[12].toInt() and 0x0F
        lastGasValid = (raw[12].toInt() shr 5) and 1 == 1
        lastHeatStable = (raw[12].toInt() shr 4) and 1 == 1
        if (!lastGasValid || !lastHeatStable) return Double.NaN
        return compensateGasResistance(gasAdc, gasRange)
    }
}
