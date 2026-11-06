package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * ADS1014 full interface — extends {@link ADS1014Minimal} with PGA gain, data rate, continuous
 * conversion, the programmable comparator and the Level-2 {@code ALERT/RDY} interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (edge direction
 * follows the configured {@code COMP_POL}), otherwise a 5&nbsp;ms polling thread that reports each
 * change of {@link #pollInterrupt()}.
 */
@CompileStatic
class ADS1014Full extends ADS1014Minimal {

    /** PGA full-scale ±6.144&nbsp;V. */
    public static final int PGA_6_144 = 0
    /** PGA full-scale ±4.096&nbsp;V. */
    public static final int PGA_4_096 = 1
    /** PGA full-scale ±2.048&nbsp;V (default). */
    public static final int PGA_2_048 = 2
    /** PGA full-scale ±1.024&nbsp;V. */
    public static final int PGA_1_024 = 3
    /** PGA full-scale ±0.512&nbsp;V. */
    public static final int PGA_0_512 = 4
    /** PGA full-scale ±0.256&nbsp;V. */
    public static final int PGA_0_256 = 5

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

    /** Traditional comparator. */
    public static final int COMP_TRADITIONAL = 0
    /** Window comparator. */
    public static final int COMP_WINDOW      = 1
    /** {@code ALERT/RDY} active low (default). */
    public static final int ACTIVE_LOW       = 0
    /** {@code ALERT/RDY} active high. */
    public static final int ACTIVE_HIGH      = 1
    /** Assert after 1 out-of-range conversion. */
    public static final int QUEUE_1          = 0
    /** Assert after 2 consecutive out-of-range conversions. */
    public static final int QUEUE_2          = 1
    /** Assert after 4 consecutive out-of-range conversions. */
    public static final int QUEUE_4          = 2

    private static final int CONFIG_RESET = 0x8583
    private static final int LO_RESET     = 0x8000
    private static final int HI_RESET     = 0x7FFF

    private int loRaw = -2048
    private int hiRaw = 2047

    private volatile IntConsumer callback
    private InputPin intPin
    private volatile boolean polling = false
    private Thread pollThread
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver. Performs no I²C traffic.
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    ADS1014Full(RegisterConnection connection) {
        super(connection)
    }

    private void writeConfig() {
        writeReg(REG_CONFIG, configWord(false, continuous))
    }

    /**
     * Select the full-scale range used by all following reads. Thresholds must be rewritten after
     * a gain change.
     *
     * @param pga {@link #PGA_6_144} … {@link #PGA_0_256}
     * @throws IllegalArgumentException if {@code pga} is outside 0–5 (6 and 7 alias ±0.256&nbsp;V)
     * @throws IOException on bus error
     */
    void setGain(int pga) {
        if (pga < 0 || pga > PGA_0_256) throw new IllegalArgumentException("pga must be 0-5")
        this.pga = pga
        if (continuous) writeConfig()
    }

    /**
     * Read the configured PGA code.
     *
     * @return PGA code 0–5
     */
    int getGain() {
        return pga
    }

    /**
     * Full-scale range of the current gain.
     *
     * @return ±FSR in volts (6.144, 4.096, 2.048, 1.024, 0.512 or 0.256)
     */
    double fullScale() {
        return FULL_SCALE[pga]
    }

    /**
     * Size of one conversion code.
     *
     * @return {@code fullScale() / 2048} in volts
     */
    double lsb() {
        return lsbVolts()
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
        return readConversion() * lsbVolts()
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
     * Write the reset values back ({@code CONFIG} 0x8583, {@code LO_THRESH} 0x8000,
     * {@code HI_THRESH} 0x7FFF) and clear the cached settings. Not an I²C general-call reset.
     *
     * @throws IOException on bus error
     */
    void reset() {
        writeReg(REG_CONFIG, CONFIG_RESET)
        writeReg(REG_LO_THRESH, LO_RESET)
        writeReg(REG_HI_THRESH, HI_RESET)
        pga = PGA_2_048
        dr = DR_1600
        continuous = false
        compMode = COMP_TRADITIONAL
        compPol = ACTIVE_LOW
        compLat = false
        queue = QUEUE_1
        source = 0
        loRaw = -2048
        hiRaw = 2047
    }

    /**
     * Configure the comparator and enable it as the {@code ALERT/RDY} source.
     *
     * @param mode     {@link #COMP_TRADITIONAL} or {@link #COMP_WINDOW}
     * @param polarity {@link #ACTIVE_LOW} or {@link #ACTIVE_HIGH}
     * @param latch    hold the alert until {@code CONVERSION} is read
     * @param queue    {@link #QUEUE_1}, {@link #QUEUE_2} or {@link #QUEUE_4} consecutive
     *                 out-of-range conversions
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IOException on bus error
     */
    void configureComparator(int mode, int polarity, boolean latch, int queue) {
        if (mode < COMP_TRADITIONAL || mode > COMP_WINDOW) throw new IllegalArgumentException("mode must be 0 or 1")
        if (polarity < ACTIVE_LOW || polarity > ACTIVE_HIGH) throw new IllegalArgumentException("polarity must be 0 or 1")
        if (queue < QUEUE_1 || queue > QUEUE_4) throw new IllegalArgumentException("queue must be 0, 1 or 2")
        this.compMode = mode
        this.compPol = polarity
        this.compLat = latch
        this.queue = queue
        this.source = SOURCE_COMPARATOR
        writeConfig()
    }

    /**
     * Disable the comparator ({@code COMP_QUE} = 11); {@code ALERT/RDY} goes high-impedance.
     *
     * @throws IOException on bus error
     */
    void disableComparator() {
        source = 0
        writeConfig()
    }

    private void writeThreshold(int reg, int raw) {
        if (raw < -2048 || raw > 2047) throw new IllegalArgumentException("raw must be -2048..2047")
        writeReg(reg, (raw & 0x0FFF) << 4)
    }

    /**
     * Write the comparator low threshold ({@code LO_THRESH} = {@code raw << 4}).
     *
     * @param raw 12-bit two's-complement code, −2048…2047
     * @throws IllegalArgumentException if {@code raw} is out of range
     * @throws IOException on bus error
     */
    void setLowThresholdRaw(int raw) {
        writeThreshold(REG_LO_THRESH, raw)
        loRaw = raw
    }

    /**
     * Write the comparator high threshold ({@code HI_THRESH} = {@code raw << 4}).
     *
     * @param raw 12-bit two's-complement code, −2048…2047
     * @throws IllegalArgumentException if {@code raw} is out of range
     * @throws IOException on bus error
     */
    void setHighThresholdRaw(int raw) {
        writeThreshold(REG_HI_THRESH, raw)
        hiRaw = raw
    }

    /**
     * Write both comparator thresholds from volts (converted with {@link #lsb()}, clamped to the
     * code range).
     *
     * @param low  low threshold in volts
     * @param high high threshold in volts
     * @throws IllegalArgumentException if {@code high <= low}
     * @throws IOException on bus error
     */
    void setThresholds(double low, double high) {
        if (high <= low) throw new IllegalArgumentException("high must be greater than low")
        double step = lsbVolts()
        setLowThresholdRaw(toCode(low, step))
        setHighThresholdRaw(toCode(high, step))
    }

    private static int toCode(double volts, double step) {
        long code = Math.round(volts / step)
        return (int) Math.max(-2048L, Math.min(2047L, code))
    }

    /**
     * Read both thresholds back.
     *
     * @return {@code {low, high}} as sign-extended 12-bit codes
     * @throws IOException on bus error
     */
    int[] getThresholdsRaw() {
        int lo = Register.toSigned(readReg(REG_LO_THRESH), 16) >> 4
        int hi = Register.toSigned(readReg(REG_HI_THRESH), 16) >> 4
        return [lo, hi] as int[]
    }

    /**
     * Release a latched comparator output by reading {@code CONVERSION}.
     *
     * @throws IOException on bus error
     */
    void clearAlert() {
        readReg(REG_CONVERSION)
    }

    // -- Interrupt API (Level 2) ------------------------------------------------------------

    /**
     * Route one source to {@code ALERT/RDY}; the other is disabled.
     *
     * <p>{@link #SOURCE_CONVERSION_READY} writes {@code HI_THRESH} = 0x8000 and {@code LO_THRESH}
     * = 0x0000 and sets {@code COMP_QUE} = 00. {@link #SOURCE_COMPARATOR} restores the thresholds
     * last set through the driver and the configured queue.
     *
     * @param source {@link #SOURCE_CONVERSION_READY} or {@link #SOURCE_COMPARATOR}
     * @throws IllegalArgumentException for an unknown source, or for {@link #SOURCE_COMPARATOR}
     *                                  when the thresholds set through the driver have
     *                                  {@code high <= low}
     * @throws IOException on bus error
     */
    void enableInterrupt(int source) {
        if (source == SOURCE_CONVERSION_READY) {
            writeReg(REG_HI_THRESH, 0x8000)
            writeReg(REG_LO_THRESH, 0x0000)
        } else if (source == SOURCE_COMPARATOR) {
            if (hiRaw <= loRaw) {
                throw new IllegalArgumentException("set thresholds (high > low) before enabling the comparator")
            }
            writeReg(REG_LO_THRESH, (loRaw & 0x0FFF) << 4)
            writeReg(REG_HI_THRESH, (hiRaw & 0x0FFF) << 4)
        } else {
            throw new IllegalArgumentException("unknown interrupt source")
        }
        this.source = source
        writeConfig()
    }

    /**
     * Release {@code ALERT/RDY} ({@code COMP_QUE} = 11). The two sources share the pin, so it is
     * released whichever source is named.
     *
     * @param source {@link #SOURCE_CONVERSION_READY} or {@link #SOURCE_COMPARATOR}
     * @throws IOException on bus error
     */
    void disableInterrupt(int source) {
        this.source = 0
        writeConfig()
    }

    /**
     * Report whether the enabled source is asserted.
     *
     * <p>The chip has no interrupt-status register. With an {@link InputPin} on the connection,
     * the {@code ALERT/RDY} level is sampled against {@code COMP_POL} (and a latched comparator
     * alert is cleared by reading {@code CONVERSION}). Without a pin,
     * {@link #SOURCE_CONVERSION_READY} falls back to {@code CONFIG.OS} (single-shot only) and
     * {@link #SOURCE_COMPARATOR} returns 0.
     *
     * @return the enabled {@code SOURCE_*} constant if asserted, else 0
     * @throws IOException on bus error
     */
    int pollInterrupt() {
        if (source == 0) return 0
        InputPin pin = intPin != null ? intPin : connection.intPin()
        if (pin != null) {
            if (pin.isHigh() != (compPol == ACTIVE_HIGH)) return 0
            if (source == SOURCE_COMPARATOR && compLat) clearAlert()
            return source
        }
        if (source == SOURCE_CONVERSION_READY && !continuous && (readReg(REG_CONFIG) & CFG_OS) != 0) {
            return SOURCE_CONVERSION_READY
        }
        return 0
    }

    /**
     * Subscribe to {@code ALERT/RDY} events using {@code connection.intPin()} if wired, otherwise
     * a 5&nbsp;ms polling thread that reports each change of {@link #pollInterrupt()}. Call
     * {@link #enableInterrupt(int)} first.
     *
     * @param callback called with the {@link #pollInterrupt()} value
     * @throws IOException on bus error
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to {@code ALERT/RDY} events, overriding which {@link InputPin} delivers edges.
     * With a pin, the callback runs on every asserting edge — falling for {@link #ACTIVE_LOW},
     * rising for {@link #ACTIVE_HIGH}.
     *
     * @param callback called with the {@link #pollInterrupt()} value
     * @param intPin   {@code ALERT/RDY} pin to arm, or {@code null} to force the polling fallback
     * @throws IOException on bus error
     */
    void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, compPol == ACTIVE_HIGH ? EdgeTrigger.RISING : EdgeTrigger.FALLING)
        } else {
            startPolling(pollInterrupt())
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
    }

    private void startPolling(int initialStatus) {
        polling = true
        pollThread = new Thread({ ->
            int last = initialStatus
            while (polling) {
                try {
                    int status = pollInterrupt()
                    IntConsumer cb = callback
                    if (status != last && cb != null) cb.accept(status)
                    last = status
                } catch (IOException ignored) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, 'ads1014-poll')
        pollThread.daemon = true
        pollThread.start()
    }

    private void stopPolling() {
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if (cb != null && status != 0) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
