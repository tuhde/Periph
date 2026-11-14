package it.uhde.periph.chips.temperature

import groovy.transform.CompileStatic

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.DoubleConsumer

/**
 * TMP102 full interface — extends {@link TMP102Minimal} with conversion rate, extended mode, the
 * {@code T_LOW}/{@code T_HIGH} limits, fault queue, polarity, thermostat mode, shutdown/one-shot,
 * alert status, general-call reset and the ALERT interrupt API.
 *
 * <p>{@link #onInterrupt(DoubleConsumer)} uses {@code connection.intPin()} if wired (edge direction
 * follows {@code POL}), otherwise a 1&nbsp;s polling thread that reports the temperature while
 * {@link #pollInterrupt()} indicates an alert.
 */
@CompileStatic
class TMP102Full extends TMP102Minimal {

    // CONFIGURATION (0x01) bits.
    protected static final int CFG_OS       = 0x8000
    protected static final int CFG_F_SHIFT  = 11
    protected static final int CFG_F_MASK   = 0x1800
    protected static final int CFG_POL      = 0x0400
    protected static final int CFG_TM       = 0x0200
    protected static final int CFG_SD       = 0x0100
    protected static final int CFG_CR_SHIFT = 6
    protected static final int CFG_CR_MASK  = 0x00C0
    protected static final int CFG_AL       = 0x0020
    protected static final int CFG_EM       = 0x0010
    protected static final int CFG_WRITABLE = 0x9FD0
    protected static final int CFG_RESERVED = 0x6000; // R1:R0, read-only, always 11

    private static final double LIMIT_MIN = -55.0
    private static final double LIMIT_MAX = 150.0
    private static final double[] RATES = [0.25d, 1.0d, 4.0d, 8.0d] as double[]
    private static final int[] FAULTS = [1, 2, 4, 6] as int[]
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler
    private volatile DoubleConsumer callback
    private InputPin intPin
    private volatile boolean polling
    private Thread pollThread
    /**
     * Construct the driver (no bus access).
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     */
    TMP102Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Read the raw signed temperature count.
     *
     * @return signed 12-bit (13-bit in extended mode) count; temperature = count × 0.0625&nbsp;°C
     * @throws IOException on bus error
     */
    int readRaw() throws IOException {
        return decodeRaw(readReg(REG_TEMPERATURE))
    }

    /**
     * Read the 16-bit CONFIGURATION register (reset value 0x60A0).
     *
     * @return register value
     * @throws IOException on bus error
     */
    int readConfig() throws IOException {
        return readReg(REG_CONFIG)
    }

    /**
     * Write the 16-bit CONFIGURATION register; read-only bits are masked out.
     *
     * @param value register value; only OS, F1:F0, POL, TM, SD, CR1:CR0 and EM are applied
     * @throws IOException on bus error
     */
    void writeConfig(int value) throws IOException {
        writeReg(REG_CONFIG, (value & CFG_WRITABLE) | CFG_RESERVED)
    }

    private void updateConfig(int clear, int set) throws IOException {
        writeConfig((readConfig() & ~clear) | set)
    }

    /**
     * Set the conversion rate.
     *
     * @param hz 0.25, 1, 4 or 8 Hz
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code hz} is not a supported rate
     */
    void setConversionRate(double hz) throws IOException {
        for (int i = 0; i < RATES.length; i++) {
            if (RATES[i] == hz) {
                updateConfig(CFG_CR_MASK, i << CFG_CR_SHIFT)
                return
            }
        }
        throw new IllegalArgumentException("hz must be 0.25, 1, 4 or 8")
    }

    /**
     * Enable or disable the 13-bit extended mode ({@code EM} bit).
     *
     * @param on {@code true} for 13-bit (−256…+255.9375&nbsp;°C), {@code false} for 12-bit
     * @throws IOException on bus error
     */
    void setExtendedMode(boolean on) throws IOException {
        updateConfig(CFG_EM, on ? CFG_EM : 0)
    }

    private void setLimit(int reg, double celsius) throws IOException {
        boolean extended = (readConfig() & CFG_EM) != 0
        double c = Math.min(Math.max(celsius, LIMIT_MIN), LIMIT_MAX)
        int counts = (int) Math.round(c / LSB_C)
        if (extended) {
            writeReg(reg, (counts << 3) & 0xFFF8)
        } else {
            if (counts > 2047) counts = 2047
            writeReg(reg, (counts << 4) & 0xFFF0)
        }
    }

    private double getLimit(int reg) throws IOException {
        boolean extended = (readConfig() & CFG_EM) != 0
        int raw16 = readReg(reg)
        int counts = extended ? Register.toSigned(raw16 >> 3, 13) : Register.toSigned(raw16 >> 4, 12)
        return counts * LSB_C
    }

    /**
     * Set the {@code T_HIGH} limit (reset 80&nbsp;°C).
     *
     * @param celsius limit in °C, rounded to 0.0625&nbsp;°C and clamped to −55…+150&nbsp;°C
     *                (12-bit mode: up to +127.9375&nbsp;°C)
     * @throws IOException on bus error
     */
    void setTHigh(double celsius) throws IOException {
        setLimit(REG_T_HIGH, celsius)
    }

    /**
     * Read the {@code T_HIGH} limit.
     *
     * @return limit in °C
     * @throws IOException on bus error
     */
    double getTHigh() throws IOException {
        return getLimit(REG_T_HIGH)
    }

