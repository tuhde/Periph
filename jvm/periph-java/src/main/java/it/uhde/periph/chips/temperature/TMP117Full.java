package it.uhde.periph.chips.temperature;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * TMP117 full interface — extends {@link TMP117Minimal} with conversion mode, averaging and
 * cycle-time control, one-shot triggering, both temperature limits, the calibration offset, soft
 * reset, EEPROM persistence and scratch storage, and the Level-2 Alert/interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (edge direction
 * follows the configured {@code POL}), otherwise a 5&nbsp;ms polling thread that reports each
 * change of the alert-flag mask.
 */
public class TMP117Full extends TMP117Minimal {

    /** Interrupt source: result &gt; {@code THIGH_LIMIT} ({@code HIGH_Alert}). */
    public static final int SOURCE_HIGH = 0x01;
    /** Interrupt source: result &lt; {@code TLOW_LIMIT} ({@code LOW_Alert}; Alert mode only). */
    public static final int SOURCE_LOW  = 0x02;

    /** Conversion mode ({@code MOD[1:0]}). */
    public enum Mode {
        /** Continuous conversion (POR default). */
        CONTINUOUS(0),
        /** No conversions; {@code TEMP_RESULT} holds its last value. */
        SHUTDOWN(1),
        /** One conversion, then Shutdown. */
        ONE_SHOT(3);

        final int bits;

        Mode(int bits) {
            this.bits = bits;
        }
    }

    /** {@code ALERT} behavior ({@code T/nA}). */
    public enum AlertMode {
        /** Window alert: {@code HIGH_Alert} and {@code LOW_Alert} (POR default). */
        ALERT,
        /** Latching thermostat; {@code TLOW_LIMIT} is the reset threshold. */
        THERM
    }

    /** {@code ALERT} pin polarity ({@code POL}). */
    public enum AlertPolarity {
        /** Needs an external pull-up (POR default). */
        ACTIVE_LOW,
        /** Driven high when asserted. */
        ACTIVE_HIGH
    }

    /** {@code ALERT} pin function ({@code DR/Alert}). */
    public enum AlertPinFunction {
        /** Reflects the alert/Therm status (POR default). */
        ALERT,
        /** Reflects {@code Data_Ready}. */
        DATA_READY
    }

    /**
     * Decoded conversion configuration, as returned by {@link #getConfig()}.
     *
     * @param mode         conversion mode
     * @param averaging    conversions averaged per result: 0, 8, 32 or 64
     * @param cycleSeconds {@code CONV[2:0]} cycle time in s (no-averaging column)
     */
    public record Config(Mode mode, int averaging, double cycleSeconds) {
    }

    // CONFIGURATION (0x01) bits.
    protected static final int CFG_HIGH_ALERT = 0x8000;
    protected static final int CFG_LOW_ALERT  = 0x4000;
    protected static final int CFG_DATA_READY = 0x2000;
    protected static final int CFG_MOD_SHIFT  = 10;
    protected static final int CFG_MOD_MASK   = 0x0C00;
    protected static final int CFG_CONV_SHIFT = 7;
    protected static final int CFG_CONV_MASK  = 0x0380;
    protected static final int CFG_AVG_SHIFT  = 5;
    protected static final int CFG_AVG_MASK   = 0x0060;
    protected static final int CFG_TNA        = 0x0010;
    protected static final int CFG_POL        = 0x0008;
    protected static final int CFG_DR_ALERT   = 0x0004;
    protected static final int CFG_SOFT_RESET = 0x0002;
    // Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
    protected static final int CFG_WRITE_MASK = 0x0FFC;

    // EEPROM_UL (0x04) bits.
    protected static final int EUN         = 0x8000;
    protected static final int EEPROM_BUSY = 0x4000;

    // Conversion cycle times in s, indexed by CONV[2:0] (no-averaging column).
    private static final double[] CYCLES = {0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0};
    // Averaging counts, indexed by AVG[1:0].
    private static final int[] AVERAGINGS = {0, 8, 32, 64};

