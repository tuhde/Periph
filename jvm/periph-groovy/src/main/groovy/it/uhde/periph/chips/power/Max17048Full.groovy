package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * MAX17048 / MAX17049 full driver — extends {@link Max17048Minimal} with alerts, RCOMP, hibernate,
 * sleep and reset.
 *
 * <p>Interrupt sources: {@link #SOURCE_RESET}, {@link #SOURCE_OVERVOLTAGE}, {@link #SOURCE_UNDERVOLTAGE},
 * {@link #SOURCE_VRESET}, {@link #SOURCE_LOW_SOC}, {@link #SOURCE_SOC_CHANGE}.
 * {@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge on ALRT),
 * otherwise a 1 s polling thread.
 */
@CompileStatic
class Max17048Full extends Max17048Minimal {

    /** Interrupt source: STATUS.RI, the chip has reset. */
    static final int SOURCE_RESET = 0x0100
    /** Interrupt source: STATUS.VH, VCELL above VALRT.MAX. */
    static final int SOURCE_OVERVOLTAGE = 0x0200
    /** Interrupt source: STATUS.VL, VCELL below VALRT.MIN. */
    static final int SOURCE_UNDERVOLTAGE = 0x0400
    /** Interrupt source: STATUS.VR, voltage-reset alert (needs enableVoltageResetAlert). */
    static final int SOURCE_VRESET = 0x0800
    /** Interrupt source: STATUS.HD, SOC crossed the empty-alert threshold. */
    static final int SOURCE_LOW_SOC = 0x1000
    /** Interrupt source: STATUS.SC, SOC changed by at least 1 % (needs setSocChangeAlert). */
    static final int SOURCE_SOC_CHANGE = 0x2000

    private static final int SOURCES = 0x3F00

    private boolean sleepOn
    private volatile IntConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = { handleEdge() } as EdgeHandler

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to address 0x36
     * @param cells 1 for the MAX17048, 2 for the MAX17049
     */
    Max17048Full(RegisterConnection connection, int cells = 1) {
        super(connection, cells)
    }

    /** @return the raw VCELL register */
    int rawVoltage() { readWord(REG_VCELL) }

    /** @return the raw SOC register */
    int rawSoc() { readWord(REG_SOC) }

    /** @return the charge/discharge rate in %/h (signed CRATE × 0.208), positive while charging */
    double chargeRate() { ((short) readWord(REG_CRATE)) * 0.208d }

    /** @return the VERSION register (0x001x) */
    int version() { readWord(REG_VERSION) }

    /** @return the factory-programmed ID (low byte of VRESET/ID) */
    int chipId() { readWord(REG_VRESET) & 0xFF }

    /** Restart the SOC estimate from the present voltage (MODE.QuickStart); only if the first SOC is clearly wrong. */
    void quickStart() { writeWord(REG_MODE, 0x4000 | (sleepOn ? 0x2000 : 0)) }

    /** Power-on reset (CMD ← 0x5400). The chip does not ACK, so a bus error is ignored; waits 1 s. */
    void reset() {
        try {
            writeWord(REG_CMD, 0x5400)
        } catch (IOException ignored) {
            // the chip does not acknowledge the reset
        }
        try {
            Thread.sleep(1000L)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    /** Write RCOMP (CONFIG bits 15:8, read-modify-write); value 0–255. */
    void setRcomp(int value) { modify(REG_CONFIG, 0xFF00, (value & 0xFF) << 8) }

    /** @return RCOMP 0–255 */
    int getRcomp() { readWord(REG_CONFIG) >> 8 }

    /** Set RCOMP from the datasheet formula with the default coefficients (RCOMP0 = 0x97). */
    void compensateTemperature(double celsius) {
        double coefficient = celsius >= 20.0d ? -0.5d : -5.0d
        setRcomp((int) Math.max(0L, Math.min(255L, Math.round(0x97 + (celsius - 20.0d) * coefficient))))
    }

    /** Set the empty-alert threshold (CONFIG.ATHD = 32 − percent); percent 1–32. */
    void setAlertSoc(int percent) { modify(REG_CONFIG, 0x001F, 32 - Math.max(1, Math.min(32, percent))) }

    /** @return the empty-alert threshold in % (1–32) */
    int getAlertSoc() { 32 - (readWord(REG_CONFIG) & 0x1F) }

    /** Enable or disable the alert on a SOC change of at least 1 % (CONFIG.ALSC). */
    void setSocChangeAlert(boolean enabled) { modify(REG_CONFIG, 0x0040, enabled ? 0x0040 : 0) }

    /**
     * Set the voltage alert window in 20 mV steps (VALRT). minV = 0 and maxV = 5.1 disable the limits.
     *
     * @throws IllegalArgumentException if minV &gt; maxV
     */
    void setVoltageAlert(double minV, double maxV) {
        if (minV > maxV) throw new IllegalArgumentException('minV must not exceed maxV')
        writeWord(REG_VALRT, (clampByte(minV / 0.020d) << 8) | clampByte(maxV / 0.020d))
    }

    /** @return {minV, maxV} in volts */
    double[] getVoltageAlert() {
        int raw = readWord(REG_VALRT)
        [(raw >> 8) * 0.020d, (raw & 0xFF) * 0.020d] as double[]
    }

    /** Set the reset voltage (VRESET bits 15:9, 40 mV steps); volts 0.0–5.08. */
    void setResetVoltage(double volts) {
        int steps = (int) Math.max(0L, Math.min(127L, Math.round(volts / 0.040d)))
        modify(REG_VRESET, 0xFE00, steps << 9)
    }

    /** @return the reset voltage in V */
    double getResetVoltage() { (readWord(REG_VRESET) >> 9) * 0.040d }

    /** Let a voltage-reset event assert ALRT (STATUS.ENVR). */
    void enableVoltageResetAlert(boolean enabled) { modify(REG_STATUS, 0x4000, enabled ? 0x4000 : 0) }

    /** Disable the analog comparator in hibernate mode (VRESET/ID.DIS, saves about 0.5 µA). */
    void setComparatorDisabled(boolean disabled) { modify(REG_VRESET, 0x0100, disabled ? 0x0100 : 0) }

    /**
     * Program the hibernate thresholds (HIBRT): hibernate below hibThrPctPerH (0.208 %/h steps),
     * wake when |OCV − VCELL| exceeds actThrV (1.25 mV steps).
     */
    void setHibernateThresholds(double hibThrPctPerH, double actThrV) {
        writeWord(REG_HIBRT, (clampByte(hibThrPctPerH / 0.208d) << 8) | clampByte(actThrV / 0.00125d))
    }

    /** @return {hibThrPctPerH, actThrV} */
    double[] getHibernateThresholds() {
        int raw = readWord(REG_HIBRT)
        [(raw >> 8) * 0.208d, (raw & 0xFF) * 0.00125d] as double[]
    }

    /** Never hibernate (HIBRT ← 0x0000). */
    void disableHibernate() { writeWord(REG_HIBRT, 0x0000) }

    /** Always stay in hibernate (HIBRT ← 0xFFFF). */
    void forceHibernate() { writeWord(REG_HIBRT, 0xFFFF) }

    /** @return MODE.HibStat; false if the write-only register cannot be read */
    boolean isHibernating() {
        try {
            return (readWord(REG_MODE) & 0x1000) != 0
        } catch (IOException ignored) {
            return false
        }
    }

    /** Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole). */
    void enableSleep(boolean enabled) {
        sleepOn = enabled
        writeWord(REG_MODE, enabled ? 0x2000 : 0x0000)
    }

    /** Enter sleep mode (CONFIG.SLEEP = 1); needs enableSleep(true) first. */
    void sleep() { modify(REG_CONFIG, 0, 0x0080) }

    /** Leave sleep mode (CONFIG.SLEEP = 0). */
    void wake() { modify(REG_CONFIG, 0x0080, 0) }

    /** @return the STATUS register */
    int readStatus() { readWord(REG_STATUS) }

    /** Clear the STATUS bits in mask and CONFIG.ALRT (releases the ALRT pin). */
    void clearStatus(int mask) {
        modify(REG_STATUS, mask, 0)
        modify(REG_CONFIG, 0x0020, 0)
    }

    /** @return the raw CONFIG register */
    int readConfig() { readWord(REG_CONFIG) }

    /** Write the raw CONFIG register. */
    void writeConfig(int value) { writeWord(REG_CONFIG, value) }

    /** Enable an alert source that has a control bit (SOURCE_SOC_CHANGE, SOURCE_VRESET). */
    void enableInterrupt(int source) {
        if (source == SOURCE_SOC_CHANGE) setSocChangeAlert(true)
        else if (source == SOURCE_VRESET) enableVoltageResetAlert(true)
    }

    /** Disable an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF). */
    void disableInterrupt(int source) {
        if (source == SOURCE_SOC_CHANGE) setSocChangeAlert(false)
        else if (source == SOURCE_VRESET) enableVoltageResetAlert(false)
        else if (source == SOURCE_UNDERVOLTAGE) modify(REG_VALRT, 0xFF00, 0)
        else if (source == SOURCE_OVERVOLTAGE) modify(REG_VALRT, 0x00FF, 0x00FF)
    }

    /**
     * Read STATUS, clear the reported flags and CONFIG.ALRT.
     *
     * @return the SOURCE_* bits (0 = no alert)
     */
    synchronized int pollInterrupt() {
        int flags = readWord(REG_STATUS) & SOURCES
        if (flags != 0) clearStatus(flags)
        flags
    }

    /** Subscribe to ALRT using {@code connection.intPin()} (falling edge), or a 1 s polling thread if none is wired. */
    void onInterrupt(IntConsumer callback) { onInterrupt(callback, connection.intPin()) }

    /**
     * Subscribe to ALRT on an explicit pin.
     *
     * @param callback receives the SOURCE_* bits
     * @param intPin pin to arm, or null to force the 1 s polling fallback
     */
    void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(1000L)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } as Runnable, 'max17048-poll')
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback
        if (cb == null) return
        try {
            int status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (IOException ignored) {
            // transient bus error: the next edge or tick retries
        }
    }

    private static int clampByte(double v) { (int) Math.max(0L, Math.min(255L, Math.round(v))) }
}
