package it.uhde.periph.chips.power;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * LC709203F — full driver. Extends {@link LC709203FMinimal} with RSOC control, thermistor mode,
 * APA/APT, current direction, battery profiles, alarms and power mode.
 *
 * <h2>Constants</h2>
 * <ul>
 *   <li>{@link #DIRECTION_AUTO}, {@link #DIRECTION_CHARGE}, {@link #DIRECTION_DISCHARGE}</li>
 *   <li>{@link #ALARM_RSOC}, {@link #ALARM_VOLTAGE} — bitmask returned by {@link #pollInterrupt()}</li>
 * </ul>
 *
 * <p>The chip has no status register; {@link #pollInterrupt()} evaluates RSOC and voltage against
 * the cached thresholds. {@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired
 * (falling edge on ALARMB), otherwise a 1 s polling thread.
 */
public class LC709203FFull extends LC709203FMinimal {

    /** Current direction: automatic. */
    public static final int DIRECTION_AUTO = 0x0000;
    /** Current direction: charge. */
    public static final int DIRECTION_CHARGE = 0x0001;
    /** Current direction: discharge. */
    public static final int DIRECTION_DISCHARGE = 0xFFFF;
    /** Alarm bitmask bit: RSOC below the Alarm Low RSOC threshold. */
    public static final int ALARM_RSOC = 0x01;
    /** Alarm bitmask bit: cell voltage below the Alarm Low Cell Voltage threshold. */
    public static final int ALARM_VOLTAGE = 0x02;

    private int alarmRsocRaw;
    private int alarmVoltageRaw;
    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the starting flow.
     *
     * @param connection  I²C connection bound to address 0x0B
     * @param capacityMah design capacity in mAh
     * @param profile     battery profile (0 or 1)
     * @throws IOException on I²C error
     */
    public LC709203FFull(RegisterConnection connection, int capacityMah, int profile) throws IOException {
        super(connection, capacityMah, profile);
        alarmRsocRaw = readWord(REG_ALARM_RSOC);
        alarmVoltageRaw = readWord(REG_ALARM_VOLTAGE);
    }

    /**
     * Construct the driver with 1000 mAh and profile 1.
     *
     * @param connection I²C connection bound to address 0x0B
     * @throws IOException on I²C error
     */
    public LC709203FFull(RegisterConnection connection) throws IOException {
        this(connection, 1000, 1);
    }

    /**
     * Read the RSOC register.
     *
     * @return state of charge in % (0–100)
     * @throws IOException on I²C error
     */
    public int rsoc() throws IOException {
        return readWord(REG_RSOC);
    }

    /**
     * Force the RSOC register (use sleep mode to keep the value).
     *
     * @param percent state of charge in % (0–100)
     * @throws IOException on I²C error
     */
    public void setRsoc(int percent) throws IOException {
        writeWord(REG_RSOC, Math.max(0, Math.min(100, percent)));
    }

    /**
     * Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms.
     *
     * @throws IOException on I²C error
     */
    public void initRsoc() throws IOException {
        writeWord(REG_INITIAL_RSOC, 0xAA55);
        pause(2);
    }

    /**
     * Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms.
     *
     * @throws IOException on I²C error
     */
    public void beforeRsoc() throws IOException {
        writeWord(REG_BEFORE_RSOC, 0xAA55);
        pause(2);
    }

    /**
     * Switch to thermistor temperature mode.
     *
     * @param bConstant thermistor B constant in K
     * @throws IOException on I²C error
     */
    public void useThermistor(int bConstant) throws IOException {
        writeWord(REG_THERMISTOR_B, bConstant);
        writeWord(REG_STATUS, 0x0001);
    }

    /**
     * Switch to I²C temperature mode and write a temperature.
     *
     * @param celsius cell temperature in °C
     * @throws IOException on I²C error
     */
    public void useI2cTemperature(double celsius) throws IOException {
        writeWord(REG_STATUS, 0x0000);
        setTemperature(celsius);
    }

    /**
     * @return true if the temperature source is the thermistor
     * @throws IOException on I²C error
     */
    public boolean isThermistorMode() throws IOException {
        return readWord(REG_STATUS) == 0x0001;
    }

    /**
     * @return thermistor B constant in K
     * @throws IOException on I²C error
     */
    public int getBConstant() throws IOException {
        return readWord(REG_THERMISTOR_B);
    }

    /**
     * Write the Adjustment Pack Application register.
     *
     * @param value APA value (0–255)
     * @throws IOException on I²C error
     */
    public void setApa(int value) throws IOException {
        writeWord(REG_APA, value & 0xFF);
    }

    /**
     * @return Adjustment Pack Application register
     * @throws IOException on I²C error
     */
    public int getApa() throws IOException {
        return readWord(REG_APA);
    }

    /**
     * Write the Adjustment Pack Thermistor register.
     *
     * @param value APT value (0–65535)
     * @throws IOException on I²C error
     */
    public void setApt(int value) throws IOException {
        writeWord(REG_APT, value & 0xFFFF);
    }

    /**
     * @return Adjustment Pack Thermistor register
     * @throws IOException on I²C error
     */
    public int getApt() throws IOException {
        return readWord(REG_APT);
    }

    /**
     * Set the current direction.
     *
     * @param mode {@link #DIRECTION_AUTO}, {@link #DIRECTION_CHARGE} or {@link #DIRECTION_DISCHARGE}
     * @throws IllegalArgumentException for any other value
     * @throws IOException on I²C error
     */
    public void setCurrentDirection(int mode) throws IOException {
        if (mode != DIRECTION_AUTO && mode != DIRECTION_CHARGE && mode != DIRECTION_DISCHARGE) {
            throw new IllegalArgumentException("invalid current direction: " + mode);
        }
        writeWord(REG_DIRECTION, mode);
    }

    /**
     * @return current direction register
     * @throws IOException on I²C error
     */
    public int getCurrentDirection() throws IOException {
        return readWord(REG_DIRECTION);
    }

    /**
     * Select battery profile 0 or 1: re-initializes the chip (waits 90 ms), then re-enters operational mode.
     *
     * @param profile 0 or 1
     * @throws IOException on I²C error
     */
    public void setProfile(int profile) throws IOException {
        writeWord(REG_PROFILE, profile & 1);
        pause(100);
        wakeAndOperational();
    }

    /**
     * @return selected battery profile (0 or 1)
     * @throws IOException on I²C error
     */
    public int getProfile() throws IOException {
        return readWord(REG_PROFILE);
    }

    /**
     * @return Number of the Parameter register (e.g. 0x0301)
     * @throws IOException on I²C error
     */
    public int profileId() throws IOException {
        return readWord(REG_PROFILE_ID);
    }

    /**
     * @return IC Version register
     * @throws IOException on I²C error
     */
    public int icVersion() throws IOException {
        return readWord(REG_IC_VERSION);
    }

    /**
     * Set the low-RSOC alarm threshold.
     *
     * @param percent 0 disables, 1–100 %
     * @throws IOException on I²C error
     */
    public void setAlarmRsoc(int percent) throws IOException {
        int p = Math.max(0, Math.min(100, percent));
        writeWord(REG_ALARM_RSOC, p);
        alarmRsocRaw = p;
    }

    /**
     * @return low-RSOC alarm threshold in %
     * @throws IOException on I²C error
     */
    public int getAlarmRsoc() throws IOException {
        return readWord(REG_ALARM_RSOC);
    }

    /**
     * Set the low-voltage alarm threshold.
     *
     * @param volts 0 disables; otherwise volts (resolution 1 mV)
     * @throws IOException on I²C error
     */
    public void setAlarmVoltage(double volts) throws IOException {
        int raw = (int) Math.max(0, Math.min(0xFFFF, Math.round(volts * 1000.0)));
        writeWord(REG_ALARM_VOLTAGE, raw);
        alarmVoltageRaw = raw;
    }

    /**
     * @return low-voltage alarm threshold in V
     * @throws IOException on I²C error
     */
    public double getAlarmVoltage() throws IOException {
        return readWord(REG_ALARM_VOLTAGE) / 1000.0;
    }

    /**
     * Enter sleep mode (only I²C stays alive; charge counting stops).
     *
     * @throws IOException on I²C error
     */
    public void sleep() throws IOException {
        writeWord(REG_POWER_MODE, 0x0002);
    }

    /**
     * Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        wakeAndOperational();
    }

    /**
     * @return true if the chip is in sleep mode (the bus is woken first)
     * @throws IOException on I²C error
     */
    public boolean isSleeping() throws IOException {
        try {
            connection.write(REG_POWER_MODE, new byte[0]); // command code only: ignored by the chip
        } catch (IOException e) {
            // ignored: wake-up pulse
        }
        pause(1);
        return readWord(REG_POWER_MODE) == 0x0002;
    }

    /**
     * Evaluate RSOC and voltage against the cached alarm thresholds.
     *
     * @return bitmask of {@link #ALARM_RSOC} / {@link #ALARM_VOLTAGE} (0 = no alarm)
     * @throws IOException on I²C error
     */
    public synchronized int pollInterrupt() throws IOException {
        int status = 0;
        if (alarmRsocRaw != 0 && readWord(REG_RSOC) < alarmRsocRaw) status |= ALARM_RSOC;
        if (alarmVoltageRaw != 0 && readWord(REG_VOLTAGE) < alarmVoltageRaw) status |= ALARM_VOLTAGE;
        return status;
    }

    /**
     * Subscribe to ALARMB using {@code connection.intPin()} (falling edge), or a 1 s polling thread if none is wired.
     *
     * @param callback receives the {@link #ALARM_RSOC} / {@link #ALARM_VOLTAGE} bitmask
     * @throws IOException on I²C error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to ALARMB on an explicit pin.
     *
     * @param callback receives the alarm bitmask
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
            }, "lc709203f-poll");
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
}