    private volatile IntConsumer callback;
    private InputPin intPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver; same identity check as {@link TMP117Minimal#TMP117Minimal(Connection)}.
     *
     * @param connection configured I²C connection pointing at the device (0x48–0x4B)
     * @throws IOException on bus error or identity mismatch
     */
    public TMP117Full(Connection connection) throws IOException {
        super(connection);
    }

    private int readConfig() throws IOException {
        return readReg(REG_CONFIG) & CFG_WRITE_MASK;
    }

    private void writeConfig(int value) throws IOException {
        writeReg(REG_CONFIG, value & CFG_WRITE_MASK);
    }

    private static int encodeTemperature(double celsius) {
        // Round half away from zero, then clamp to the 16-bit two's-complement range.
        double steps = celsius / LSB_C;
        long value = steps >= 0 ? (long) (steps + 0.5) : -(long) (-steps + 0.5);
        value = Math.max(-32768, Math.min(32767, value));
        return (int) value & 0xFFFF;
    }

    // -------------------------------------------------------------------------
    // Conversion
    // -------------------------------------------------------------------------

    /**
     * Set conversion mode, averaging and cycle time.
     *
     * <p>The cycle time is matched to the nearest {@code CONV[2:0]} step from the no-averaging
     * column (15.5&nbsp;ms, 125&nbsp;ms, 250&nbsp;ms, 500&nbsp;ms, 1&nbsp;s, 4&nbsp;s, 8&nbsp;s,
     * 16&nbsp;s); at higher averaging the hardware lengthens short cycles automatically. The Alert
     * configuration bits are preserved.
     *
     * @param mode         conversion mode
     * @param averaging    conversions averaged per result: 0, 8, 32 or 64
     * @param cycleSeconds desired conversion cycle time in s
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code averaging} is not 0, 8, 32 or 64
     */
    public void configure(Mode mode, int averaging, double cycleSeconds) throws IOException {
        int avg = -1;
        for (int i = 0; i < AVERAGINGS.length; i++) {
            if (AVERAGINGS[i] == averaging) avg = i;
        }
        if (avg < 0) throw new IllegalArgumentException("averaging must be one of 0, 8, 32, 64");
        int conv = 0;
        for (int code = 1; code < CYCLES.length; code++) {
            if (Math.abs(CYCLES[code] - cycleSeconds) < Math.abs(CYCLES[conv] - cycleSeconds)) conv = code;
        }
        int config = readConfig() & ~(CFG_MOD_MASK | CFG_CONV_MASK | CFG_AVG_MASK);
        config |= (mode.bits << CFG_MOD_SHIFT) | (conv << CFG_CONV_SHIFT) | (avg << CFG_AVG_SHIFT);
        writeConfig(config);
    }

    /**
     * Restore the POR default conversion configuration: continuous, 8-conversion averaging,
     * 1&nbsp;s cycle.
     *
     * @throws IOException on bus error
     */
    public void configure() throws IOException {
        configure(Mode.CONTINUOUS, 8, 1.0);
    }

    /**
     * Read the conversion mode, averaging and cycle time.
     *
     * @return decoded configuration; {@code cycleSeconds} is the no-averaging {@code CONV} step
     * @throws IOException on bus error
     */
    public Config getConfig() throws IOException {
        int config = readReg(REG_CONFIG);
        int mod = (config & CFG_MOD_MASK) >> CFG_MOD_SHIFT;
        // MOD = 10 reads back as continuous conversion.
        Mode mode = mod == 1 ? Mode.SHUTDOWN : mod == 3 ? Mode.ONE_SHOT : Mode.CONTINUOUS;
        return new Config(mode, AVERAGINGS[(config & CFG_AVG_MASK) >> CFG_AVG_SHIFT],
                CYCLES[(config & CFG_CONV_MASK) >> CFG_CONV_SHIFT]);
    }

    /**
     * Report whether the sensor is in Shutdown mode.
     *
     * @return {@code true} if {@code MOD[1:0]} is Shutdown
     * @throws IOException on bus error
     */
    public boolean isShutdown() throws IOException {
        return ((readReg(REG_CONFIG) & CFG_MOD_MASK) >> CFG_MOD_SHIFT) == 1;
    }

    /**
     * Start a single conversion ({@code MOD[1:0]} = One-Shot); the sensor returns to Shutdown once
     * the conversion (including averaging) completes.
     *
     * @throws IOException on bus error
     */
    public void triggerOneShot() throws IOException {
        writeConfig((readConfig() & ~CFG_MOD_MASK) | (Mode.ONE_SHOT.bits << CFG_MOD_SHIFT));
    }

    /**
     * Report whether a fresh conversion result is available. Reading this flag clears it (as does
     * reading {@code TEMP_RESULT}).
     *
     * @return {@code true} if {@code Data_Ready} is set
     * @throws IOException on bus error
     */
    public boolean isDataReady() throws IOException {
        return (readReg(REG_CONFIG) & CFG_DATA_READY) != 0;
    }

    // -------------------------------------------------------------------------
    // Limits and offset
    // -------------------------------------------------------------------------

    /**
     * Read {@code THIGH_LIMIT}.
     *
     * @return high limit in °C
     * @throws IOException on bus error
     */
    public double getHighLimit() throws IOException {
        return decodeTemperature(readReg(REG_THIGH));
    }

    /**
     * Write {@code THIGH_LIMIT}, rounded to the nearest 0.0078125&nbsp;°C.
     *
     * @param celsius high limit in °C (−256.0 to 255.9921875, clamped)
     * @throws IOException on bus error
     */
    public void setHighLimit(double celsius) throws IOException {
        writeReg(REG_THIGH, encodeTemperature(celsius));
    }

    /**
     * Read {@code TLOW_LIMIT}.
     *
     * @return low limit in °C
     * @throws IOException on bus error
     */
    public double getLowLimit() throws IOException {
        return decodeTemperature(readReg(REG_TLOW));
    }

    /**
     * Write {@code TLOW_LIMIT}, rounded to the nearest 0.0078125&nbsp;°C. In Therm mode this is
     * {@code HIGH_Alert}'s reset threshold (hysteresis).
     *
     * @param celsius low limit in °C (−256.0 to 255.9921875, clamped)
     * @throws IOException on bus error
     */
    public void setLowLimit(double celsius) throws IOException {
        writeReg(REG_TLOW, encodeTemperature(celsius));
    }

    /**
     * Read {@code TEMP_OFFSET}.
     *
     * @return calibration offset in °C
     * @throws IOException on bus error
     */
    public double getTemperatureOffset() throws IOException {
        return decodeTemperature(readReg(REG_TEMP_OFFSET));
    }

    /**
     * Write {@code TEMP_OFFSET}, added to every result after linearization.
     *
     * @param celsius calibration offset in °C (−256.0 to 255.9921875, clamped)
     * @throws IOException on bus error
     */
    public void setTemperatureOffset(double celsius) throws IOException {
        writeReg(REG_TEMP_OFFSET, encodeTemperature(celsius));
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    /**
     * Software reset ({@code Soft_Reset} = 1), then wait the 2&nbsp;ms reset time. Reloads
     * {@code CONFIGURATION}, {@code THIGH_LIMIT}, {@code TLOW_LIMIT} and {@code TEMP_OFFSET} from
     * EEPROM.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        writeReg(REG_CONFIG, CFG_SOFT_RESET);
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // EEPROM
    // -------------------------------------------------------------------------

    /**
     * Unlock the EEPROM ({@code EUN} = 1). While unlocked, writes to {@code CONFIGURATION},
     * {@code THIGH_LIMIT}, {@code TLOW_LIMIT}, {@code TEMP_OFFSET} and {@code EEPROM2} also program
     * the EEPROM as the new power-on default. Poll {@link #isEepromBusy()} after each such write.
     *
     * @throws IOException on bus error
     */
    public void unlockEeprom() throws IOException {
        writeReg(REG_EEPROM_UL, EUN);
    }

    /**
     * Lock the EEPROM ({@code EUN} = 0); register writes become volatile only.
     *
     * @throws IOException on bus error
     */
    public void lockEeprom() throws IOException {
        writeReg(REG_EEPROM_UL, 0x0000);
    }

    /**
     * Report whether an EEPROM programming operation is in progress.
     *
     * @return {@code true} if {@code EEPROM_Busy} is set
     * @throws IOException on bus error
     */
    public boolean isEepromBusy() throws IOException {
        return (readReg(REG_EEPROM_UL) & EEPROM_BUSY) != 0;
    }

    /**
     * Read a general-purpose EEPROM scratch register.
     *
     * @param slot 1 ({@code EEPROM1}), 2 ({@code EEPROM2}) or 3 ({@code EEPROM3}); slots 1 and 3
     *             hold factory NIST-traceability data
     * @return 16-bit register value
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code slot} is not 1, 2 or 3
     */
    public int readEepromScratch(int slot) throws IOException {
        int reg = switch (slot) {
            case 1 -> REG_EEPROM1;
            case 2 -> REG_EEPROM2;
            case 3 -> REG_EEPROM3;
            default -> throw new IllegalArgumentException("slot must be 1, 2 or 3");
        };
        return readReg(reg);
    }

    /**
     * Write the general-purpose {@code EEPROM2} scratch register. Only slot 2 is writable —
     * {@code EEPROM1}/{@code EEPROM3} hold factory NIST-traceability data. Persists across power
     * cycles only while the EEPROM is unlocked.
     *
     * @param slot  must be 2
     * @param value 16-bit value
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code slot} is not 2
     */
    public void writeEepromScratch(int slot, int value) throws IOException {
        if (slot != 2) throw new IllegalArgumentException("only EEPROM scratch slot 2 is writable");
        writeReg(REG_EEPROM2, value & 0xFFFF);
    }

    // -------------------------------------------------------------------------
    // Alert output
    // -------------------------------------------------------------------------

    /**
     * Configure the {@code ALERT} output's mode, polarity and pin function together.
     *
     * @param mode        window alert or latching Therm
     * @param polarity    active-low (POR default) or active-high
     * @param pinFunction alert/Therm status or Data-Ready
     * @throws IOException on bus error
     */
    public void configureAlert(AlertMode mode, AlertPolarity polarity, AlertPinFunction pinFunction)
            throws IOException {
        int config = readConfig() & ~(CFG_TNA | CFG_POL | CFG_DR_ALERT);
        if (mode == AlertMode.THERM) config |= CFG_TNA;
        if (polarity == AlertPolarity.ACTIVE_HIGH) config |= CFG_POL;
        if (pinFunction == AlertPinFunction.DATA_READY) config |= CFG_DR_ALERT;
        writeConfig(config);
    }

    /**
     * Restore the POR default {@code ALERT} configuration: Alert mode, active-low, alert status.
     *
     * @throws IOException on bus error
     */
    public void configureAlert() throws IOException {
        configureAlert(AlertMode.ALERT, AlertPolarity.ACTIVE_LOW, AlertPinFunction.ALERT);
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 2)
    // -------------------------------------------------------------------------

    /**
     * Read {@code CONFIGURATION}'s {@code HIGH_Alert} / {@code LOW_Alert} flags. In Alert mode this
     * read also clears both flags (a hardware side effect); in Therm mode {@code HIGH_Alert} clears
     * only once the result drops below {@code TLOW_LIMIT}.
     *
     * @return mask of {@link #SOURCE_HIGH} / {@link #SOURCE_LOW}
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int config = readReg(REG_CONFIG);
        int status = 0;
        if ((config & CFG_HIGH_ALERT) != 0) status |= SOURCE_HIGH;
        if ((config & CFG_LOW_ALERT) != 0) status |= SOURCE_LOW;
        return status;
    }

    /**
     * Subscribe to {@code ALERT} events using {@code connection.intPin()} (or a 5&nbsp;ms polling
     * thread if none is wired).
     *
     * @param callback called with the {@link #pollInterrupt()} mask
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to {@code ALERT} events, overriding which {@link InputPin} delivers edges. With a
     * pin, the callback runs on every {@code ALERT} edge — falling for active-low, rising for
     * active-high, following the configured {@code POL}. The polling fallback calls it whenever the
     * status mask changes.
     *
     * @param callback called with the {@link #pollInterrupt()} mask
     * @param intPin   ALERT pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            boolean activeHigh = (readReg(REG_CONFIG) & CFG_POL) != 0;
            this.intPin = intPin;
            intPin.onEdge(edgeHandler, activeHigh ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
            startPolling(pollInterrupt());
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        stopPolling();
    }

    private void startPolling(int initialStatus) {
        polling = true;
        pollThread = new Thread(() -> {
            int last = initialStatus;
            while (polling) {
                try {
                    int status = pollInterrupt();
                    IntConsumer cb = callback;
                    if (status != last && cb != null) cb.accept(status);
                    last = status;
                } catch (IOException ignored) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "tmp117-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
