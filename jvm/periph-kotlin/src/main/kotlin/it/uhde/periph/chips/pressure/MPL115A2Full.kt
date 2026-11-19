package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.pow

/**
 * MPL115A2 — full driver. Extends [MPL115A2Minimal] with a combined reading, raw ADC counts,
 * coefficient access, the pure compensation function, hPa and altitude helpers.
 *
 * ## Altitude formula
 * `altitude_m = 44330.0 × (1.0 − (pressure_Pa / seaLevelPa)^(1/5.255))`
 *
 * @param connection I²C connection bound to address 0x60
 */
class MPL115A2Full(connection: RegisterConnection) : MPL115A2Minimal(connection) {

    /**
     * Run one conversion and return both values.
     *
     * @return `{pressure_pa, temperature_c}`
     * @throws IOException on I²C error
     */
    fun read(): DoubleArray {
        val raw = convert()
        return doubleArrayOf(compute(raw[0], raw[1]), temperatureC(raw[1]))
    }

    /**
     * Run one conversion and return the raw 10-bit ADC counts.
     *
     * @return `{padc, tadc}`, each 0–1023
     * @throws IOException on I²C error
     */
    fun readRaw(): IntArray = convert()

    /**
     * Return the cached compensation coefficients.
     *
     * @return `{a0, b1, b2, c12}`
     */
    fun coefficients(): DoubleArray = doubleArrayOf(a0, b1, b2, c12)

    /**
     * Re-read the coefficient block (e.g. after a power cycle).
     *
     * @throws IOException on I²C error
     */
    fun reloadCoefficients() = loadCoefficients()

    /**
     * Compute pressure from raw counts and the cached coefficients (no bus access).
     *
     * @param padc 10-bit pressure ADC count
     * @param tadc 10-bit temperature ADC count
     * @return pressure in Pa
     */
    fun compensate(padc: Int, tadc: Int): Double = compute(padc, tadc)

    /**
     * Run one conversion and return the pressure in hectopascals.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    fun pressureHpa(): Double = readPressure() / 100.0

    /**
     * Compute altitude from a fresh pressure reading (barometric formula, informative).
     *
     * @param seaLevelPa reference sea-level pressure in Pa (default 101325)
     * @return altitude in m
     * @throws IOException on I²C error
     */
    fun altitude(seaLevelPa: Double = 101325.0): Double {
        val p = readPressure()
        return 44330.0 * (1.0 - (p / seaLevelPa).pow(1.0 / 5.255))
    }
}
