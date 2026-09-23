package it.uhde.periph.chips.temperature

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.io.IOException
import java.util.function.IntConsumer

/**
 * MCP9808 full interface — extends {@link MCP9808Minimal} with resolution control, Shutdown mode,
 * the {@code TUPPER}/{@code TLOWER}/{@code TCRIT} boundaries, hysteresis, the one-way register
 * locks, and the Level-2 Alert/interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (edge direction
 * follows the configured {@code ALERT_POL}), otherwise a 5&nbsp;ms polling thread that reports each
 * change of the boundary-status mask.
 */
@CompileStatic
class MCP9808Full extends MCP9808Minimal {

    /** Interrupt source: {@code TA} &lt; {@code TLOWER}. */
    public static final int SOURCE_LOWER    = 0x01
    /** Interrupt source: {@code TA} &gt; {@code TUPPER}. */
    public static final int SOURCE_UPPER    = 0x02
    /** Interrupt source: {@code TA} ≥ {@code TCRIT}. */
    public static final int SOURCE_CRITICAL = 0x04

    /** Which boundaries drive the Alert output ({@code ALERT_SEL}). */
    static enum AlertMode {
        /** {@code TUPPER}, {@code TLOWER} and {@code TCRIT}. */
        ALL,
        /** {@code TCRIT} only. */
        CRITICAL_ONLY
    }

    /** Alert output behavior ({@code ALERT_MOD}). */
    static enum AlertOutput {
        /** Follows the boundary state. */
        COMPARATOR,
        /** Latches until {@link #clearInterrupt()}. */
        INTERRUPT
    }

    /** Alert output polarity ({@code ALERT_POL}). */
    static enum AlertPolarity {
        /** Needs an external pull-up (POR default). */
        ACTIVE_LOW,
        /** Driven high when asserted. */
        ACTIVE_HIGH
    }

    // CONFIG (0x01) bits.
    protected static final int CFG_THYST_SHIFT = 9
    protected static final int CFG_THYST_MASK  = 0x0600
    protected static final int CFG_SHDN        = 0x0100
    protected static final int CFG_CRIT_LOCK   = 0x0080
    protected static final int CFG_WIN_LOCK    = 0x0040
    protected static final int CFG_INT_CLEAR   = 0x0020
    protected static final int CFG_ALERT_STAT  = 0x0010
    protected static final int CFG_ALERT_CNT   = 0x0008
    protected static final int CFG_ALERT_SEL   = 0x0004
    protected static final int CFG_ALERT_POL   = 0x0002
    protected static final int CFG_ALERT_MOD   = 0x0001
    protected static final int CFG_LOCKS       = 0x00C0
    // Writable bits: all but the unimplemented 15:11, the read-only ALERT_STAT,
    // and the self-clearing INT_CLEAR (set only on purpose).
    protected static final int CFG_WRITE_MASK  = 0x07CF

    private static final double[] RESOLUTIONS = [0.5d, 0.25d, 0.125d, 0.0625d] as double[]
    private static final double[] HYSTERESES  = [0.0d, 1.5d, 3.0d, 6.0d] as double[]

