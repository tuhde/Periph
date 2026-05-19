package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport

/**
 * INA219 — full driver. Extends [Ina219Minimal] with ADC configuration,
 * conversion-ready and overflow status, software reset, power-down, wake, and
 * one-shot trigger.
 *
 * ## Configuration register layout (0x00)
 * ```
 * Bit 15    : RST  — software reset (self-clearing)
 * Bit 13    : BRNG — bus voltage range (0 = 16 V, 1 = 32 V)
 * Bits 12:11: PG   — PGA gain (00 = /1, 01 = /2, 10 = /4, 11 = /8)
 * Bits 10:7 : BADC — bus ADC resolution / averaging
 * Bits  6:3 : SADC — shunt ADC resolution / averaging
 * Bits  2:0 : MODE — operating mode
 * ```
 *
 * @param transport  I²C transport bound to the INA219 device address
 * @param rShunt     shunt resistance in Ω (default 0.1)
 * @param maxCurrent maximum expected current in A (default 2.0)
 */
class Ina219Full @JvmOverloads constructor(
    transport: Transport,
    rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) : Ina219Minimal(transport, rShunt, maxCurrent) {

    companion object {
        // PGA gain
        /** PGA /1: ±40 mV full-scale shunt range. */
        const val PGA_1 = 0
        /** PGA /2: ±80 mV full-scale shunt range. */
        const val PGA_2 = 1
        /** PGA /4: ±160 mV full-scale shunt range. */
        const val PGA_4 = 2
        /** PGA /8: ±320 mV full-scale shunt range (hardware default). */
        const val PGA_8 = 3

        // Bus voltage range
        /** Bus voltage range: 16 V full-scale. */
        const val BRNG_16V = 0
        /** Bus voltage range: 32 V full-scale (hardware default). */
        const val BRNG_32V = 1

        // ADC resolution / averaging
        /** ADC: 9-bit single sample (84 µs). */
        const val ADC_9BIT    = 0
        /** ADC: 10-bit single sample (148 µs). */
        const val ADC_10BIT   = 1
        /** ADC: 11-bit single sample (276 µs). */
        const val ADC_11BIT   = 2
        /** ADC: 12-bit single sample (532 µs, hardware default). */
        const val ADC_12BIT   = 3
        /** ADC: 2-sample average (1.06 ms). */
        const val ADC_AVG_2   = 9
        /** ADC: 4-sample average (2.13 ms). */
        const val ADC_AVG_4   = 10
        /** ADC: 8-sample average (4.26 ms). */
        const val ADC_AVG_8   = 11
        /** ADC: 16-sample average (8.51 ms). */
        const val ADC_AVG_16  = 12
        /** ADC: 32-sample average (17.02 ms). */
        const val ADC_AVG_32  = 13
        /** ADC: 64-sample average (34.05 ms). */
        const val ADC_AVG_64  = 14
        /** ADC: 128-sample average (68.10 ms). */
        const val ADC_AVG_128 = 15

        // Operating modes
        /** Mode: power-down (low quiescent current, ADC powered off). */
        const val MODE_POWERDOWN      = 0
        /** Mode: shunt voltage, triggered (single shot). */
        const val MODE_SHUNT_TRIG     = 1
        /** Mode: bus voltage, triggered (single shot). */
        const val MODE_BUS_TRIG       = 2
        /** Mode: shunt and bus voltage, triggered (single shot). */
        const val MODE_SHUNT_BUS_TRIG = 3
        /** Mode: ADC off (power-down while configuration bits are held). */
        const val MODE_ADC_OFF        = 4
        /** Mode: shunt voltage, continuous. */
        const val MODE_SHUNT_CONT     = 5
        /** Mode: bus voltage, continuous. */
        const val MODE_BUS_CONT       = 6
        /** Mode: shunt and bus voltage, continuous (hardware default). */
        const val MODE_SHUNT_BUS_CONT = 7
    }

    /** Cached configuration register value. */
    private var config: Int = 0x399F

    /**
     * Write the configuration register.
     *
     * All five fields are packed into the 16-bit register and written
     * atomically. The value is cached so [reset], [shutdown], and [wake]
     * can restore or modify it without a read-modify-write bus cycle.
     *
     * @param brng bus voltage range (0 = 16 V, 1 = 32 V)
     * @param pga  PGA gain (0–3); see [PGA_1]–[PGA_8]
     * @param badc bus ADC resolution/averaging (0–3 or 9–15)
     * @param sadc shunt ADC resolution/averaging (0–3 or 9–15)
     * @param mode operating mode (0–7); see [MODE_POWERDOWN]–[MODE_SHUNT_BUS_CONT]
     */
    fun configure(brng: Int, pga: Int, badc: Int, sadc: Int, mode: Int) {
        config = ((brng and 0x01) shl 13) or
                 ((pga  and 0x03) shl 11) or
                 ((badc and 0x0F) shl  7) or
                 ((sadc and 0x0F) shl  3) or
                 (mode  and 0x07)
        writeReg(REG_CONFIG, config)
    }

    /**
     * Check whether a new conversion result is available.
     *
     * Reads the Bus Voltage register and tests the CNVR bit (bit 1). In
     * triggered modes the bit is set when a complete measurement is ready.
     *
     * @return `true` when a conversion is ready to be read
     */
    fun conversionReady(): Boolean = (readReg(REG_BUS_V) and 0x02) != 0

    /**
     * Check the math overflow flag.
     *
     * Reads the Bus Voltage register and tests the OVF bit (bit 0). The flag
     * is set when the power or current calculation has exceeded register
     * capacity; current and power readings will be invalid until it clears.
     *
     * @return `true` when an arithmetic overflow has occurred
     */
    fun overflow(): Boolean = (readReg(REG_BUS_V) and 0x01) != 0

    /**
     * Perform a software reset and restore the configuration and calibration.
     *
     * Sets the RST bit (bit 15), triggering an internal power-on reset.
     * Then re-writes the cached configuration and recomputes and re-writes the
     * calibration register to restore the driver to its pre-reset state.
     */
    fun reset() {
        writeReg(REG_CONFIG, 0x8000)
        writeReg(REG_CONFIG, config)
        val cal = (0.04096 / (currentLsb * rShunt)).toInt() and 0xFFFE
        writeReg(REG_CALIBRATE, cal)
    }

    /**
     * Enter power-down mode (MODE bits = 000).
     *
     * Writes the cached configuration with the MODE field replaced by
     * [MODE_POWERDOWN]. The remaining configuration bits are preserved.
     * Call [wake] to exit.
     */
    fun shutdown() {
        writeReg(REG_CONFIG, (config and 0x07.inv()) or MODE_POWERDOWN)
    }

    /**
     * Exit power-down mode by restoring the cached configuration.
     *
     * Re-writes the full cached configuration register, reinstating the
     * original operating mode. Typically called after [shutdown].
     */
    fun wake() {
        writeReg(REG_CONFIG, config)
    }

    /**
     * Trigger a one-shot conversion by re-writing the configuration register.
     *
     * In triggered modes ([MODE_SHUNT_TRIG], [MODE_BUS_TRIG],
     * [MODE_SHUNT_BUS_TRIG]) re-writing the configuration register initiates a
     * new single conversion. Calling this in continuous mode is harmless.
     */
    fun trigger() {
        writeReg(REG_CONFIG, config)
    }
}