    /**
     * Set the {@code T_LOW} limit (reset 75&nbsp;°C); same coding as {@link #setTHigh(double)}.
     *
     * @param celsius limit in °C
     * @throws IOException on bus error
     */
    void setTLow(double celsius) throws IOException {
        setLimit(REG_T_LOW, celsius)
    }

    /**
     * Read the {@code T_LOW} limit.
     *
     * @return limit in °C
     * @throws IOException on bus error
     */
    double getTLow() throws IOException {
        return getLimit(REG_T_LOW)
    }

    /**
     * Set the number of consecutive faults that trigger ALERT.
     *
     * @param count 1, 2, 4 or 6
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code count} is not a supported value
     */
    void setFaultQueue(int count) throws IOException {
        for (int i = 0; i < FAULTS.length; i++) {
            if (FAULTS[i] == count) {
                updateConfig(CFG_F_MASK, i << CFG_F_SHIFT)
                return
            }
        }
        throw new IllegalArgumentException("count must be 1, 2, 4 or 6")
    }

    /**
     * Set the ALERT polarity ({@code POL} bit).
     *
     * @param activeHigh {@code true} for active-high, {@code false} for active-low (default)
     * @throws IOException on bus error
     */
    void setPolarity(boolean activeHigh) throws IOException {
        updateConfig(CFG_POL, activeHigh ? CFG_POL : 0)
    }

    /**
     * Select the thermostat mode ({@code TM} bit).
     *
     * @param interrupt {@code true} for interrupt mode, {@code false} for comparator mode (default)
     * @throws IOException on bus error
     */
    void setThermostatMode(boolean interrupt) throws IOException {
        updateConfig(CFG_TM, interrupt ? CFG_TM : 0)
    }

    /**
     * Shut the device down after the current conversion ({@code SD} = 1).
     *
     * @throws IOException on bus error
     */
    void shutdown() throws IOException {
        updateConfig(0, CFG_SD)
    }

    /**
     * Resume continuous conversion ({@code SD} = 0).
     *
     * @throws IOException on bus error
     */
    void wake() throws IOException {
        updateConfig(CFG_SD, 0)
    }

    /**
     * Take a single conversion and return the temperature. Puts the device in shutdown if needed,
     * sets {@code OS} = 1, polls {@code OS} until the conversion is done (30&nbsp;ms timeout) and
     * restores the previous mode.
     *
     * @return temperature in °C
     * @throws IOException on bus error or if the conversion does not finish within 30&nbsp;ms
     */
    double oneShot() throws IOException {
        int config = readConfig()
        boolean wasRunning = (config & CFG_SD) == 0
        if (wasRunning) {
            config |= CFG_SD
            writeConfig(config)
        }
        writeConfig(config | CFG_OS)
        boolean done = false
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(1)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new IOException("interrupted", e)
            }
            if ((readConfig() & CFG_OS) != 0) {
                done = true
                break
            }
        }
        double t = readTemperature()
        if (wasRunning) wake()
        if (!done) throw new IOException("TMP102: one-shot conversion timed out")
        return t
    }

    /**
     * Report whether ALERT is currently active.
     *
     * @return {@code true} while the comparator state is alerting (the {@code AL} bit corrected for {@code POL})
     * @throws IOException on bus error
     */
    boolean alertState() throws IOException {
        int config = readConfig()
        boolean al = (config & CFG_AL) != 0
        // AL reads 1 while inactive with POL = 0 (inverted); POL = 1 flips it.
        return (config & CFG_POL) != 0 ? al : !al
    }

    /**
     * Send the general-call reset ({@code 0x00 0x06}). Resets <b>every</b> general-call device on
     * the bus to power-up values; needs a connection that transmits to the general-call address.
     *
     * @throws IOException on bus error
     */
    void reset() throws IOException {
        connection.write([0x00, 0x06] as byte[])
    }

    /**
     * Read the alert state and clear an interrupt-mode alert (reads CONFIGURATION, then TEMPERATURE).
     *
     * @return {@code true} while the comparator state is alerting
     * @throws IOException on bus error
     */
    boolean pollInterrupt() throws IOException {
        boolean alerting = alertState()
        readReg(REG_TEMPERATURE)
        return alerting
    }

    /**
     * Subscribe to ALERT events using {@code connection.intPin()} (or a 1&nbsp;s polling thread if
     * none is wired).
     *
     * @param callback called with the temperature in °C
     * @throws IOException on bus error
     */
    void onInterrupt(DoubleConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to ALERT events, overriding which {@link InputPin} delivers edges. With a pin, the
     * callback runs on every active edge (falling for active-low, rising for active-high).
     *
     * @param callback called with the temperature in °C
     * @param intPin   ALERT pin to arm, or {@code null} to force the 1&nbsp;s polling fallback
     * @throws IOException on bus error
     */
    void onInterrupt(DoubleConsumer callback, InputPin intPin) throws IOException {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            boolean activeHigh = (readConfig() & CFG_POL) != 0
            this.intPin = intPin
            intPin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING)
        } else {
            startPolling()
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

    private void startPolling() {
        polling = true
        pollThread = new Thread({ ->
            while (polling) {
                try {
                    boolean alerting = pollInterrupt()
                    DoubleConsumer cb = callback
                    if (alerting && cb != null) cb.accept(readTemperature())
                } catch (IOException ignored) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(1000)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        } as Runnable, "tmp102-poll")
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
            double t = readTemperature()
            DoubleConsumer cb = callback
            if (cb != null) cb.accept(t)
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
