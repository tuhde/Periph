package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * INA219 — full driver. Extends {@link Ina219Minimal} with ADC configuration,
 * conversion-ready and overflow status, software reset, power-down, wake, and
 * one-shot trigger.
 *
 * <h2>PGA constants</h2>
 * {@link #PGA_1}, {@link #PGA_2}, {@link #PGA_4}, {@link #PGA_8}
 *
 * <h2>Bus range constants</h2>
 * {@link #BRNG_16V}, {@link #BRNG_32V}
 *
 * <h2>ADC constants</h2>
 * {@link #ADC_9BIT}–{@link #ADC_12BIT}, {@link #ADC_AVG_2}–{@link #ADC_AVG_128}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_POWERDOWN}–{@link #MODE_SHUNT_BUS_CONT}
 */
@CompileStatic
class Ina219Full extends Ina219Minimal {

    // PGA gain
    static final int PGA_1 = 0
    static final int PGA_2 = 1
    static final int PGA_4 = 2
    static final int PGA_8 = 3

    // Bus voltage range
    static final int BRNG_16V = 0
    static final int BRNG_32V = 1

    // ADC resolution / averaging
    static final int ADC_9BIT    = 0
    static final int ADC_10BIT   = 1
    static final int ADC_11BIT   = 2
    static final int ADC_12BIT   = 3
    static final int ADC_AVG_2   = 9
    static final int ADC_AVG_4   = 10
    static final int ADC_AVG_8   = 11
    static final int ADC_AVG_16  = 12
    static final int ADC_AVG_32  = 13
    static final int ADC_AVG_64  = 14
    static final int ADC_AVG_128 = 15

    // Operating modes
    static final int MODE_POWERDOWN      = 0
    static final int MODE_SHUNT_TRIG     = 1
    static final int MODE_BUS_TRIG       = 2
    static final int MODE_SHUNT_BUS_TRIG = 3
    static final int MODE_ADC_OFF        = 4
    static final int MODE_SHUNT_CONT     = 5
    static final int MODE_BUS_CONT       = 6
    static final int MODE_SHUNT_BUS_CONT = 7

    /** Cached configuration register value (hardware default on power-on). */
    private int config = 0x399F

    /**
     * Construct the full driver.
     *
     * @param transport  I²C transport bound to the INA219 device address
     * @param rShunt     shunt resistance in Ω (default 0.1)
     * @param maxCurrent maximum expected current in A (default 2.0)
     */
    Ina219Full(Transport transport, double rShunt = 0.1, double maxCurrent = 2.0) {
        super(transport, rShunt, maxCurrent)
    }

    /**
     * Write the configuration register.
     *
     * <p>All five fields are packed into the 16-bit register and written
     * atomically. The value is cached so that {@link #reset()},
     * {@link #shutdown()}, and {@link #wake()} can restore or modify it.
     *
     * @param brng bus voltage range (0 = 16 V, 1 = 32 V)
     * @param pga  PGA gain (0–3)
     * @param badc bus ADC resolution/averaging (0–3 or 9–15)
     * @param sadc shunt ADC resolution/averaging (0–3 or 9–15)
     * @param mode operating mode (0–7)
     */
    void configure(int brng, int pga, int badc, int sadc, int mode) {
        config = ((brng & 0x01) << 13) |
                 ((pga  & 0x03) << 11) |
                 ((badc & 0x0F) << 7)  |
                 ((sadc & 0x0F) << 3)  |
                 (mode  & 0x07)
        writeReg(REG_CONFIG, config)
    }

    /**
     * Check whether a new conversion result is available.
     *
     * <p>Reads the Bus Voltage register and tests the CNVR bit (bit 1).
     *
     * @return {@code true} when a conversion is ready to be read
     */
    boolean conversionReady() {
        (readReg(REG_BUS_V) & 0x02) != 0
    }

    /**
     * Check the math overflow flag.
     *
     * <p>Reads the Bus Voltage register and tests the OVF bit (bit 0).
     *
     * @return {@code true} when an arithmetic overflow has occurred
     */
    boolean overflow() {
        (readReg(REG_BUS_V) & 0x01) != 0
    }

    /**
     * Perform a software reset and restore the configuration and calibration.
     *
     * <p>Sets the RST bit (bit 15), then re-writes the cached configuration and
     * recomputes and re-writes the calibration register.
     */
    void reset() {
        writeReg(REG_CONFIG, 0x8000)
        writeReg(REG_CONFIG, config)
        int cal = (int) (0.04096 / (currentLsb * rShunt)) & 0xFFFE
        writeReg(REG_CALIBRATE, cal)
    }

    /**
     * Enter power-down mode (MODE bits = 000).
     *
     * <p>Writes the cached configuration with the MODE field replaced by
     * {@code MODE_POWERDOWN}. Call {@link #wake()} to exit.
     */
    void shutdown() {
        writeReg(REG_CONFIG, (config & ~0x07) | MODE_POWERDOWN)
    }

    /**
     * Exit power-down mode by restoring the cached configuration.
     *
     * <p>Re-writes the full cached configuration register, reinstating the
     * original operating mode. Typically called after {@link #shutdown()}.
     */
    void wake() {
        writeReg(REG_CONFIG, config)
    }

    /**
     * Trigger a one-shot conversion by re-writing the configuration register.
     *
     * <p>In triggered modes re-writing the configuration register initiates a
     * new single conversion. Harmless in continuous mode.
     */
    void trigger() {
        writeReg(REG_CONFIG, config)
    }
}
