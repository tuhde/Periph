package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * LC709203F — full driver. Extends {@link LC709203FMinimal} with RSOC control, thermistor mode,
 * APA/APT, current direction, battery profiles, alarms and power mode.
 *
 * <p>Constants: {@link #DIRECTION_AUTO}, {@link #DIRECTION_CHARGE}, {@link #DIRECTION_DISCHARGE};
 * {@link #ALARM_RSOC}, {@link #ALARM_VOLTAGE} (bitmask returned by {@link #pollInterrupt()}).
 *
 * <p>The chip has no status register; {@link #pollInterrupt()} evaluates RSOC and voltage against
 * the cached thresholds. {@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired
 * (falling edge on ALARMB), otherwise a 1 s polling thread.
 */
@CompileStatic
class LC709203FFull extends LC709203FMinimal {

    /** Current direction: automatic. */
    static final int DIRECTION_AUTO = 0x0000
    /** Current direction: charge. */
    static final int DIRECTION_CHARGE = 0x0001
    /** Current direction: discharge. */
    static final int DIRECTION_DISCHARGE = 0xFFFF
    /** Alarm bitmask bit: RSOC below the Alarm Low RSOC threshold. */
    static final int ALARM_RSOC = 0x01
    /** Alarm bitmask bit: cell voltage below the Alarm Low Cell Voltage threshold. */
    static final int ALARM_VOLTAGE = 0x02

    private int cachedAlarmRsoc
    private int cachedAlarmVoltage
    private volatile IntConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = { handleEdge() } as EdgeHandler

    /**
     * Construct the driver and run the starting flow.
     *
     * @param connection I²C connection bound to address 0x0B
     * @param capacityMah design capacity in mAh
     * @param profile battery profile (0 or 1)
     */
    LC709203FFull(RegisterConnection connection, int capacityMah = 1000, int profile = 1) {
        super(connection, capacityMah, profile)
        cachedAlarmRsoc = readWord(REG_ALARM_RSOC)
        cachedAlarmVoltage = readWord(REG_ALARM_VOLTAGE)
    }

    /** @return state of charge in % (0–100) */
    int rsoc() { readWord(REG_RSOC) }

    /**
     * Force the RSOC register (use sleep mode to keep the value).
     *
     * @param percent state of charge in % (0–100)
     */
    void setRsoc(int percent) { writeWord(REG_RSOC, Math.max(0, Math.min(100, percent))) }

    /** Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms. */
    void initRsoc() {
        writeWord(REG_INITIAL_RSOC, 0xAA55)
        pause(2L)
    }

    /** Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms. */
    void beforeRsoc() {
        writeWord(REG_BEFORE_RSOC, 0xAA55)
        pause(2L)
    }

    /**
     * Switch to thermistor temperature mode.
     *
     * @param bConstant thermistor B constant in K
     */
    void useThermistor(int bConstant = 3380) {
        writeWord(REG_THERMISTOR_B, bConstant)
        writeWord(REG_STATUS, 0x0001)
    }

    /**
     * Switch to I²C temperature mode and write a temperature.
     *
     * @param celsius cell temperature in °C
     */
    void useI2cTemperature(double celsius = 25.0d) {
        writeWord(REG_STATUS, 0x0000)
        setTemperature(celsius)
    }

    /** @return true if the temperature source is the thermistor */
    boolean isThermistorMode() { readWord(REG_STATUS) == 0x0001 }

    /** @return thermistor B constant in K */
    int getBConstant() { readWord(REG_THERMISTOR_B) }

    /**
     * Write the Adjustment Pack Application register.
     *
     * @param value APA value (0–255)
     */
    void setApa(int value) { writeWord(REG_APA, value & 0xFF) }

    /** @return Adjustment Pack Application register */
    int getApa() { readWord(REG_APA) }

    /**
     * Write the Adjustment Pack Thermistor register.
     *
     * @param value APT value (0–65535)
     */
    void setApt(int value) { writeWord(REG_APT, value & 0xFFFF) }

    /** @return Adjustment Pack Thermistor register */
    int getApt() { readWord(REG_APT) }

    /**
     * Set the current direction.
     *
     * @param mode {@link #DIRECTION_AUTO}, {@link #DIRECTION_CHARGE} or {@link #DIRECTION_DISCHARGE}
     * @throws IllegalArgumentException for any other value
     */
    void setCurrentDirection(int mode) {
        if (mode != DIRECTION_AUTO && mode != DIRECTION_CHARGE && mode != DIRECTION_DISCHARGE) {
            throw new IllegalArgumentException('invalid current direction: ' + mode)
        }
        writeWord(REG_DIRECTION, mode)
    }

    /** @return current direction register */
    int getCurrentDirection() { readWord(REG_DIRECTION) }

    /**
     * Select battery profile 0 or 1: re-initializes the chip (waits 90 ms), then re-enters operational mode.
     *
     * @param profile 0 or 1
     */
    void setProfile(int profile) {
        writeWord(REG_PROFILE, profile & 1)
        pause(100L)
        wakeAndOperational()
    }

    /** @return selected battery profile (0 or 1) */
    int getProfile() { readWord(REG_PROFILE) }

    /** @return Number of the Parameter register (e.g. 0x0301) */
    int profileId() { readWord(REG_PROFILE_ID) }

    /** @return IC Version register */
    int icVersion() { readWord(REG_IC_VERSION) }

    /**
     * Set the low-RSOC alarm threshold.
     *
     * @param percent 0 disables, 1–100 %
     */
    void setAlarmRsoc(int percent) {
        int p = Math.max(0, Math.min(100, percent))
        writeWord(REG_ALARM_RSOC, p)
        cachedAlarmRsoc = p
    }

    /** @return low-RSOC alarm threshold in % */
    int getAlarmRsoc() { readWord(REG_ALARM_RSOC) }

    /**
     * Set the low-voltage alarm threshold.
     *
     * @param volts 0 disables; otherwise volts (resolution 1 mV)
     */
    void setAlarmVoltage(double volts) {
        int raw = (int) Math.max(0L, Math.min(0xFFFFL, Math.round(volts * 1000.0d)))
        writeWord(REG_ALARM_VOLTAGE, raw)
        cachedAlarmVoltage = raw
    }

    /** @return low-voltage alarm threshold in V */
    double getAlarmVoltage() { readWord(REG_ALARM_VOLTAGE) / 1000.0d }

    /** Enter sleep mode (only I²C stays alive; charge counting stops). */
    void sleep() { writeWord(REG_POWER_MODE, 0x0002) }

    /** Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode. */
    void wake() { wakeAndOperational() }

    /** @return true if the chip is in sleep mode (the bus is woken first) */
    boolean isSleeping() {
        try {
            connection.write(REG_POWER_MODE, new byte[0]) // command code only: ignored by the chip
        } catch (IOException ignored) {
            // wake-up pulse
        }
        pause(1L)
        readWord(REG_POWER_MODE) == 0x0002
    }

    /**
     * Evaluate RSOC and voltage against the cached alarm thresholds.
     *
     * @return bitmask of {@link #ALARM_RSOC} / {@link #ALARM_VOLTAGE} (0 = no alarm)
     */
    synchronized int pollInterrupt() {
        int status = 0
        if (cachedAlarmRsoc != 0 && readWord(REG_RSOC) < cachedAlarmRsoc) status |= ALARM_RSOC
        if (cachedAlarmVoltage != 0 && readWord(REG_VOLTAGE) < cachedAlarmVoltage) status |= ALARM_VOLTAGE
        status
    }

    /**
     * Subscribe to ALARMB using {@code connection.intPin()} (falling edge), or a 1 s polling thread if none is wired.
     *
     * @param callback receives the alarm bitmask
     */
    void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to ALARMB on an explicit pin.
     *
     * @param callback receives the alarm bitmask
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
            } as Runnable, 'lc709203f-poll')
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
}
