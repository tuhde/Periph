package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic

import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * ADS1113 full interface — extends {@link ADS1113Minimal} with data rate, continuous
 * conversion, status and reset.
 */
@CompileStatic
class ADS1113Full extends ADS1113Minimal {

    /** Data-rate codes ({@code DR[2:0]}). */
    public static final int DR_8 = 0, DR_16 = 1, DR_32 = 2, DR_64 = 3, DR_128 = 4, DR_250 = 5, DR_475 = 6, DR_860 = 7

    /**
     * Construct the driver; makes no bus traffic.
     *
     * @param connection I²C register connection pointing at the device
     */
    public ADS1113Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Run a single-shot conversion and return volts at the fixed ±2.048 V range.
     *
     * @return voltage AIN0 − AIN1 in V
     * @throws IOException on bus error
     */
    @Override
    public double readVoltage() throws IOException {
        return readRaw() * doLsb()
    }

    /**
     * Start continuous conversion at the current data rate ({@code MODE} ← 0).
     *
     * @throws IOException on bus error
     */
    public void startContinuous() throws IOException {
        doStartContinuous(0)
    }

    /** @return ±FSR of the current gain in volts */
    public double fullScale() {
        return doFullScale()
    }

    /** @return size of one code in volts ({@code fullScale() / 32768}) */
    public double lsb() {
        return doLsb()
    }

    /**
     * Set the data rate used by all following conversions.
     *
     * @param rate {@code DR_8}…{@code DR_860} (0–7)
     * @throws IOException on bus error
     */
    public void setDataRate(int rate) throws IOException {
        doSetDataRate(rate)
    }

    /** @return the configured {@code DR} code (0–7) */
    public int getDataRate() {
        return dr
    }

    /** @return the configured data rate in samples per second */
    public int dataRateSps() {
        return RATES[dr]
    }

    /**
     * Read the most recent completed result without touching Config.
     *
     * @return signed 16-bit code
     * @throws IOException on bus error
     */
    public int readContinuousRaw() throws IOException {
        return doReadContinuousRaw()
    }

    /**
     * Read the most recent continuous result in volts.
     *
     * @return voltage in V
     * @throws IOException on bus error
     */
    public double readContinuous() throws IOException {
        return doReadContinuousRaw() * doLsb()
    }

    /**
     * Return to single-shot / power-down mode ({@code MODE} ← 1).
     *
     * @throws IOException on bus error
     */
    public void stopContinuous() throws IOException {
        doStopContinuous()
    }

    /**
     * @return {@code true} while {@code CONFIG.OS} reads 0 (single-shot mode only)
     * @throws IOException on bus error
     */
    public boolean isConverting() throws IOException {
        return doIsConverting()
    }

    /**
     * @return the raw 16-bit {@code CONFIG} register
     * @throws IOException on bus error
     */
    public int readConfig() throws IOException {
        return readReg(REG_CONFIG)
    }

    /**
     * Write the reset values back to CONFIG and clear the cached
     * settings. Not an I²C general-call reset.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        doReset(false)
    }
}
