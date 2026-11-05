package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic

import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * ADS1013 full interface — extends {@link ADS1013Minimal} with data-rate selection and
 * continuous conversion.
 */
@CompileStatic
class ADS1013Full extends ADS1013Minimal {

    /** Data rate 128&nbsp;SPS. */
    public static final int DR_128  = 0
    /** Data rate 250&nbsp;SPS. */
    public static final int DR_250  = 1
    /** Data rate 490&nbsp;SPS. */
    public static final int DR_490  = 2
    /** Data rate 920&nbsp;SPS. */
    public static final int DR_920  = 3
    /** Data rate 1600&nbsp;SPS (default). */
    public static final int DR_1600 = 4
    /** Data rate 2400&nbsp;SPS. */
    public static final int DR_2400 = 5
    /** Data rate 3300&nbsp;SPS. */
    public static final int DR_3300 = 6

    private static final int CONFIG_RESET = 0x8583

    /**
     * Construct the driver. Performs no I²C traffic.
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    ADS1013Full(RegisterConnection connection) {
        super(connection)
    }

    private void writeConfig() {
        writeReg(REG_CONFIG, configWord(false, continuous))
    }

    /**
     * Fixed full-scale range.
     *
     * @return always 2.048 (volts)
     */
    double fullScale() {
        return 2.048
    }

    /**
     * Size of one conversion code.
     *
     * @return always 0.001 (volts)
     */
    double lsb() {
        return LSB
    }

    /**
     * Select the conversion data rate.
     *
     * @param dr {@link #DR_128} … {@link #DR_3300}
     * @throws IllegalArgumentException if {@code dr} is outside 0–6 (7 aliases 3300&nbsp;SPS)
     * @throws IOException on bus error
     */
    void setDataRate(int dr) {
        if (dr < 0 || dr > DR_3300) throw new IllegalArgumentException("dr must be 0-6")
        this.dr = dr
        if (continuous) writeConfig()
    }

    /**
     * Read the configured data-rate code.
     *
     * @return {@code DR} code 0–6
     */
    int getDataRate() {
        return dr
    }

    /**
     * Current data rate.
     *
     * @return 128, 250, 490, 920, 1600, 2400 or 3300 samples per second
     */
    int dataRateSps() {
        return SPS[dr]
    }

    /**
     * Start continuous conversion ({@code MODE} = 0) at the current data rate.
     *
     * @throws IOException on bus error
     */
    void startContinuous() {
        continuous = true
        writeConfig()
    }

    /**
     * Read the most recent completed result without touching Config (valid at least 1/DR after
     * {@link #startContinuous()}).
     *
     * @return 12-bit two's-complement code, −2048…2047
     * @throws IOException on bus error
     */
    int readContinuousRaw() {
        return readConversion()
    }

    /**
     * Read the most recent completed result in volts.
     *
     * @return {@code readContinuousRaw() × lsb()}
     * @throws IOException on bus error
     */
    double readContinuous() {
        return readConversion() * LSB
    }

    /**
     * Stop continuous conversion ({@code MODE} = 1, single-shot / power-down).
     *
     * @throws IOException on bus error
     */
    void stopContinuous() {
        continuous = false
        writeConfig()
    }

    /**
     * Report whether a single-shot conversion is running ({@code OS} = 0; only meaningful in
     * single-shot mode).
     *
     * @return {@code true} while converting
     * @throws IOException on bus error
     */
    boolean isConverting() {
        return (readReg(REG_CONFIG) & CFG_OS) == 0
    }

    /**
     * Read the raw CONFIG register.
     *
     * @return 16-bit CONFIG value
     * @throws IOException on bus error
     */
    int readConfig() {
        return readReg(REG_CONFIG)
    }

    /**
     * Write {@code CONFIG} = 0x8583 back and clear the cached settings. Not an I²C general-call
     * reset.
     *
     * @throws IOException on bus error
     */
    void reset() {
        writeReg(REG_CONFIG, CONFIG_RESET)
        dr = DR_1600
        continuous = false
    }
}
