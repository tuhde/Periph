package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * DS3231 full driver — extends {@link DS3231Minimal} with alarms, the
 * square-wave/32kHz outputs, oscillator control, forced temperature
 * conversion, aging-offset trim, and Level-2 selectable-source interrupts.
 *
 * <p>{@link #onInterrupt(IntConsumer)} subscribes to alarm matches delivered
 * on {@code INT/SQW}; delivery uses {@code connection.intPin()} (or an
 * explicit override) if wired, otherwise falls back to a 5&nbsp;ms polling
 * thread automatically. {@link #pollInterrupt()} reads and clears the alarm
 * flags. {@link #enableInterrupt(int)}/{@link #disableInterrupt(int)} select
 * which alarm(s) assert the pin.
 */
public class DS3231Full extends DS3231Minimal {

    /** Interrupt source: Alarm 1 matched. */
    public static final int SOURCE_ALARM1 = 0x01;
    /** Interrupt source: Alarm 2 matched. */
    public static final int SOURCE_ALARM2 = 0x02;

    // Alarm 1 match modes (mask/DY-DT combinations — Table 2 of the datasheet).
    public static final int ALARM1_MATCH_EVERY_SECOND                = 0;
    public static final int ALARM1_MATCH_SECONDS                       = 1;
    public static final int ALARM1_MATCH_MINUTES_SECONDS          = 2;
    public static final int ALARM1_MATCH_HOURS_MINUTES_SECONDS = 3;
    public static final int ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS = 4;
    public static final int ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS  = 5;

    // Alarm 2 match modes.
    public static final int ALARM2_MATCH_EVERY_MINUTE          = 0;
    public static final int ALARM2_MATCH_MINUTES                  = 1;
    public static final int ALARM2_MATCH_HOURS_MINUTES       = 2;
    public static final int ALARM2_MATCH_DATE_HOURS_MINUTES = 3;
    public static final int ALARM2_MATCH_DAY_HOURS_MINUTES   = 4;

    // Square-wave output rates (CONTROL RS2:RS1).
    public static final int SQUARE_WAVE_1_HZ       = 1;
    public static final int SQUARE_WAVE_1024_HZ  = 1024;
    public static final int SQUARE_WAVE_4096_HZ  = 4096;
    public static final int SQUARE_WAVE_8192_HZ  = 8192;

    private static final int MAX_TEMP_CONVERSION_WAIT_MS = 250;

    /** Callback invoked with the alarm-flag bitmask on any alarm match. */
    private volatile IntConsumer callback;

    /** INT-line pin currently armed, or {@code null} if polling. */
    private InputPin intPin;

    /** {@code true} while the polling-fallback thread is running. */
    private volatile boolean polling = false;

    /** Background daemon thread used only when no {@link InputPin} is available. */
    private Thread pollThread;

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls. A
     * method reference like {@code this::handleEdge} is not guaranteed by the
     * JLS to evaluate to the same object each time, so offEdge() could
     * silently fail to remove the handler onEdge() registered.
     */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * @param connection configured I²C connection bound to address 0x68
     * @throws IOException on bus error
     */
    public DS3231Full(Connection connection) throws IOException {
        super(connection);
    }

    // -------------------------------------------------------------------------
    // Alarms
    // -------------------------------------------------------------------------

    /**
     * Alarm 1 configuration.
     *
     * @param second      0–59 (ignored unless {@code matchMode} is one of the
     *                    "…seconds" modes)
     * @param minute      0–59
     * @param hour        0–23
     * @param dayOrDate   day-of-week (1–7) or day-of-month (1–31), per {@code isDayOfWeek}
     * @param isDayOfWeek {@code true} = {@code dayOrDate} is a day-of-week match
     * @param matchMode   one of the {@code ALARM1_MATCH_*} constants
     */
    public record Alarm1(int second, int minute, int hour, int dayOrDate, boolean isDayOfWeek, int matchMode) {}

    /** Alarm 2 configuration — same shape as {@link Alarm1} without seconds. */
    public record Alarm2(int minute, int hour, int dayOrDate, boolean isDayOfWeek, int matchMode) {}

    /**
     * Read Alarm 1's current configuration.
     *
     * @return the alarm configuration decoded from registers 0x07–0x0A
     * @throws IOException on bus error
     */
    public Alarm1 getAlarm1() throws IOException {
        byte[] raw = readBurst(REG_ALARM1_SECONDS, 4);
        int a1m1 = (raw[0] & 0x80) != 0 ? 1 : 0;
        int a1m2 = (raw[1] & 0x80) != 0 ? 1 : 0;
        int a1m3 = (raw[2] & 0x80) != 0 ? 1 : 0;
        int a1m4 = (raw[3] & 0x80) != 0 ? 1 : 0;
        boolean isDayOfWeek = (raw[3] & 0x40) != 0;
        int dayOrDate = bcdToInt(raw[3] & 0x3F);
        int matchMode = decodeAlarm1MatchMode(a1m4, a1m3, a1m2, a1m1, isDayOfWeek);
        return new Alarm1(
                bcdToInt(raw[0] & 0x7F),
                bcdToInt(raw[1] & 0x7F),
                bcdToInt(raw[2] & 0x3F),
                dayOrDate, isDayOfWeek, matchMode);
    }

    /**
     * Configure and store Alarm 1. Does not enable it — call
     * {@link #enableInterrupt(int)} with {@link #SOURCE_ALARM1} to arm it.
     *
     * @param second      0–59 (ignored unless {@code matchMode} is one of the
     *                    "…seconds" modes)
     * @param minute      0–59
     * @param hour        0–23
     * @param dayOrDate   day-of-week (1–7) or day-of-month (1–31), per {@code isDayOfWeek}
     * @param isDayOfWeek {@code true} = {@code dayOrDate} is a day-of-week match
     * @param matchMode   one of the {@code ALARM1_MATCH_*} constants
     * @throws IOException on bus error
     */
    public void setAlarm1(int second, int minute, int hour, int dayOrDate, boolean isDayOfWeek, int matchMode) throws IOException {
        int m1, m2, m3, m4;
        switch (matchMode) {
            case ALARM1_MATCH_EVERY_SECOND:                m1 = 1; m2 = 1; m3 = 1; m4 = 1; break;
            case ALARM1_MATCH_SECONDS:                       m1 = 0; m2 = 1; m3 = 1; m4 = 1; break;
            case ALARM1_MATCH_MINUTES_SECONDS:          m1 = 0; m2 = 0; m3 = 1; m4 = 1; break;
            case ALARM1_MATCH_HOURS_MINUTES_SECONDS: m1 = 0; m2 = 0; m3 = 0; m4 = 1; break;
            case ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS: m1 = 0; m2 = 0; m3 = 0; m4 = 0; break;
            case ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS:   m1 = 0; m2 = 0; m3 = 0; m4 = 0; break;
            default: return;
        }
        int dayDateByte = (m4 << 7) | ((isDayOfWeek ? 1 : 0) << 6) | (intToBcd(dayOrDate) & 0x3F);
        writeBurst(REG_ALARM1_SECONDS,
                (m1 << 7) | (intToBcd(second) & 0x7F),
                (m2 << 7) | (intToBcd(minute) & 0x7F),
                (m3 << 7) | (intToBcd(hour) & 0x3F),
                dayDateByte);
    }

    private static int decodeAlarm1MatchMode(int a1m4, int a1m3, int a1m2, int a1m1, boolean isDayOfWeek) {
        if (a1m4 == 1 && a1m3 == 1 && a1m2 == 1 && a1m1 == 1) return ALARM1_MATCH_EVERY_SECOND;
        if (a1m4 == 1 && a1m3 == 1 && a1m2 == 1) return ALARM1_MATCH_SECONDS;
        if (a1m4 == 1 && a1m3 == 1) return ALARM1_MATCH_MINUTES_SECONDS;
        if (a1m4 == 1) return ALARM1_MATCH_HOURS_MINUTES_SECONDS;
        return isDayOfWeek ? ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS : ALARM1_MATCH_DATE_HOURS_MINUTES_SECONDS;
    }

    /**
     * Read Alarm 2's current configuration.
     *
     * @return the alarm configuration decoded from registers 0x0B–0x0D
     * @throws IOException on bus error
     */
    public Alarm2 getAlarm2() throws IOException {
        byte[] raw = readBurst(REG_ALARM2_MINUTES, 3);
        int a2m2 = (raw[0] & 0x80) != 0 ? 1 : 0;
        int a2m3 = (raw[1] & 0x80) != 0 ? 1 : 0;
        int a2m4 = (raw[2] & 0x80) != 0 ? 1 : 0;
        boolean isDayOfWeek = (raw[2] & 0x40) != 0;
        int dayOrDate = bcdToInt(raw[2] & 0x3F);
        int matchMode = decodeAlarm2MatchMode(a2m4, a2m3, a2m2, isDayOfWeek);
        return new Alarm2(bcdToInt(raw[0] & 0x7F), bcdToInt(raw[1] & 0x3F), dayOrDate, isDayOfWeek, matchMode);
    }

    /**
     * Configure and store Alarm 2. Does not enable it — call
     * {@link #enableInterrupt(int)} with {@link #SOURCE_ALARM2} to arm it.
     *
     * @param minute      0–59
     * @param hour        0–23
     * @param dayOrDate   day-of-week (1–7) or day-of-month (1–31), per {@code isDayOfWeek}
     * @param isDayOfWeek {@code true} = {@code dayOrDate} is a day-of-week match
     * @param matchMode   one of the {@code ALARM2_MATCH_*} constants
     * @throws IOException on bus error
     */
    public void setAlarm2(int minute, int hour, int dayOrDate, boolean isDayOfWeek, int matchMode) throws IOException {
        int m2, m3, m4;
        switch (matchMode) {
            case ALARM2_MATCH_EVERY_MINUTE:          m2 = 1; m3 = 1; m4 = 1; break;
            case ALARM2_MATCH_MINUTES:                  m2 = 0; m3 = 1; m4 = 1; break;
            case ALARM2_MATCH_HOURS_MINUTES:       m2 = 0; m3 = 0; m4 = 1; break;
            case ALARM2_MATCH_DATE_HOURS_MINUTES: m2 = 0; m3 = 0; m4 = 0; break;
            case ALARM2_MATCH_DAY_HOURS_MINUTES:   m2 = 0; m3 = 0; m4 = 0; break;
            default: return;
        }
        int dayDateByte = (m4 << 7) | ((isDayOfWeek ? 1 : 0) << 6) | (intToBcd(dayOrDate) & 0x3F);
        writeBurst(REG_ALARM2_MINUTES,
                (m2 << 7) | (intToBcd(minute) & 0x7F),
                (m3 << 7) | (intToBcd(hour) & 0x3F),
                dayDateByte);
    }

    private static int decodeAlarm2MatchMode(int a2m4, int a2m3, int a2m2, boolean isDayOfWeek) {
        if (a2m4 == 1 && a2m3 == 1 && a2m2 == 1) return ALARM2_MATCH_EVERY_MINUTE;
        if (a2m4 == 1 && a2m3 == 1) return ALARM2_MATCH_MINUTES;
        if (a2m4 == 1) return ALARM2_MATCH_HOURS_MINUTES;
        return isDayOfWeek ? ALARM2_MATCH_DAY_HOURS_MINUTES : ALARM2_MATCH_DATE_HOURS_MINUTES;
    }

    // -------------------------------------------------------------------------
    // Square wave / 32kHz outputs
    // -------------------------------------------------------------------------

    /**
     * Enable the square-wave output on {@code INT/SQW}. Mutually exclusive
     * with alarm interrupts — they share the {@code INTCN} bit, so whichever
     * of this method or {@link #enableInterrupt(int)} is called last wins.
     *
     * @param rateHz        one of the {@code SQUARE_WAVE_*} constants
     * @param batteryBacked keep the output driven while running on {@code VBAT}
     * @throws IOException on bus error
     */
    public void enableSquareWave(int rateHz, boolean batteryBacked) throws IOException {
        int rs;
        switch (rateHz) {
            case SQUARE_WAVE_1_HZ:      rs = 0x00; break;
            case SQUARE_WAVE_1024_HZ: rs = CONTROL_RS1; break;
            case SQUARE_WAVE_4096_HZ: rs = CONTROL_RS2; break;
            case SQUARE_WAVE_8192_HZ: rs = CONTROL_RS2 | CONTROL_RS1; break;
            default: return;
        }
        int control = readReg(REG_CONTROL);
        control &= ~(CONTROL_INTCN | CONTROL_RS2 | CONTROL_RS1 | CONTROL_BBSQW);
        control |= rs;
        if (batteryBacked) control |= CONTROL_BBSQW;
        writeReg(REG_CONTROL, control);
    }

    /**
     * Disable the square wave, returning {@code INT/SQW} to interrupt mode.
     *
     * @throws IOException on bus error
     */
    public void disableSquareWave() throws IOException {
        int control = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, control | CONTROL_INTCN);
    }

    /**
     * @return {@code true} if the free-running 32kHz output pin is enabled
     * @throws IOException on bus error
     */
    public boolean is32kHzEnabled() throws IOException {
        return (readReg(REG_STATUS) & STATUS_EN32KHZ) != 0;
    }

    /** @throws IOException on bus error */
    public void enable32kHzOutput() throws IOException {
        int status = readReg(REG_STATUS);
        writeReg(REG_STATUS, status | STATUS_EN32KHZ);
    }

    /** @throws IOException on bus error */
    public void disable32kHzOutput() throws IOException {
        int status = readReg(REG_STATUS);
        writeReg(REG_STATUS, status & ~STATUS_EN32KHZ);
    }

    // -------------------------------------------------------------------------
    // Oscillator / temperature / aging
    // -------------------------------------------------------------------------

    /**
     * @return {@code true} if the Oscillator Stop Flag is set — timekeeping
     * data may be invalid since the last check
     * @throws IOException on bus error
     */
    public boolean oscillatorStopped() throws IOException {
        return (readReg(REG_STATUS) & STATUS_OSF) != 0;
    }

    /**
     * Write 0 to the Oscillator Stop Flag only, preserving the 32kHz-output
     * enable bit.
     *
     * @throws IOException on bus error
     */
    public void clearOscillatorStopped() throws IOException {
        int status = readReg(REG_STATUS);
        writeReg(REG_STATUS, status & ~STATUS_OSF);
    }

    /**
     * Clear {@code EOSC} — the oscillator keeps running on {@code VBAT} (the
     * power-on default).
     *
     * @throws IOException on bus error
     */
    public void enableBatteryOscillator() throws IOException {
        int control = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, control & ~CONTROL_EOSC);
    }

    /**
     * Set {@code EOSC} — the oscillator stops when switched to {@code VBAT},
     * saving battery current.
     *
     * @throws IOException on bus error
     */
    public void disableBatteryOscillator() throws IOException {
        int control = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, control | CONTROL_EOSC);
    }

    /**
     * Force an immediate temperature conversion and wait for it to complete
     * (max 200 ms per the datasheet; polled with a 5 ms margin).
     *
     * @throws IOException if the conversion does not complete within the
     *                      expected time, or on bus error
     */
    public void forceTemperatureConversion() throws IOException {
        int control = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, control | CONTROL_CONV);
        long deadline = System.currentTimeMillis() + MAX_TEMP_CONVERSION_WAIT_MS;
        while ((readReg(REG_STATUS) & STATUS_BSY) != 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("DS3231 temperature conversion timed out");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }

    /**
     * @return the raw signed 8-bit oscillator trim code from {@code AGING_OFFSET}
     * @throws IOException on bus error
     */
    public int getAgingOffset() throws IOException {
        return (byte) readReg(REG_AGING_OFFSET);
    }

    /**
     * @param offset raw signed 8-bit oscillator trim code, −128 to 127
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code offset} is out of range
     */
    public void setAgingOffset(int offset) throws IOException {
        if (offset < -128 || offset > 127) {
            throw new IllegalArgumentException("aging offset out of range: " + offset);
        }
        writeReg(REG_AGING_OFFSET, offset & 0xFF);
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 2 — selectable sources)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to alarm matches using {@code connection.intPin()} (or a
     * 5&nbsp;ms polling thread if none is wired). Also sets {@code INTCN}=1 so
     * {@code INT/SQW} carries alarm interrupts instead of the square wave.
     *
     * @param callback called with the alarm-flag bitmask ({@link #SOURCE_ALARM1}/{@link #SOURCE_ALARM2}) on any match
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to alarm matches, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the alarm-flag bitmask on any match
     * @param intPin   INT-line pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        this.callback = callback;
        int control = readReg(REG_CONTROL);
        writeReg(REG_CONTROL, control | CONTROL_INTCN);
        if (intPin != null) {
            this.intPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            startPolling();
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

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                handleEdge();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ds3231-poll");
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
            if (status == 0) return;
            if (callback != null) callback.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read {@code CONTROL_STATUS} and clear {@code A1F}/{@code A2F} (leaving
     * {@code OSF}/{@code EN32kHz}/{@code BSY} untouched).
     *
     * @return the pre-clear byte; mask with {@link #SOURCE_ALARM1}/{@link #SOURCE_ALARM2} to test each source
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int status = readReg(REG_STATUS);
        writeReg(REG_STATUS, status & ~(STATUS_A1F | STATUS_A2F));
        return status & (SOURCE_ALARM1 | SOURCE_ALARM2);
    }

    /**
     * Enable one alarm's interrupt output and route {@code INT/SQW} to
     * interrupt mode ({@code INTCN}=1).
     *
     * @param source {@link #SOURCE_ALARM1} or {@link #SOURCE_ALARM2}
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        int control = readReg(REG_CONTROL);
        control |= CONTROL_INTCN;
        if (source == SOURCE_ALARM1) control |= CONTROL_A1IE;
        else if (source == SOURCE_ALARM2) control |= CONTROL_A2IE;
        writeReg(REG_CONTROL, control);
    }

    /**
     * Disable one alarm's interrupt output.
     *
     * @param source {@link #SOURCE_ALARM1} or {@link #SOURCE_ALARM2}
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        int control = readReg(REG_CONTROL);
        if (source == SOURCE_ALARM1) control &= ~CONTROL_A1IE;
        else if (source == SOURCE_ALARM2) control &= ~CONTROL_A2IE;
        writeReg(REG_CONTROL, control);
    }
}
