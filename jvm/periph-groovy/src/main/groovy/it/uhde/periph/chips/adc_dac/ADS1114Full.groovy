package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.InputPin
import groovy.transform.CompileStatic

import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * ADS1114 full interface — extends {@link ADS1114Minimal} with gain, data rate, continuous
 * conversion, status and reset, the comparator, thresholds and the {@code ALERT/RDY} interrupt API.
 */
@CompileStatic
class ADS1114Full extends ADS1114Minimal {

    /** Data-rate codes ({@code DR[2:0]}). */
    public static final int DR_8 = 0, DR_16 = 1, DR_32 = 2, DR_64 = 3, DR_128 = 4, DR_250 = 5, DR_475 = 6, DR_860 = 7
    /** Full-scale range codes ({@code PGA[2:0]}). */
    public static final int PGA_6_144 = 0, PGA_4_096 = 1, PGA_2_048 = 2, PGA_1_024 = 3, PGA_0_512 = 4, PGA_0_256 = 5
    /** Comparator mode, polarity and queue values. */
    public static final int COMP_TRADITIONAL = 0, COMP_WINDOW = 1
    public static final int ACTIVE_LOW = 0, ACTIVE_HIGH = 1
    public static final int QUEUE_1 = 0, QUEUE_2 = 1, QUEUE_4 = 2

    /**
     * Construct the driver; makes no bus traffic.
     *
     * @param connection I²C register connection pointing at the device
     */
    public ADS1114Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Run a single-shot conversion and return volts at the current gain.
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

    /**
     * Set the full-scale range for all following reads; thresholds are not rescaled.
     *
     * @param code {@code PGA_6_144}…{@code PGA_0_256} (0–5)
     * @throws IOException on bus error
     */
    public void setGain(int code) throws IOException {
        doSetGain(code)
    }

    /** @return the configured {@code PGA} code (0–5) */
    public int getGain() {
        return pga
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
     * Write the reset values back to CONFIG, LO_THRESH and HI_THRESH and clear the cached
     * settings. Not an I²C general-call reset.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        doReset(true)
    }

    /**
     * Configure the comparator and route it to {@code ALERT/RDY}: traditional, active low,
     * non-latching, one-sample queue.
     *
     * @throws IOException on bus error
     */
    public void configureComparator() throws IOException {
        doConfigureComparator(COMP_TRADITIONAL, ACTIVE_LOW, false, QUEUE_1)
    }

    /**
     * Configure the comparator and route it to {@code ALERT/RDY}.
     *
     * @param mode     {@code COMP_TRADITIONAL} or {@code COMP_WINDOW}
     * @param polarity {@code ACTIVE_LOW} or {@code ACTIVE_HIGH}
     * @param latch    {@code true} to latch until {@link #clearAlert()}
     * @param queue    {@code QUEUE_1}, {@code QUEUE_2} or {@code QUEUE_4}
     * @throws IOException on bus error
     * @throws IllegalArgumentException on an invalid argument or when the last thresholds have high ≤ low
     */
    public void configureComparator(int mode, int polarity, boolean latch, int queue) throws IOException {
        doConfigureComparator(mode, polarity, latch, queue)
    }

    /**
     * Set {@code COMP_QUE} to 11; {@code ALERT/RDY} becomes high-impedance.
     *
     * @throws IOException on bus error
     */
    public void disableComparator() throws IOException {
        doDisableComparator()
    }

    /**
     * Write {@code LO_THRESH} (signed code, same scale as {@code CONVERSION}).
     *
     * @param raw threshold code
     * @throws IOException on bus error
     */
    public void setLowThresholdRaw(int raw) throws IOException {
        doSetLowThresholdRaw(raw)
    }

    /**
     * Write {@code HI_THRESH} (signed code, same scale as {@code CONVERSION}).
     *
     * @param raw threshold code
     * @throws IOException on bus error
     */
    public void setHighThresholdRaw(int raw) throws IOException {
        doSetHighThresholdRaw(raw)
    }

    /**
     * Write both thresholds from volts at the current gain.
     *
     * @param low  lower threshold in V
     * @param high upper threshold in V
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code high <= low}
     */
    public void setThresholds(double low, double high) throws IOException {
        doSetThresholds(low, high)
    }

    /**
     * @return {@code [low, high]} read back and sign-extended
     * @throws IOException on bus error
     */
    public int[] getThresholdsRaw() throws IOException {
        return doGetThresholdsRaw()
    }

    /**
     * Read {@code CONVERSION} to release a latched comparator output.
     *
     * @throws IOException on bus error
     */
    public void clearAlert() throws IOException {
        doClearAlert()
    }

    /**
     * Route one source to {@code ALERT/RDY}; the other is disabled.
     *
     * @param src {@link #SOURCE_CONVERSION_READY} or {@link #SOURCE_COMPARATOR}
     * @throws IOException on bus error
     */
    public void enableInterrupt(int src) throws IOException {
        doEnableInterrupt(src)
    }

    /**
     * Release {@code ALERT/RDY} ({@code COMP_QUE} ← 11).
     *
     * @param src the source being disabled
     * @throws IOException on bus error
     */
    public void disableInterrupt(int src) throws IOException {
        doDisableComparator()
    }

    /**
     * Return the enabled source constant if {@code ALERT/RDY} is asserted, else 0. Samples
     * {@code connection.intPin()} when wired; without a pin {@code SOURCE_CONVERSION_READY} falls
     * back to {@code CONFIG.OS} (single-shot only). A latched alert is cleared by reading
     * {@code CONVERSION}.
     *
     * @return the enabled source constant, or 0
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return doPollInterrupt()
    }

    /**
     * Subscribe to {@code ALERT/RDY} events using {@code connection.intPin()} (or a 5&nbsp;ms
     * polling thread if none is wired).
     *
     * @param callback called with the {@link #pollInterrupt()} value
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        doOnInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to {@code ALERT/RDY} events via {@code intPin}; the edge follows
     * {@code COMP_POL}. {@code null} forces the 5&nbsp;ms polling fallback.
     *
     * @param callback called with the {@link #pollInterrupt()} value
     * @param intPin   ALERT/RDY pin to arm, or {@code null}
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        doOnInterrupt(callback, intPin)
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        doOffInterrupt()
    }
}
