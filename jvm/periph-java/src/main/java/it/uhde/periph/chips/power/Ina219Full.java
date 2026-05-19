package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * INA219 — full driver. Extends {@link Ina219Minimal} with ADC configuration,
 * conversion-ready and overflow status, software reset, power-down, wake, and
 * one-shot trigger.
 *
 * <h2>Configuration register layout (0x00)</h2>
 * <pre>
 * Bit 15    : RST  — software reset (self-clearing)
 * Bit 13    : BRNG — bus voltage range (0 = 16 V, 1 = 32 V)
 * Bits 12:11: PG   — PGA gain (00 = /1, 01 = /2, 10 = /4, 11 = /8)
 * Bits 10:7 : BADC — bus ADC resolution / averaging
 * Bits  6:3 : SADC — shunt ADC resolution / averaging
 * Bits  2:0 : MODE — operating mode
 * </pre>
 *
 * <h2>PGA constants</h2>
 * {@link #PGA_1}, {@link #PGA_2}, {@link #PGA_4}, {@link #PGA_8}
 *
 * <h2>Bus range constants</h2>
 * {@link #BRNG_16V}, {@link #BRNG_32V}
 *
 * <h2>ADC constants</h2>
 * {@link #ADC_9BIT}, {@link #ADC_10BIT}, {@link #ADC_11BIT}, {@link #ADC_12BIT},
 * {@link #ADC_AVG_2}, {@link #ADC_AVG_4}, {@link #ADC_AVG_8}, {@link #ADC_AVG_16},
 * {@link #ADC_AVG_32}, {@link #ADC_AVG_64}, {@link #ADC_AVG_128}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_POWERDOWN}, {@link #MODE_SHUNT_TRIG}, {@link #MODE_BUS_TRIG},
 * {@link #MODE_SHUNT_BUS_TRIG}, {@link #MODE_ADC_OFF}, {@link #MODE_SHUNT_CONT},
 * {@link #MODE_BUS_CONT}, {@link #MODE_SHUNT_BUS_CONT}
 */
public class Ina219Full extends Ina219Minimal {

    // ------------------------------------------------------------------
    // PGA gain (shunt voltage full-scale range)
    // ------------------------------------------------------------------

    /** PGA /1: ±40 mV full-scale shunt range. */
    public static final int PGA_1 = 0;
    /** PGA /2: ±80 mV full-scale shunt range. */
    public static final int PGA_2 = 1;
    /** PGA /4: ±160 mV full-scale shunt range. */
    public static final int PGA_4 = 2;
    /** PGA /8: ±320 mV full-scale shunt range (hardware default). */
    public static final int PGA_8 = 3;

    // ------------------------------------------------------------------
    // Bus voltage range
    // ------------------------------------------------------------------

    /** Bus voltage range: 16 V full-scale. */
    public static final int BRNG_16V = 0;
    /** Bus voltage range: 32 V full-scale (hardware default). */
    public static final int BRNG_32V = 1;

    // ------------------------------------------------------------------
    // ADC resolution / averaging (BADC and SADC fields)
    // ------------------------------------------------------------------

    /** ADC: 9-bit single sample (84 µs conversion time). */
    public static final int ADC_9BIT    = 0;
    /** ADC: 10-bit single sample (148 µs conversion time). */
    public static final int ADC_10BIT   = 1;
    /** ADC: 11-bit single sample (276 µs conversion time). */
    public static final int ADC_11BIT   = 2;
    /** ADC: 12-bit single sample (532 µs conversion time, hardware default). */
    public static final int ADC_12BIT   = 3;
    /** ADC: 2-sample average (1.06 ms). */
    public static final int ADC_AVG_2   = 9;
    /** ADC: 4-sample average (2.13 ms). */
    public static final int ADC_AVG_4   = 10;
    /** ADC: 8-sample average (4.26 ms). */
    public static final int ADC_AVG_8   = 11;
    /** ADC: 16-sample average (8.51 ms). */
    public static final int ADC_AVG_16  = 12;
    /** ADC: 32-sample average (17.02 ms). */
    public static final int ADC_AVG_32  = 13;
    /** ADC: 64-sample average (34.05 ms). */
    public static final int ADC_AVG_64  = 14;
    /** ADC: 128-sample average (68.10 ms). */
    public static final int ADC_AVG_128 = 15;

    // ------------------------------------------------------------------
    // Operating modes
    // ------------------------------------------------------------------

    /** Mode: power-down (low quiescent current, ADC powered off). */
    public static final int MODE_POWERDOWN      = 0;
    /** Mode: shunt voltage, triggered (single shot). */
    public static final int MODE_SHUNT_TRIG     = 1;
    /** Mode: bus voltage, triggered (single shot). */
    public static final int MODE_BUS_TRIG       = 2;
    /** Mode: shunt and bus voltage, triggered (single shot). */
    public static final int MODE_SHUNT_BUS_TRIG = 3;
    /** Mode: ADC off (power-down while configuration bits are held). */
    public static final int MODE_ADC_OFF        = 4;
    /** Mode: shunt voltage, continuous. */
    public static final int MODE_SHUNT_CONT     = 5;
    /** Mode: bus voltage, continuous. */
    public static final int MODE_BUS_CONT       = 6;
    /** Mode: shunt and bus voltage, continuous (hardware default). */
    public static final int MODE_SHUNT_BUS_CONT = 7;

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Cached configuration register value (hardware default on power-on). */
    private int config = 0x399F;

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /**
     * Construct the full driver.
     *
     * @param transport  I²C transport bound to the INA219 device address
     * @param rShunt     shunt resistance in Ω
     * @param maxCurrent maximum expected current in A
     * @throws IOException on I²C error
     */
    public Ina219Full(Transport transport, double rShunt, double maxCurrent) throws IOException {
        super(transport, rShunt, maxCurrent);
    }

    /**
     * Construct with default parameters (0.1 Ω shunt, 2.0 A max).
     *
     * @param transport I²C transport bound to the INA219 device address
     * @throws IOException on I²C error
     */
    public Ina219Full(Transport transport) throws IOException {
        super(transport, 0.1, 2.0);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * Write the configuration register.
     *
     * <p>All five fields are packed into the 16-bit register and written
     * atomically. The value is cached so that {@link #reset()},
     * {@link #shutdown()}, and {@link #wake()} can restore or modify it without
     * a read-modify-write bus cycle.
     *
     * @param brng bus voltage range (0 = 16 V, 1 = 32 V)
     * @param pga  PGA gain (0–3); see {@link #PGA_1}–{@link #PGA_8}
     * @param badc bus ADC resolution/averaging (0–3 or 9–15)
     * @param sadc shunt ADC resolution/averaging (0–3 or 9–15)
     * @param mode operating mode (0–7); see {@link #MODE_POWERDOWN}–{@link #MODE_SHUNT_BUS_CONT}
     * @throws IOException on I²C error
     */
    public void configure(int brng, int pga, int badc, int sadc, int mode) throws IOException {
        config = ((brng & 0x01) << 13)
               | ((pga  & 0x03) << 11)
               | ((badc & 0x0F) << 7)
               | ((sadc & 0x0F) << 3)
               | (mode  & 0x07);
        writeReg(REG_CONFIG, config);
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /**
     * Check whether a new conversion result is available.
     *
     * <p>Reads the Bus Voltage register and tests the CNVR bit (bit 1). In
     * triggered modes the bit is set when a complete measurement is ready and
     * cleared when a new conversion starts or the register is read.
     *
     * @return {@code true} when a conversion is ready to be read
     * @throws IOException on I²C error
     */
    public boolean conversionReady() throws IOException {
        return (readReg(REG_BUS_V) & 0x02) != 0;
    }

    /**
     * Check the math overflow flag.
     *
     * <p>Reads the Bus Voltage register and tests the OVF bit (bit 0). The flag
     * is set when the power or current calculation has exceeded the register
     * capacity; the current and power readings will be invalid until it clears.
     *
     * @return {@code true} when an arithmetic overflow has occurred
     * @throws IOException on I²C error
     */
    public boolean overflow() throws IOException {
        return (readReg(REG_BUS_V) & 0x01) != 0;
    }

    // ------------------------------------------------------------------
    // Power control
    // ------------------------------------------------------------------

    /**
     * Perform a software reset and restore the configuration and calibration.
     *
     * <p>Sets the RST bit (bit 15), which triggers an internal power-on reset.
     * The hardware resets both the configuration and calibration registers to
     * their default values; this method re-writes the cached configuration and
     * recomputes and re-writes the calibration register to restore the driver
     * to its pre-reset state.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        writeReg(REG_CONFIG, 0x8000);
        writeReg(REG_CONFIG, config);
        int cal = (int) (0.04096 / (currentLsb * rShunt)) & 0xFFFE;
        writeReg(REG_CALIBRATE, cal);
    }

    /**
     * Enter power-down mode (MODE bits = 000).
     *
     * <p>Writes the cached configuration with the MODE field replaced by
     * {@link #MODE_POWERDOWN}. The remaining configuration bits are preserved.
     * Call {@link #wake()} to exit.
     *
     * @throws IOException on I²C error
     */
    public void shutdown() throws IOException {
        writeReg(REG_CONFIG, (config & ~0x07) | MODE_POWERDOWN);
    }

    /**
     * Exit power-down mode by restoring the cached configuration.
     *
     * <p>Re-writes the full cached configuration register, reinstating the
     * original operating mode. Typically called after {@link #shutdown()}.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        writeReg(REG_CONFIG, config);
    }

    /**
     * Trigger a one-shot conversion by re-writing the configuration register.
     *
     * <p>In triggered modes ({@link #MODE_SHUNT_TRIG}, {@link #MODE_BUS_TRIG},
     * {@link #MODE_SHUNT_BUS_TRIG}) re-writing the configuration register
     * initiates a new single conversion. Calling this method in continuous mode
     * has no visible effect but is harmless.
     *
     * @throws IOException on I²C error
     */
    public void trigger() throws IOException {
        writeReg(REG_CONFIG, config);
    }
}
