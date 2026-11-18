package it.uhde.periph.chips.power;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * MAX17048 / MAX17049 full driver — extends {@link Max17048Minimal} with alerts, RCOMP,
 * hibernate, sleep and reset.
 *
 * <p>Interrupt sources ({@link #pollInterrupt()}, callback, {@link #enableInterrupt(int)}):
 * {@link #SOURCE_RESET}, {@link #SOURCE_OVERVOLTAGE}, {@link #SOURCE_UNDERVOLTAGE},
 * {@link #SOURCE_VRESET}, {@link #SOURCE_LOW_SOC}, {@link #SOURCE_SOC_CHANGE}.
 * {@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge on
 * ALRT), otherwise a 1 s polling thread.
 */
public class Max17048Full extends Max17048Minimal {

    /** Interrupt source: STATUS.RI, the chip has reset. */
    public static final int SOURCE_RESET = 0x0100;
    /** Interrupt source: STATUS.VH, VCELL above VALRT.MAX. */
    public static final int SOURCE_OVERVOLTAGE = 0x0200;
    /** Interrupt source: STATUS.VL, VCELL below VALRT.MIN. */
    public static final int SOURCE_UNDERVOLTAGE = 0x0400;
    /** Interrupt source: STATUS.VR, voltage-reset alert (needs {@link #enableVoltageResetAlert}). */
    public static final int SOURCE_VRESET = 0x0800;
    /** Interrupt source: STATUS.HD, SOC crossed the empty-alert threshold. */
    public static final int SOURCE_LOW_SOC = 0x1000;
    /** Interrupt source: STATUS.SC, SOC changed by at least 1 % (needs {@link #setSocChangeAlert}). */
    public static final int SOURCE_SOC_CHANGE = 0x2000;

    private static final int SOURCES = 0x3F00;

    private boolean sleepEnabled;
    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to address 0x36
     * @param cells      1 for the MAX17048, 2 for the MAX17049
     * @throws IOException on I²C error
     */
    public Max17048Full(RegisterConnection connection, int cells) throws IOException {
        super(connection, cells);
    }

    /**
     * Construct the driver for a single cell (MAX17048).
     *
     * @param connection I²C connection bound to address 0x36
     * @throws IOException on I²C error
     */
    public Max17048Full(RegisterConnection connection) throws IOException {
        this(connection, 1);
    }

    /** @return the raw VCELL register @throws IOException on I²C error */
    public int rawVoltage() throws IOException {
        return readWord(REG_VCELL);
    }

    /** @return the raw SOC register @throws IOException on I²C error */
    public int rawSoc() throws IOException {
        return readWord(REG_SOC);
    }

    /**
     * Read the charge/discharge rate.
     *
     * @return rate in %/h (signed CRATE × 0.208), positive while charging
     * @throws IOException on I²C error
     */
    public double chargeRate() throws IOException {
        return (short) readWord(REG_CRATE) * 0.208;
    }

    /** @return the VERSION register (0x001x) @throws IOException on I²C error */
    public int version() throws IOException {
        return readWord(REG_VERSION);
    }

    /** @return the factory-programmed ID (low byte of VRESET/ID) @throws IOException on I²C error */
    public int chipId() throws IOException {
        return readWord(REG_VRESET) & 0xFF;
    }

    /**
     * Restart the SOC estimate from the present voltage (MODE.QuickStart); only use when the first
     * SOC after power-up is clearly wrong.
     *
     * @throws IOException on I²C error
     */
    public void quickStart() throws IOException {
        writeWord(REG_MODE, 0x4000 | (sleepEnabled ? 0x2000 : 0));
    }

    /**
     * Power-on reset (CMD ← 0x5400). The chip does not ACK, so a bus error is ignored; waits 1 s.
     *
     * @throws IOException never in practice; declared for interrupt safety
     */
    public void reset() throws IOException {
        try {
            writeWord(REG_CMD, 0x5400);
        } catch (IOException e) {
            // ignored: the chip does not acknowledge the reset
        }
        pause(1000);
    }

    /**
     * Write RCOMP (CONFIG bits 15:8, read-modify-write).
     *
     * @param value RCOMP 0–255
     * @throws IOException on I²C error
     */
    public void setRcomp(int value) throws IOException {
        modify(REG_CONFIG, 0xFF00, (value & 0xFF) << 8);
    }

    /** @return RCOMP 0–255 @throws IOException on I²C error */
    public int getRcomp() throws IOException {
        return readWord(REG_CONFIG) >> 8;
    }

    /**
     * Set RCOMP from the datasheet formula with the default coefficients (RCOMP0 = 0x97).
     *
     * @param celsius cell temperature in °C
     * @throws IOException on I²C error
     */
    public void compensateTemperature(double celsius) throws IOException {
        double coefficient = celsius >= 20.0 ? -0.5 : -5.0;
        setRcomp((int) Math.max(0, Math.min(255, Math.round(0x97 + (celsius - 20.0) * coefficient))));
    }

    /**
     * Set the empty-alert threshold (CONFIG.ATHD = 32 − percent).
     *
     * @param percent threshold in % (1–32)
     * @throws IOException on I²C error
     */
    public void setAlertSoc(int percent) throws IOException {
        modify(REG_CONFIG, 0x001F, 32 - Math.max(1, Math.min(32, percent)));
    }

    /** @return the empty-alert threshold in % (1–32) @throws IOException on I²C error */
    public int getAlertSoc() throws IOException {
        return 32 - (readWord(REG_CONFIG) & 0x1F);
    }

    /**
     * Enable or disable the alert on a SOC change of at least 1 % (CONFIG.ALSC).
     *
     * @param enabled true to enable
     * @throws IOException on I²C error
     */
    public void setSocChangeAlert(boolean enabled) throws IOException {
        modify(REG_CONFIG, 0x0040, enabled ? 0x0040 : 0);
    }

    /**
     * Set the voltage alert window in 20 mV steps (VALRT).
     *
     * @param minV under-voltage threshold in V; 0 disables it
     * @param maxV over-voltage threshold in V; 5.1 disables it
     * @throws IllegalArgumentException if minV &gt; maxV
     * @throws IOException on I²C error
     */
    public void setVoltageAlert(double minV, double maxV) throws IOException {
        if (minV > maxV) throw new IllegalArgumentException("minV must not exceed maxV");
        writeWord(REG_VALRT, (clampByte(minV / 0.020) << 8) | clampByte(maxV / 0.020));
    }

    /**
     * Read the voltage alert window.
     *
     * @return {minV, maxV} in volts
     * @throws IOException on I²C error
     */
    public double[] getVoltageAlert() throws IOException {
        int raw = readWord(REG_VALRT);
        return new double[]{(raw >> 8) * 0.020, (raw & 0xFF) * 0.020};
    }

    /**
     * Set the reset voltage (VRESET bits 15:9, 40 mV steps).
     *
     * @param volts reset voltage in V (0.0–5.08)
     * @throws IOException on I²C error
     */
    public void setResetVoltage(double volts) throws IOException {
        int steps = (int) Math.max(0, Math.min(127, Math.round(volts / 0.040)));
        modify(REG_VRESET, 0xFE00, steps << 9);
    }

    /** @return the reset voltage in V @throws IOException on I²C error */
    public double getResetVoltage() throws IOException {
        return (readWord(REG_VRESET) >> 9) * 0.040;
    }

    /**
     * Let a voltage-reset event assert ALRT (STATUS.ENVR).
     *
     * @param enabled true to enable
     * @throws IOException on I²C error
     */
    public void enableVoltageResetAlert(boolean enabled) throws IOException {
        modify(REG_STATUS, 0x4000, enabled ? 0x4000 : 0);
    }

    /**
     * Disable the analog comparator in hibernate mode (VRESET/ID.DIS, saves about 0.5 µA).
     *
     * @param disabled true to disable the comparator
     * @throws IOException on I²C error
     */
    public void setComparatorDisabled(boolean disabled) throws IOException {
        modify(REG_VRESET, 0x0100, disabled ? 0x0100 : 0);
    }

    /**
     * Program the hibernate thresholds (HIBRT).
     *
     * @param hibThrPctPerH hibernate when |CRATE| stays below this rate in %/h (0.208 %/h steps)
     * @param actThrV       leave hibernate when |OCV − VCELL| exceeds this in V (1.25 mV steps)
     * @throws IOException on I²C error
     */
    public void setHibernateThresholds(double hibThrPctPerH, double actThrV) throws IOException {
        writeWord(REG_HIBRT, (clampByte(hibThrPctPerH / 0.208) << 8) | clampByte(actThrV / 0.00125));
    }

    /**
     * Read the hibernate thresholds.
     *
     * @return {hibThrPctPerH, actThrV}
     * @throws IOException on I²C error
     */
    public double[] getHibernateThresholds() throws IOException {
        int raw = readWord(REG_HIBRT);
        return new double[]{(raw >> 8) * 0.208, (raw & 0xFF) * 0.00125};
    }

    /** Never hibernate (HIBRT ← 0x0000). @throws IOException on I²C error */
    public void disableHibernate() throws IOException {
        writeWord(REG_HIBRT, 0x0000);
    }

    /** Always stay in hibernate (HIBRT ← 0xFFFF). @throws IOException on I²C error */
    public void forceHibernate() throws IOException {
        writeWord(REG_HIBRT, 0xFFFF);
    }

    /** @return MODE.HibStat; false if the write-only register cannot be read */
    public boolean isHibernating() {
        try {
            return (readWord(REG_MODE) & 0x1000) != 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole).
     *
     * @param enabled true to allow sleep
     * @throws IOException on I²C error
     */
    public void enableSleep(boolean enabled) throws IOException {
        sleepEnabled = enabled;
        writeWord(REG_MODE, enabled ? 0x2000 : 0x0000);
    }

    /** Enter sleep mode (CONFIG.SLEEP = 1); needs {@link #enableSleep(boolean)}. @throws IOException on I²C error */
    public void sleep() throws IOException {
        modify(REG_CONFIG, 0, 0x0080);
    }

    /** Leave sleep mode (CONFIG.SLEEP = 0). @throws IOException on I²C error */
    public void wake() throws IOException {
        modify(REG_CONFIG, 0x0080, 0);
    }

    /** @return the STATUS register @throws IOException on I²C error */
    public int readStatus() throws IOException {
        return readWord(REG_STATUS);
    }

    /**
     * Clear the given STATUS bits and CONFIG.ALRT (releases the ALRT pin).
     *
     * @param mask STATUS bits to clear, e.g. {@link #SOURCE_RESET}
     * @throws IOException on I²C error
     */
    public void clearStatus(int mask) throws IOException {
        modify(REG_STATUS, mask, 0);
        modify(REG_CONFIG, 0x0020, 0);
    }

    /** @return the raw CONFIG register @throws IOException on I²C error */
    public int readConfig() throws IOException {
        return readWord(REG_CONFIG);
    }

    /**
     * Write the raw CONFIG register.
     *
     * @param value 16-bit value
     * @throws IOException on I²C error
     */
    public void writeConfig(int value) throws IOException {
        writeWord(REG_CONFIG, value);
    }

    /**
     * Enable an alert source that has a control bit ({@link #SOURCE_SOC_CHANGE}, {@link #SOURCE_VRESET}).
     *
     * @param source one of the SOURCE_* constants
     * @throws IOException on I²C error
     */
    public void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_SOC_CHANGE) setSocChangeAlert(true);
        else if (source == SOURCE_VRESET) enableVoltageResetAlert(true);
    }

    /**
     * Disable an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF).
     *
     * @param source one of the SOURCE_* constants
     * @throws IOException on I²C error
     */
    public void disableInterrupt(int source) throws IOException {
        if (source == SOURCE_SOC_CHANGE) setSocChangeAlert(false);
        else if (source == SOURCE_VRESET) enableVoltageResetAlert(false);
        else if (source == SOURCE_UNDERVOLTAGE) modify(REG_VALRT, 0xFF00, 0);
        else if (source == SOURCE_OVERVOLTAGE) modify(REG_VALRT, 0x00FF, 0x00FF);
    }

    /**
     * Read STATUS, clear the reported flags and CONFIG.ALRT.
     *
     * @return the SOURCE_* bits (0 = no alert)
     * @throws IOException on I²C error
     */
    public synchronized int pollInterrupt() throws IOException {
        int flags = readWord(REG_STATUS) & SOURCES;
        if (flags != 0) clearStatus(flags);
        return flags;
    }

    /**
     * Subscribe to ALRT using {@code connection.intPin()} (falling edge), or a 1 s polling thread if none is wired.
     *
     * @param callback receives the SOURCE_* bits
     * @throws IOException on I²C error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to ALRT on an explicit pin.
     *
     * @param callback receives the SOURCE_* bits
     * @param intPin   pin to arm, or {@code null} to force the 1 s polling fallback
     * @throws IOException on I²C error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "max17048-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // transient bus error: the next edge or tick retries
        }
    }

    private static int clampByte(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