    private volatile IntConsumer callback
    private InputPin intPin
    private volatile boolean polling = false
    private Thread pollThread
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver; same identity check as {@link MCP9808Minimal#MCP9808Minimal(Connection)}.
     *
     * @param connection configured I²C connection pointing at the device (0x18–0x1F)
     * @throws IOException on bus error or identity mismatch
     */
    MCP9808Full(Connection connection) {
        super(connection)
    }

    private int readConfig() {
        return readReg(REG_CONFIG) & CFG_WRITE_MASK
    }

    private void writeConfig(int value) {
        writeReg(REG_CONFIG, value & CFG_WRITE_MASK)
    }

    private static double decodeLimit(int raw) {
        int value = (raw >> 2) & 0x3FF
        if ((raw & 0x1000) != 0) value -= 1024
        return value / 4.0d
    }

    private static int encodeLimit(double celsius) {
        // Round half away from zero, then clamp to the 11-bit two's-complement range.
        int quarters = celsius >= 0 ? (int) (celsius * 4 + 0.5d) : -(int) (-celsius * 4 + 0.5d)
        quarters = Math.max(-1024, Math.min(1023, quarters))
        return (quarters & 0x7FF) << 2
    }

    private static int indexOf(double[] table, double value) {
        for (int i = 0; i < table.length; i++) {
            if (Math.abs(value - table[i]) < 1e-6d) return i
        }
        return -1
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Set the measurement resolution. Finer steps take longer to convert: 0.5&nbsp;°C = 30&nbsp;ms,
     * 0.25&nbsp;°C = 65&nbsp;ms, 0.125&nbsp;°C = 130&nbsp;ms, 0.0625&nbsp;°C = 250&nbsp;ms (typical).
     *
     * @param celsius one of 0.5, 0.25, 0.125, 0.0625
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code celsius} is not a supported step
     */
    void setResolution(double celsius) {
        int code = indexOf(RESOLUTIONS, celsius)
        if (code < 0) throw new IllegalArgumentException("resolution must be one of 0.5, 0.25, 0.125, 0.0625")
        connection.write(new byte[]{(byte) REG_RESOLUTION, (byte) code})
    }

    /**
     * Read the measurement resolution.
     *
     * @return resolution step in °C
     * @throws IOException on bus error
     */
    double getResolution() {
        return RESOLUTIONS[connection.writeRead(new byte[]{(byte) REG_RESOLUTION}, 1)[0] & 0x03]
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /**
     * Enter Shutdown (low-power) mode; {@code TA} holds its last value. No-op while either lock
     * bit is set (the chip ignores {@code SHDN}=1 then).
     *
     * @throws IOException on bus error
     */
    void shutdown() {
        int config = readConfig()
        if ((config & CFG_LOCKS) != 0) return
        writeConfig(config | CFG_SHDN)
    }

    /**
     * Leave Shutdown mode and resume continuous conversion.
     *
     * @throws IOException on bus error
     */
    void wake() {
        writeConfig(readConfig() & ~CFG_SHDN)
    }

    /**
     * Report whether the sensor is in Shutdown mode.
     *
     * @return {@code true} if {@code SHDN} is set
     * @throws IOException on bus error
     */
    boolean isShutdown() {
        return (readReg(REG_CONFIG) & CFG_SHDN) != 0
    }

    // -------------------------------------------------------------------------
    // Boundaries
    // -------------------------------------------------------------------------

    /**
     * Read the {@code TUPPER} boundary.
     *
     * @return upper boundary in °C (0.25&nbsp;°C steps)
     * @throws IOException on bus error
     */
    double getUpperLimit() {
        return decodeLimit(readReg(REG_TUPPER))
    }

    /**
     * Write the {@code TUPPER} boundary, rounded to the nearest 0.25&nbsp;°C (ignored by the chip
     * while {@code WIN_LOCK} is set).
     *
     * @param celsius upper boundary in °C (−256.0 to 255.75, clamped)
     * @throws IOException on bus error
     */
    void setUpperLimit(double celsius) {
        writeReg(REG_TUPPER, encodeLimit(celsius))
    }

    /**
     * Read the {@code TLOWER} boundary.
     *
     * @return lower boundary in °C (0.25&nbsp;°C steps)
     * @throws IOException on bus error
     */
    double getLowerLimit() {
        return decodeLimit(readReg(REG_TLOWER))
    }

    /**
     * Write the {@code TLOWER} boundary, rounded to the nearest 0.25&nbsp;°C (ignored by the chip
     * while {@code WIN_LOCK} is set).
     *
     * @param celsius lower boundary in °C (−256.0 to 255.75, clamped)
     * @throws IOException on bus error
     */
    void setLowerLimit(double celsius) {
        writeReg(REG_TLOWER, encodeLimit(celsius))
    }

    /**
     * Read the {@code TCRIT} boundary.
     *
     * @return critical boundary in °C (0.25&nbsp;°C steps)
     * @throws IOException on bus error
     */
    double getCriticalLimit() {
        return decodeLimit(readReg(REG_TCRIT))
    }

    /**
     * Write the {@code TCRIT} boundary, rounded to the nearest 0.25&nbsp;°C (ignored by the chip
     * while {@code CRIT_LOCK} is set).
     *
     * @param celsius critical boundary in °C (−256.0 to 255.75, clamped)
     * @throws IOException on bus error
     */
    void setCriticalLimit(double celsius) {
        writeReg(REG_TCRIT, encodeLimit(celsius))
    }

    // -------------------------------------------------------------------------
    // Hysteresis
    // -------------------------------------------------------------------------

    /**
     * Set the boundary hysteresis (applies to the cooling edge only; ignored by the chip while
     * either lock bit is set).
     *
     * @param celsius one of 0, 1.5, 3.0, 6.0
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code celsius} is not a supported value
     */
    void setHysteresis(double celsius) {
        int code = indexOf(HYSTERESES, celsius)
        if (code < 0) throw new IllegalArgumentException("hysteresis must be one of 0, 1.5, 3.0, 6.0")
        writeConfig((readConfig() & ~CFG_THYST_MASK) | (code << CFG_THYST_SHIFT))
    }

    /**
     * Read the boundary hysteresis.
     *
     * @return hysteresis in °C
     * @throws IOException on bus error
     */
    double getHysteresis() {
        return HYSTERESES[(readReg(REG_CONFIG) & CFG_THYST_MASK) >> CFG_THYST_SHIFT]
    }

    // -------------------------------------------------------------------------
    // Locks
    // -------------------------------------------------------------------------

    /**
     * Lock {@code TCRIT} (and {@code ALERT_SEL}/{@code POL}/{@code MOD}). Irreversible except by
     * power-on reset.
     *
     * @throws IOException on bus error
     */
    void lockCriticalLimit() {
        writeConfig(readConfig() | CFG_CRIT_LOCK)
    }

    /**
     * Lock {@code TUPPER}/{@code TLOWER} (and {@code ALERT_SEL}/{@code POL}/{@code MOD}).
     * Irreversible except by power-on reset.
     *
     * @throws IOException on bus error
     */
    void lockWindowLimits() {
        writeConfig(readConfig() | CFG_WIN_LOCK)
    }

    /**
     * Report whether {@code CRIT_LOCK} is set.
     *
     * @return {@code true} if {@code TCRIT} is locked
     * @throws IOException on bus error
     */
    boolean isCriticalLimitLocked() {
        return (readReg(REG_CONFIG) & CFG_CRIT_LOCK) != 0
    }

    /**
     * Report whether {@code WIN_LOCK} is set.
     *
     * @return {@code true} if {@code TUPPER}/{@code TLOWER} are locked
     * @throws IOException on bus error
     */
    boolean isWindowLimitsLocked() {
        return (readReg(REG_CONFIG) & CFG_WIN_LOCK) != 0
    }

    // -------------------------------------------------------------------------
    // Alert output
    // -------------------------------------------------------------------------

    /**
     * Configure the Alert output with the POR defaults: all boundaries, comparator, active-low.
     *
     * @throws IOException           on bus error
     * @throws IllegalStateException if either lock bit is set
     */
    void configureAlert() {
        configureAlert(AlertMode.ALL, AlertOutput.COMPARATOR, AlertPolarity.ACTIVE_LOW)
    }

    /**
     * Configure the Alert output's source, mode and polarity together.
     *
     * @param mode     boundaries that drive the Alert output
     * @param output   comparator or latching interrupt output
     * @param polarity active-low or active-high
     * @throws IOException           on bus error
     * @throws IllegalStateException if either lock bit is set (the bits are frozen until power-on reset)
     */
    void configureAlert(AlertMode mode, AlertOutput output, AlertPolarity polarity) {
        int config = readConfig()
        if ((config & CFG_LOCKS) != 0) {
            throw new IllegalStateException("MCP9808: Alert configuration is locked until power-on reset")
        }
        config &= ~(CFG_ALERT_SEL | CFG_ALERT_POL | CFG_ALERT_MOD)
        if (mode == AlertMode.CRITICAL_ONLY) config |= CFG_ALERT_SEL
        if (polarity == AlertPolarity.ACTIVE_HIGH) config |= CFG_ALERT_POL
        if (output == AlertOutput.INTERRUPT) config |= CFG_ALERT_MOD
        writeConfig(config)
    }

    /**
     * Enable the Alert output ({@code ALERT_CNT} = 1).
     *
     * @throws IOException on bus error
     */
    void enableAlert() {
        writeConfig(readConfig() | CFG_ALERT_CNT)
    }

    /**
     * Disable the Alert output ({@code ALERT_CNT} = 0).
     *
     * @throws IOException on bus error
     */
    void disableAlert() {
        writeConfig(readConfig() & ~CFG_ALERT_CNT)
    }

    /**
     * Report whether the Alert output is currently asserted.
     *
     * @return {@code true} if {@code ALERT_STAT} is set
     * @throws IOException on bus error
     */
    boolean isAlertAsserted() {
        return (readReg(REG_CONFIG) & CFG_ALERT_STAT) != 0
    }

    /**
     * Clear an asserted interrupt-mode Alert output ({@code INT_CLEAR} = 1). Has no effect in
     * comparator mode.
     *
     * @throws IOException on bus error
     */
    void clearInterrupt() {
        writeReg(REG_CONFIG, readConfig() | CFG_INT_CLEAR)
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 2)
    // -------------------------------------------------------------------------

    /**
     * Read {@code TA}'s live boundary-status bits. Nothing is cleared — the bits are a live
     * comparison, always current.
     *
     * @return mask of {@link #SOURCE_LOWER} / {@link #SOURCE_UPPER} / {@link #SOURCE_CRITICAL}
     * @throws IOException on bus error
     */
    int pollInterrupt() {
        return (readReg(REG_TA) >> 13) & 0x07
    }

    /**
     * Subscribe to Alert events using {@code connection.intPin()} (or a 5&nbsp;ms polling thread if
     * none is wired). The Alert output must be enabled ({@link #enableAlert()}) for a pin to see
     * edges.
     *
     * @param callback called with the {@link #pollInterrupt()} mask
     * @throws IOException on bus error
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to Alert events, overriding which {@link InputPin} delivers edges. With a pin, the
     * callback runs on every Alert edge — falling for active-low, rising for active-high,
     * following the configured {@code ALERT_POL}. The polling fallback calls it whenever the
     * status mask changes.
     *
     * @param callback called with the {@link #pollInterrupt()} mask
     * @param intPin   Alert pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            boolean activeHigh = (readReg(REG_CONFIG) & CFG_ALERT_POL) != 0
            this.intPin = intPin
            intPin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING)
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
        } as Runnable, 'mcp9808-poll')
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
            if (cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
