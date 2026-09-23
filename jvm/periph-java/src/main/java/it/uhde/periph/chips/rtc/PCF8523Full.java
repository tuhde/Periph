package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * PCF8523 full driver — extends {@link PCF8523Minimal} with the alarm, Timer
 * A (countdown or watchdog), Timer B, programmable {@code CLKOUT}, offset
 * calibration, battery backup control/status, oscillator-stop detection,
 * software reset, and the Level-3 interrupt API.
 *
 * <p>{@code INT1} is shared with {@code CLKOUT}: interrupts only reach
 * {@code INT1} once {@code CLKOUT} is disabled ({@link #disableClockOutput()});
 * the driver never does that implicitly. Timer B additionally drives the
 * dedicated {@code INT2} pin. {@link #onInterrupt(IntConsumer)} uses
 * {@code connection.intPin()} if wired, otherwise a 5&nbsp;ms polling thread.
 */
public class PCF8523Full extends PCF8523Minimal {

    /** Interrupt source: second tick ({@code SF}). */
    public static final int SOURCE_SECOND         = 0x01;
    /** Interrupt source: Timer A timed out ({@code CTAF} or {@code WTAF}). */
    public static final int SOURCE_TIMER_A        = 0x02;
    /** Interrupt source: Timer B timed out ({@code CTBF}). */
    public static final int SOURCE_TIMER_B        = 0x04;
    /** Interrupt source: all enabled alarm fields matched ({@code AF}). */
    public static final int SOURCE_ALARM          = 0x08;
    /** Interrupt source: battery switch-over occurred ({@code BSF}). */
    public static final int SOURCE_BATTERY_SWITCH = 0x10;
    /** Interrupt source: battery low ({@code BLF}). */
    public static final int SOURCE_BATTERY_LOW    = 0x20;

    // CONTROL_2 (0x01) bits.
    private static final int C2_WTAF      = 0x80;
    private static final int C2_CTAF      = 0x40;
    private static final int C2_CTBF      = 0x20;
    private static final int C2_SF        = 0x10;
    private static final int C2_AF        = 0x08;
    private static final int C2_WTAIE     = 0x04;
    private static final int C2_CTAIE     = 0x02;
    private static final int C2_CTBIE     = 0x01;
    private static final int C2_CLEARABLE = 0x78; // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
    private static final int C2_ENABLES   = 0x07;

    // CONTROL_3 (0x02) bits.
    private static final int C3_PM_MASK = 0xE0;
    private static final int C3_BSF     = 0x08;
    private static final int C3_BLF     = 0x04;
    private static final int C3_BSIE    = 0x02;
    private static final int C3_BLIE    = 0x01;

    private static final int SECONDS_OS = 0x80;

    // TMR_CLKOUT_CTRL (0x0F) fields.
    private static final int TMR_TAM           = 0x80;
    private static final int TMR_TBM           = 0x40;
    private static final int TMR_COF_MASK      = 0x38;
    private static final int TMR_TAC_MASK      = 0x06;
    private static final int TMR_TAC_COUNTDOWN = 0x02;
    private static final int TMR_TAC_WATCHDOG  = 0x04;
    private static final int TMR_TBC           = 0x01;

    /** TBW[2:0] → low-pulse width in ms (datasheet Table 36; not uniformly spaced). */
    private static final double[] TBW_WIDTHS_MS = {46.875, 62.5, 78.125, 93.75, 125.0, 156.25, 187.5, 218.75};

    /** Timer A operating mode. */
    public enum TimerAMode { COUNTDOWN, WATCHDOG }

    /** Timer source clock ({@code TAQ}/{@code TBQ} encoding). */
    public enum SourceClock {
        /** 4096 Hz — 244 µs … 62.256 ms. */
        HZ_4096(0x00),
        /** 64 Hz — 15.625 ms … 3.984 s. */
        HZ_64(0x01),
        /** 1 Hz — 1 s … 255 s. */
        HZ_1(0x02),
        /** 1/60 Hz — 1 min … 255 min. */
        HZ_1_60(0x03),
        /** 1/3600 Hz — 1 h … 255 h. */
        HZ_1_3600(0x07);

        final int bits;

        SourceClock(int bits) { this.bits = bits; }
    }

    /** Offset correction interval. */
    public enum OffsetMode {
        /** Once every two hours, 4.34 ppm per LSB (default). */
        EVERY_TWO_HOURS,
        /** Once every minute, 4.069 ppm per LSB. */
        EVERY_MINUTE
    }

    /** Battery switch-over mode. */
    public enum BatteryMode {
        /** Switch when VDD &lt; VBAT and VDD &lt; 2.5 V. */
        STANDARD,
        /** Switch whenever VDD &lt; VBAT. */
        DIRECT,
        /** VDD only — tie VBAT to VDD. */
        DISABLED
    }

    /**
     * Alarm configuration; a {@code null} field is disabled (ignored in the match).
     *
     * @param minute  0–59, or {@code null}
     * @param hour    0–23, or {@code null}
     * @param day     day of month 1–31, or {@code null}
     * @param weekday 0 (Sunday) – 6 (Saturday), or {@code null}
     */
    public record Alarm(Integer minute, Integer hour, Integer day, Integer weekday) {}

    /**
     * Offset calibration value.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode   correction interval
     */
    public record Offset(int offset, OffsetMode mode) {}

    /** Callback invoked with the status mask on any interrupt. */
    private volatile IntConsumer callback;

    /** INT-line pin currently armed, or {@code null} if polling. */
    private InputPin intPin;

    /** {@code true} while the polling-fallback thread is running. */
    private volatile boolean polling = false;

    /** Background daemon thread used only when no {@link InputPin} is available. */
    private Thread pollThread;

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls so
     * offEdge() removes exactly the handler onEdge() registered.
     */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * @param connection configured I²C connection bound to address 0x68
     * @throws IOException on bus error
     */
    public PCF8523Full(Connection connection) throws IOException {
        super(connection);
    }

    private void updateTmrClkout(int clearMask, int setBits) throws IOException {
        int reg = readReg(REG_TMR_CLKOUT_CTRL);
        writeReg(REG_TMR_CLKOUT_CTRL, (reg & ~clearMask) | setBits);
    }

    /** Write CONTROL_3 with BSF=1 so the flag is left unchanged. */
    private void writeControl3(int value) throws IOException {
        writeReg(REG_CONTROL_3, (value & (C3_PM_MASK | C3_BSIE | C3_BLIE)) | C3_BSF);
    }

    // -------------------------------------------------------------------------
    // Alarm
    // -------------------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0A–0x0D).
     *
     * @return the alarm; disabled fields are {@code null}
     * @throws IOException on bus error
     */
    public Alarm getAlarm() throws IOException {
        byte[] raw = readBurst(REG_MINUTE_ALARM, 4);
        return new Alarm(
                (raw[0] & 0x80) != 0 ? null : bcdToInt(raw[0] & 0x7F),
                (raw[1] & 0x80) != 0 ? null : bcdToInt(raw[1] & 0x3F),
                (raw[2] & 0x80) != 0 ? null : bcdToInt(raw[2] & 0x3F),
                (raw[3] & 0x80) != 0 ? null : raw[3] & 0x07);
    }

    /**
     * Write the alarm registers (0x0A–0x0D). {@code null} fields are disabled;
     * the alarm fires when every enabled field matches.
     *
     * @param alarm the alarm configuration
     * @throws IOException on bus error
     */
    public void setAlarm(Alarm alarm) throws IOException {
        writeBurst(REG_MINUTE_ALARM,
                alarm.minute() == null ? 0x80 : intToBcd(alarm.minute()) & 0x7F,
                alarm.hour() == null ? 0x80 : intToBcd(alarm.hour()) & 0x3F,
                alarm.day() == null ? 0x80 : intToBcd(alarm.day()) & 0x3F,
                alarm.weekday() == null ? 0x80 : alarm.weekday() & 0x07);
    }

    // -------------------------------------------------------------------------
    // Timers
    // -------------------------------------------------------------------------

    /**
     * Configure and start Timer A.
     *
     * @param mode        countdown or watchdog
     * @param value       countdown value, 0–255
     * @param sourceClock timer tick frequency
     * @param pulsed      pulsed ({@code true}) or permanently-active interrupt
     * @throws IOException on bus error
     */
    public void configureTimerA(TimerAMode mode, int value, SourceClock sourceClock, boolean pulsed) throws IOException {
        int tac = mode == TimerAMode.WATCHDOG ? TMR_TAC_WATCHDOG : TMR_TAC_COUNTDOWN;
        writeReg(REG_TMR_A_FREQ_CTRL, sourceClock.bits);
        writeReg(REG_TMR_A_REG, value);
        updateTmrClkout(TMR_TAM | TMR_TAC_MASK, (pulsed ? TMR_TAM : 0) | tac);
    }

    /**
     * Configure and start Timer A with a permanently-active interrupt.
     *
     * @param mode        countdown or watchdog
     * @param value       countdown value, 0–255
     * @param sourceClock timer tick frequency
     * @throws IOException on bus error
     */
    public void configureTimerA(TimerAMode mode, int value, SourceClock sourceClock) throws IOException {
        configureTimerA(mode, value, sourceClock, false);
    }

    /**
     * Stop Timer A ({@code TAC}=00).
     *
     * @throws IOException on bus error
     */
    public void disableTimerA() throws IOException {
        updateTmrClkout(TMR_TAC_MASK, 0);
    }

    /**
     * @return Timer A's live countdown value, 0–255 (not the loaded one)
     * @throws IOException on bus error
     */
    public int readTimerA() throws IOException {
        return readReg(REG_TMR_A_REG);
    }

    /**
     * Configure and start Timer B (also drives {@code INT2}).
     *
     * @param value        countdown value, 0–255
     * @param sourceClock  timer tick frequency
     * @param pulseWidthMs pulsed-mode low-pulse width in ms; the nearest of the eight hardware widths (46.875–218.75 ms) is used
     * @param pulsed       pulsed ({@code true}) or permanently-active interrupt
     * @throws IOException on bus error
     */
    public void configureTimerB(int value, SourceClock sourceClock, double pulseWidthMs, boolean pulsed) throws IOException {
        int tbw = 0;
        for (int i = 1; i < TBW_WIDTHS_MS.length; i++) {
            if (Math.abs(TBW_WIDTHS_MS[i] - pulseWidthMs) < Math.abs(TBW_WIDTHS_MS[tbw] - pulseWidthMs)) tbw = i;
        }
        writeReg(REG_TMR_B_FREQ_CTRL, (tbw << 4) | sourceClock.bits);
        writeReg(REG_TMR_B_REG, value);
        updateTmrClkout(TMR_TBM | TMR_TBC, (pulsed ? TMR_TBM : 0) | TMR_TBC);
    }

    /**
     * Configure and start Timer B with the default 46.875 ms pulse width and a
     * permanently-active interrupt.
     *
     * @param value       countdown value, 0–255
     * @param sourceClock timer tick frequency
     * @throws IOException on bus error
     */
    public void configureTimerB(int value, SourceClock sourceClock) throws IOException {
        configureTimerB(value, sourceClock, 46.875, false);
    }

    /**
     * Stop Timer B ({@code TBC}=0).
     *
     * @throws IOException on bus error
     */
    public void disableTimerB() throws IOException {
        updateTmrClkout(TMR_TBC, 0);
    }

    /**
     * @return Timer B's live countdown value, 0–255 (not the loaded one)
     * @throws IOException on bus error
     */
    public int readTimerB() throws IOException {
        return readReg(REG_TMR_B_REG);
    }

    // -------------------------------------------------------------------------
    // CLKOUT
    // -------------------------------------------------------------------------

    /**
     * Drive {@code CLKOUT} on the shared {@code INT1}/{@code CLKOUT} pin.
     *
     * @param frequencyHz one of 32768, 16384, 8192, 4096, 1024, 32, 1 Hz (other values disable CLKOUT)
     * @throws IOException on bus error
     */
    public void setClockOutput(int frequencyHz) throws IOException {
        int cof = switch (frequencyHz) {
            case 32768 -> 0;
            case 16384 -> 1;
            case 8192 -> 2;
            case 4096 -> 3;
            case 1024 -> 4;
            case 32 -> 5;
            case 1 -> 6;
            default -> 7;
        };
        updateTmrClkout(TMR_COF_MASK, cof << 3);
    }

    /**
     * Disable {@code CLKOUT} ({@code COF}=111), freeing {@code INT1} for interrupts.
     *
     * @throws IOException on bus error
     */
    public void disableClockOutput() throws IOException {
        updateTmrClkout(TMR_COF_MASK, TMR_COF_MASK);
    }

    // -------------------------------------------------------------------------
    // Offset
    // -------------------------------------------------------------------------

    /**
     * Read the offset calibration register.
     *
     * @return the correction (−64…+63 LSB) and its interval
     * @throws IOException on bus error
     */
    public Offset getOffset() throws IOException {
        int raw = readReg(REG_OFFSET);
        int offset = raw & 0x7F;
        if ((offset & 0x40) != 0) offset -= 128;
        return new Offset(offset, (raw & 0x80) != 0 ? OffsetMode.EVERY_MINUTE : OffsetMode.EVERY_TWO_HOURS);
    }

    /**
     * Write the offset calibration register.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode   correction interval (4.34 or 4.069 ppm per LSB)
     * @throws IOException on bus error
     */
    public void setOffset(int offset, OffsetMode mode) throws IOException {
        writeReg(REG_OFFSET, (mode == OffsetMode.EVERY_MINUTE ? 0x80 : 0) | (offset & 0x7F));
    }

    /**
     * Write the offset calibration register with a correction every two hours.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @throws IOException on bus error
     */
    public void setOffset(int offset) throws IOException {
        setOffset(offset, OffsetMode.EVERY_TWO_HOURS);
    }

    // -------------------------------------------------------------------------
    // Battery backup
    // -------------------------------------------------------------------------

    /**
     * Select the battery switch-over mode ({@code PM[2:0]}).
     *
     * @param mode         standard, direct or disabled switch-over
     * @param lowDetection enable battery-low detection
     * @throws IOException on bus error
     */
    public void configureBatteryBackup(BatteryMode mode, boolean lowDetection) throws IOException {
        int pm = switch (mode) {
            case STANDARD -> lowDetection ? 0x00 : 0x04;
            case DIRECT -> lowDetection ? 0x01 : 0x05;
            case DISABLED -> lowDetection ? 0x02 : 0x07;
        };
        int ctrl3 = readReg(REG_CONTROL_3);
        writeControl3((ctrl3 & ~C3_PM_MASK) | (pm << 5));
    }

    /**
     * Select the battery switch-over mode with battery-low detection enabled.
     *
     * @param mode standard, direct or disabled switch-over
     * @throws IOException on bus error
     */
    public void configureBatteryBackup(BatteryMode mode) throws IOException {
        configureBatteryBackup(mode, true);
    }

    /**
     * @return {@code BSF} — a switch-over to VBAT occurred since it was last cleared
     * @throws IOException on bus error
     */
    public boolean isBatterySwitchedOver() throws IOException {
        return (readReg(REG_CONTROL_3) & C3_BSF) != 0;
    }

    /**
     * Clear {@code BSF} only, leaving {@code PM} and the enable bits unchanged.
     *
     * @throws IOException on bus error
     */
    public void clearBatterySwitchover() throws IOException {
        int ctrl3 = readReg(REG_CONTROL_3);
        writeReg(REG_CONTROL_3, ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE));
    }

    /**
     * @return {@code BLF} (read-only) — VBAT is below the detection threshold
     * @throws IOException on bus error
     */
    public boolean isBatteryLow() throws IOException {
        return (readReg(REG_CONTROL_3) & C3_BLF) != 0;
    }

    /**
     * @return the {@code OS} flag (bit 7 of {@code SECONDS}) — the time may be invalid; cleared by {@link #setDatetime}
     * @throws IOException on bus error
     */
    public boolean oscillatorStopped() throws IOException {
        return (readReg(REG_SECONDS) & SECONDS_OS) != 0;
    }

    /**
     * Send the software-reset sequence (0x58 to {@code CONTROL_1}). Resets all
     * control/configuration registers to power-on defaults — including
     * {@code PM}=111 (battery backup disabled) — but keeps the
     * time/date/alarm/timer values.
     *
     * @throws IOException on bus error
     */
    public void softwareReset() throws IOException {
        writeReg(REG_CONTROL_1, 0x58);
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 3 — multiple INT lines)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to interrupts using {@code connection.intPin()} (or a
     * 5&nbsp;ms polling thread if none is wired). Call
     * {@link #disableClockOutput()} first if {@code INT1} still carries {@code CLKOUT}.
     *
     * @param callback called with the status mask (test with the {@code SOURCE_*} constants)
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to interrupts, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the status mask
     * @param intPin   INT-line pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback;
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
        }, "pcf8523-poll");
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
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read {@code CONTROL_2}/{@code CONTROL_3}, clear the set
     * {@code CTAF}/{@code CTBF}/{@code SF}/{@code AF}/{@code BSF} flags
     * ({@code WTAF}/{@code BLF} are read-only; enable bits untouched).
     *
     * @return the pre-clear status mask — test with the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        byte[] raw = readBurst(REG_CONTROL_2, 2);
        int ctrl2 = raw[0] & 0xFF;
        int ctrl3 = raw[1] & 0xFF;
        int status = 0;
        if ((ctrl2 & C2_SF) != 0) status |= SOURCE_SECOND;
        if ((ctrl2 & (C2_CTAF | C2_WTAF)) != 0) status |= SOURCE_TIMER_A;
        if ((ctrl2 & C2_CTBF) != 0) status |= SOURCE_TIMER_B;
        if ((ctrl2 & C2_AF) != 0) status |= SOURCE_ALARM;
        if ((ctrl3 & C3_BSF) != 0) status |= SOURCE_BATTERY_SWITCH;
        if ((ctrl3 & C3_BLF) != 0) status |= SOURCE_BATTERY_LOW;
        // Write 0 only to the flags seen set, 1 to the rest, so a flag that
        // sets between the read and this write is not lost.
        if ((ctrl2 & C2_CLEARABLE) != 0) {
            writeReg(REG_CONTROL_2, (C2_CLEARABLE & ~ctrl2) | (ctrl2 & C2_ENABLES));
        }
        if ((ctrl3 & C3_BSF) != 0) {
            writeReg(REG_CONTROL_3, ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE));
        }
        return status;
    }

    /**
     * Enable one or more interrupt sources. {@link #SOURCE_TIMER_A} sets
     * {@code WTAIE} or {@code CTAIE} depending on Timer A's configured mode —
     * call {@link #configureTimerA} first.
     *
     * @param source bitwise OR of {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        setInterruptEnables(source, true);
    }

    /**
     * Disable one or more interrupt sources.
     *
     * @param source bitwise OR of {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        setInterruptEnables(source, false);
    }

    private static int apply(int reg, int bits, boolean enable) {
        return enable ? reg | bits : reg & ~bits;
    }

    private void setInterruptEnables(int source, boolean enable) throws IOException {
        if ((source & (SOURCE_SECOND | SOURCE_ALARM)) != 0) {
            int bits = ((source & SOURCE_SECOND) != 0 ? C1_SIE : 0) | ((source & SOURCE_ALARM) != 0 ? C1_AIE : 0);
            writeReg(REG_CONTROL_1, apply(readControl1(), bits, enable));
        }
        if ((source & (SOURCE_TIMER_A | SOURCE_TIMER_B)) != 0) {
            int bits = 0;
            if ((source & SOURCE_TIMER_A) != 0) {
                if (enable) {
                    int tac = readReg(REG_TMR_CLKOUT_CTRL) & TMR_TAC_MASK;
                    bits |= tac == TMR_TAC_WATCHDOG ? C2_WTAIE : C2_CTAIE;
                } else {
                    bits |= C2_WTAIE | C2_CTAIE;
                }
            }
            if ((source & SOURCE_TIMER_B) != 0) bits |= C2_CTBIE;
            int enables = readReg(REG_CONTROL_2) & C2_ENABLES;
            // Re-supply the enable bits; write 1 to every clearable flag so
            // none is cleared by accident (AND semantics).
            writeReg(REG_CONTROL_2, C2_CLEARABLE | (apply(enables, bits, enable) & C2_ENABLES));
        }
        if ((source & (SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW)) != 0) {
            int bits = ((source & SOURCE_BATTERY_SWITCH) != 0 ? C3_BSIE : 0) | ((source & SOURCE_BATTERY_LOW) != 0 ? C3_BLIE : 0);
            writeControl3(apply(readReg(REG_CONTROL_3), bits, enable));
        }
    }
}
