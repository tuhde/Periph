package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME680 — full driver. Extends [Bme680Minimal] with oversampling control
 * for all three TPH channels, IIR filter configuration, multi-profile heater
 * management, ambient-temperature override, combined read-all, gas-validity
 * and heater-stability flags, status polling, chip ID read-back, and soft
 * reset.
 *
 * ## Oversampling constants
 * [OSRS_SKIP], [OSRS_X1], [OSRS_X2], [OSRS_X4], [OSRS_X8], [OSRS_X16]
 *
 * ## Mode constants
 * [MODE_SLEEP], [MODE_FORCED]
 *
 * ## Filter constants
 * [FILTER_0], [FILTER_1], [FILTER_3], [FILTER_7], [FILTER_15], [FILTER_31],
 * [FILTER_63], [FILTER_127]
 *
 * ## Status flags
 * [STATUS_NEW_DATA], [STATUS_GAS_MEASURING], [STATUS_MEASURING],
 * [STATUS_GAS_VALID], [STATUS_HEATER_STABLE]
 */
class Bme680Full @JvmOverloads constructor(
    transport: Transport,
    addr: Int = 0x76
) : Bme680Minimal(transport, addr) {

    /**
     * Combined reading from a single TPHG cycle.
     *
     * @property temperatureC temperature in °C
     * @property pressureHpa pressure in hPa
     * @property humidityPct humidity in %RH
     * @property gasResistanceOhm gas resistance in Ω, or NaN if invalid
     */
    data class Reading(
        val temperatureC: Double,
        val pressureHpa: Double,
        val humidityPct: Double,
        val gasResistanceOhm: Double
    )

    companion object {
        const val OSRS_SKIP = 0
        const val OSRS_X1   = 1
        const val OSRS_X2   = 2
        const val OSRS_X4   = 3
        const val OSRS_X8   = 4
        const val OSRS_X16  = 5

        const val MODE_SLEEP  = 0
        const val MODE_FORCED = 1

        const val FILTER_0   = 0
        const val FILTER_1   = 1
        const val FILTER_3   = 2
        const val FILTER_7   = 3
        const val FILTER_15  = 4
        const val FILTER_31  = 5
        const val FILTER_63  = 6
        const val FILTER_127 = 7

        const val STATUS_NEW_DATA      = 0x80
        const val STATUS_GAS_MEASURING = 0x40
        const val STATUS_MEASURING     = 0x20
        const val STATUS_GAS_VALID     = 0x20
        const val STATUS_HEATER_STABLE = 0x10

        private const val REG_MEAS_STATUS = 0x1D
        private const val DEFAULT_SEA_LEVEL_HPA = 1013.25
    }

    private var activeProfile: Int = 0
    private val heaterProfileTempC = IntArray(10) { 320 }
    private val heaterProfileDurationMs = IntArray(10) { 150 }

    /**
     * Configure oversampling, operating mode, and IIR filter in one call.
     *
     * Writes ctrl_hum before ctrl_meas as required by the chip. The mode
     * should be [MODE_SLEEP] or [MODE_FORCED].
     *
     * @param osrsT temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP pressure oversampling (0–5, use OSRS_* constants)
     * @param osrsH humidity oversampling (0–5, use OSRS_* constants)
     * @param mode operating mode (use MODE_* constants)
     * @param filter IIR filter coefficient (0–7, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    fun configure(osrsT: Int, osrsP: Int, osrsH: Int, mode: Int, filter: Int) {
        this.osrsT = osrsT
        this.osrsP = osrsP
        this.osrsH = osrsH
        this.filterCoeff = filter
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), (osrsH and 0x07).toByte()))
        val meas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or (mode and 0x03)
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), meas.toByte()))
        transport.write(byteArrayOf(REG_CONFIG.toByte(), ((filter and 0x07) shl 2).toByte()))
    }

    /**
     * Update the temperature, pressure, and humidity oversampling settings.
     *
     * Writes ctrl_hum then ctrl_meas to ensure humidity oversampling latches.
     *
     * @param osrsT temperature oversampling (0–5, use OSRS_* constants)
     * @param osrsP pressure oversampling (0–5, use OSRS_* constants)
     * @param osrsH humidity oversampling (0–5, use OSRS_* constants)
     * @throws IOException on I²C error
     */
    fun setOversampling(osrsT: Int, osrsP: Int, osrsH: Int) {
        this.osrsT = osrsT
        this.osrsP = osrsP
        this.osrsH = osrsH
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), (osrsH and 0x07).toByte()))
        val meas = ((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2) or 0x00
        transport.write(byteArrayOf(REG_CTRL_MEAS.toByte(), meas.toByte()))
    }

    /**
     * Set the IIR filter coefficient.
     *
     * Applies to temperature and pressure only (not humidity or gas).
     *
     * @param coeff filter coefficient (0–7, use FILTER_* constants)
     * @throws IOException on I²C error
     */
    fun setFilter(coeff: Int) {
        this.filterCoeff = coeff
        transport.write(byteArrayOf(REG_CONFIG.toByte(), ((coeff and 0x07) shl 2).toByte()))
    }

    /**
     * Configure heater profile 0 and activate it.
     *
     * Computes the heater resistance from the target temperature and the
     * current ambient temperature estimate, encodes the gas wait duration,
     * writes both registers, and selects profile 0 in ctrl_gas_1.
     *
     * @param tempC target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    fun setHeater(tempC: Int, durationMs: Int) {
        setHeaterProfile(0, tempC, durationMs)
        selectHeaterProfile(0)
    }

    /**
     * Configure one of the 10 heater profiles with a target temperature
     * and duration.
     *
     * Does not activate the profile — call [selectHeaterProfile] to use it.
     *
     * @param index heater profile index (0–9)
     * @param tempC target heater temperature in °C
     * @param durationMs heater on-time in ms (1–4032)
     * @throws IOException on I²C error
     */
    fun setHeaterProfile(index: Int, tempC: Int, durationMs: Int) {
        heaterProfileTempC[index] = tempC
        heaterProfileDurationMs[index] = durationMs
        configureHeaterProfile(index, tempC, durationMs)
    }

    /**
     * Select which heater profile to use in the next forced-mode cycle.
     *
     * Updates nb_conv in ctrl_gas_1 and sets the active profile index.
     *
     * @param index heater profile index (0–9)
     * @throws IOException on I²C error
     */
    fun selectHeaterProfile(index: Int) {
        activeProfile = index
        ctrlGas1 = (ctrlGas1 and 0xF0) or (index and 0x0F)
        transport.write(byteArrayOf(REG_CTRL_GAS_1.toByte(), ctrlGas1.toByte()))
    }

    /**
     * Enable or disable the gas conversion in the next forced-mode cycle.
     *
     * @param enabled true to enable gas measurement, false to skip it
     * @throws IOException on I²C error
     */
    fun setGasEnabled(enabled: Boolean) {
        ctrlGas1 = if (enabled) ctrlGas1 or 0x10 else ctrlGas1 and 0xEF
        transport.write(byteArrayOf(REG_CTRL_GAS_1.toByte(), ctrlGas1.toByte()))
    }

    /**
     * Turn the heater off or re-enable it.
     *
     * When off, the heater override bit in ctrl_gas_0 prevents any heater
     * activation regardless of the heater profile settings.
     *
     * @param off true to disable the heater, false to re-enable
     * @throws IOException on I²C error
     */
    fun setHeaterOff(off: Boolean) {
        val value = if (off) 0x08 else 0x00
        transport.write(byteArrayOf(REG_CTRL_GAS_0.toByte(), value.toByte()))
    }

    /**
     * Override the ambient temperature used for heater-resistance calculation.
     *
     * Also recomputes and rewrites the active heater profile's resistance
     * register using the new ambient temperature.
     *
     * @param tempC ambient temperature in °C
     * @throws IOException on I²C error
     */
    fun setAmbientTemperature(tempC: Double) {
        ambientTemp = tempC
        val resHeat = calcHeaterResistance(
            heaterProfileTempC[activeProfile],
            tempC.toInt()
        )
        transport.write(
            byteArrayOf(
                (REG_RES_HEAT_BASE + activeProfile).toByte(),
                resHeat.toByte()
            )
        )
    }

    /**
     * Read all four sensor values from a single TPHG cycle.
     *
     * More efficient than calling [temperature], [pressure], [humidity], and
     * [gasResistance] separately, which each trigger their own cycle.
     *
     * @return [Reading] containing temperature, pressure, humidity, and gas resistance
     * @throws IOException on I²C error
     */
    fun readAll(): Reading {
        val raw = readRawData()
        val adcP = ((raw[0].toInt() and 0xFF) shl 12) or
                ((raw[1].toInt() and 0xFF) shl 4) or
                ((raw[2].toInt() and 0xFF) shr 4)
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                ((raw[4].toInt() and 0xFF) shl 4) or
                ((raw[5].toInt() and 0xFF) shr 4)
        val humAdc = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        val gasAdc = ((raw[11].toInt() and 0xFF) shl 2) or ((raw[12].toInt() and 0xFF) shr 6)
        val gasRange = raw[12].toInt() and 0x0F
        lastGasValid = (raw[12].toInt() shr 5) and 1 == 1
        lastHeatStable = (raw[12].toInt() shr 4) and 1 == 1

        val t = compensateTemperature(adcT)
        ambientTemp = t
        val p = compensatePressure(adcP)
        val h = compensateHumidity(humAdc)
        val g = if (lastGasValid && lastHeatStable) {
            compensateGasResistance(gasAdc, gasRange)
        } else {
            Double.NaN
        }
        return Reading(t, p, h, g)
    }

    /**
     * Check whether the most recent gas measurement was valid.
     *
     * The gas_valid_r flag in gas_r_lsb is set when the measurement slot
     * produced a real result (not a dummy slot).
     *
     * @return true if the last gas reading was valid
     */
    fun gasValid(): Boolean = lastGasValid

    /**
     * Check whether the heater reached its target temperature in the most
     * recent gas measurement.
     *
     * The heat_stab_r flag in gas_r_lsb is set when the heater stabilized
     * within the configured gas_wait duration.
     *
     * @return true if the heater stabilized during the last measurement
     */
    fun heaterStable(): Boolean = lastHeatStable

    /**
     * Read the measurement status register (0x1D).
     *
     * Bit 7 ([STATUS_NEW_DATA]) is set when new TPH data is available.
     * Bit 6 ([STATUS_GAS_MEASURING]) is set during gas conversion.
     * Bit 5 ([STATUS_MEASURING]) is set during any conversion.
     *
     * @return raw status byte
     * @throws IOException on I²C error
     */
    fun status(): Int {
        val b = transport.writeRead(byteArrayOf(REG_MEAS_STATUS.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Compute altitude using the default sea-level pressure (1013.25 hPa).
     *
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(): Double = altitude(DEFAULT_SEA_LEVEL_HPA)

    /**
     * Compute altitude for a given sea-level reference pressure.
     *
     * Uses the barometric formula:
     * `altitude_m = 44330 × (1 − (pressure / seaLevelHpa)^(1/5.255))`
     *
     * @param seaLevelHpa reference sea-level pressure in hPa
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(seaLevelHpa: Double): Double {
        val p = pressure()
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255))
    }

    /**
     * Compute the dew point from the current temperature and humidity using
     * the Magnus-Tetens approximation.
     *
     * @return dew point in °C
     * @throws IOException on I²C error
     */
    fun dewPoint(): Double {
        val raw = readRawData()
        val adcT = ((raw[3].toInt() and 0xFF) shl 12) or
                ((raw[4].toInt() and 0xFF) shl 4) or
                ((raw[5].toInt() and 0xFF) shr 4)
        val humAdc = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        val t = compensateTemperature(adcT)
        val h = compensateHumidity(humAdc)
        val a = 17.625
        val b = 243.04
        val gamma = Math.log(h / 100.0) + (a * t) / (b + t)
        return (b * gamma) / (a - gamma)
    }

    /**
     * Read the chip ID register (0xD0).
     *
     * Expected value is 0x61 for BME680.
     *
     * @return chip ID byte
     * @throws IOException on I²C error
     */
    fun chipId(): Int {
        val b = transport.writeRead(byteArrayOf(REG_CHIP_ID.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    /**
     * Perform a soft reset, reload calibration, and re-apply the current
     * configuration including the active heater profile.
     *
     * Writes 0xB6 to register 0xE0, waits 2 ms for the chip to complete
     * its power-on sequence, re-reads calibration NVM, and restores all
     * control registers and the active heater profile.
     *
     * @throws IOException on I²C error
     */
    fun reset() {
        transport.write(byteArrayOf(REG_SOFT_RESET.toByte(), 0xB6.toByte()))
        Thread.sleep(2)
        readCalibration()
        transport.write(byteArrayOf(REG_CTRL_HUM.toByte(), (osrsH and 0x07).toByte()))
        transport.write(
            byteArrayOf(
                REG_CTRL_MEAS.toByte(),
                (((osrsT and 0x07) shl 5) or ((osrsP and 0x07) shl 2)).toByte()
            )
        )
        transport.write(byteArrayOf(REG_CONFIG.toByte(), ((filterCoeff and 0x07) shl 2).toByte()))
        configureHeaterProfile(
            activeProfile,
            heaterProfileTempC[activeProfile],
            heaterProfileDurationMs[activeProfile]
        )
        transport.write(byteArrayOf(REG_CTRL_GAS_1.toByte(), ctrlGas1.toByte()))
    }
}
