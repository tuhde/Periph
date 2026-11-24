package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection

/**
 * MS5611 — full driver: single values, raw codes, oversampling, non-blocking conversion, PROM
 * access and altitude. Extends [Ms5611Minimal].
 */
class Ms5611Full(connection: Connection) : Ms5611Minimal(connection) {
    companion object {
        /** Conversion kind: D1, pressure. */
        const val PRESSURE = 0

        /** Conversion kind: D2, temperature. */
        const val TEMPERATURE = 1
    }

    private fun osrIndexOf(osr: Int): Int {
        val i = OSR_VALUES.indexOf(osr)
        require(i >= 0) { "OSR must be 256, 512, 1024, 2048 or 4096" }
        return i
    }

    /**
     * Convert D2 and D1 at the current oversampling ratio.
     *
     * @return {d1 (pressure), d2 (temperature)}, both 24-bit
     */
    fun readRaw(): IntArray {
        val d2 = convert(1)
        val d1 = convert(0)
        return intArrayOf(d1, d2)
    }

    /** Measure and return the compensated pressure in Pa. */
    fun readPressure(): Double = read()[1]

    /** Convert D2 only and return the temperature in °C (with the low-temperature correction). */
    fun readTemperature(): Double = compute(0, convert(1), true)[0]

    /**
     * Set the oversampling ratio used for both conversions.
     *
     * @param osr 256, 512, 1024, 2048 or 4096
     * @throws IllegalArgumentException for any other value
     */
    fun setOversampling(osr: Int) {
        osrIndex = osrIndexOf(osr)
    }

    /** Current oversampling ratio (256 … 4096). */
    fun getOversampling(): Int = OSR_VALUES[osrIndex]

    /**
     * Maximum conversion time in seconds for an oversampling ratio (the current one if [osr] is null).
     *
     * @throws IllegalArgumentException for an invalid ratio
     */
    fun conversionTime(osr: Int? = null): Double =
        T_CONV_US[if (osr == null) osrIndex else osrIndexOf(osr)] / 1.0e6

    /**
     * Write a conversion command without waiting (non-blocking use); wait at least [conversionTime]
     * before [readAdc].
     *
     * @param kind [PRESSURE] or [TEMPERATURE]
     * @param osr oversampling ratio; null for the current one
     */
    fun startConversion(kind: Int, osr: Int? = null) {
        start(kind, if (osr == null) osrIndex else osrIndexOf(osr))
    }

    /**
     * ADC read command plus 3 bytes: the result of the last conversion. Only valid after the
     * conversion time; a read during the conversion or a repeated read returns 0.
     *
     * @return 24-bit result
     * @throws java.io.IOException on bus error or a result of 0
     */
    fun readAdc(): Int = readAdcValue()

    /**
     * Pure computation with the stored coefficients (no bus access).
     *
     * @param secondOrder false for the first-order result only
     * @return {temperature in °C, pressure in Pa}
     */
    fun compensate(d1: Int, d2: Int, secondOrder: Boolean = true): DoubleArray = compute(d1, d2, secondOrder)

    /** Reset (0x1E), wait 3 ms and reload and verify the PROM. */
    fun reset() = init()

    /** Read the eight PROM words from the chip. */
    fun readProm(): IntArray = readPromWords()

    /** The calibration coefficients {C1, …, C6} read at init. */
    fun coefficients(): IntArray = intArrayOf(prom[1], prom[2], prom[3], prom[4], prom[5], prom[6])

    /** Verify the CRC-4 of the PROM words read at init (no bus access). */
    fun promCrcOk(): Boolean = crc4(prom) == (prom[7] and 0x0F)

    /** Serial code (PROM word 7, bits 15:4). */
    fun serialCode(): Int = (prom[7] shr 4) and 0x0FFF

    /**
     * Altitude in metres from one pressure reading (international barometric formula).
     *
     * @param seaLevelPa reference sea-level pressure in pascals
     */
    fun altitude(seaLevelPa: Double = 101325.0): Double =
        44330.0 * (1.0 - Math.pow(readPressure() / seaLevelPa, 1.0 / 5.255))
}
