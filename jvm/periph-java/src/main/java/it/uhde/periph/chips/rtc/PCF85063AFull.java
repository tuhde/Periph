package it.uhde.periph.chips.rtc;

import it.uhde.periph.connection.RegisterConnection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * PCF85063A full driver — extends {@link PCF85063AMinimal} with the
 * five-field alarm, the countdown timer, minute / half-minute interrupts,
 * programmable {@code CLKOUT}, offset calibration, oscillator-load
 * selection, oscillator-stop detection, clock stop/start, the free RAM byte,
 * software reset and the interrupt API.
 *
 * <p>The {@code INT} pin is open-drain and active-low; {@code CLKOUT} is a
 * separate pin. {@link #onInterrupt(IntConsumer)} uses
 * {@code connection.intPin()} if wired, otherwise a 5&nbsp;ms polling thread.
 */
public class PCF85063AFull extends PCF85063AMinimal {

    /** Interrupt source: all enabled alarm fields matched ({@code AF}). */
    public static final int SOURCE_ALARM       = 0x01;
    /** Interrupt source: countdown timer expired ({@code TF}). */
    public static final int SOURCE_TIMER       = 0x02;
    /** Interrupt source: minute interrupt (enable only; reported as {@link #SOURCE_TIMER}). */
    public static final int SOURCE_MINUTE      = 0x04;
    /** Interrupt source: half-minute interrupt (enable only; reported as {@link #SOURCE_TIMER}). */
    public static final int SOURCE_HALF_MINUTE = 0x08;
    /** Interrupt source: offset-correction pulse (enable only, no flag). */
    public static final int SOURCE_CORRECTION  = 0x10;

    // Control_2 (0x01) bits.
    private static final int C2_AIE   = 0x80;
    private static final int C2_AF    = 0x40;
    private static final int C2_MI    = 0x20;
    private static final int C2_HMI   = 0x10;
    private static final int C2_TF    = 0x08;
    private static final int C2_COF   = 0x07;
    private static final int C2_FLAGS = 0x48; // AF|TF: write 0 clears, 1 keeps

    // Timer_mode (0x11) bits.
    private static final int TM_TCF_MASK = 0x18;
    private static final int TM_TE       = 0x04;
    private static final int TM_TIE      = 0x02;
    private static final int TM_TI_TP    = 0x01;

    private static final int SECONDS_OS = 0x80;

    /** Timer source clock ({@code TCF} encoding). */
    public enum SourceClock {
        /** 4.096 kHz — 244 µs … 62.256 ms. */
        HZ_4096(0x00),
        /** 64 Hz — 15.625 ms … 3.984 s. */
        HZ_64(0x01),
        /** 1 Hz — 1 s … 255 s. */
        HZ_1(0x02),
        /** 1/60 Hz — 1 min … 4 h 15 min. */
        HZ_1_60(0x03);

        final int bits;

        SourceClock(int bits) { this.bits = bits; }
    }

    /** Offset correction mode. */
    public enum OffsetMode {
        /** Correction every two hours, 4.34 ppm per LSB (default). */
        NORMAL,
        /** Correction every four minutes, 4.069 ppm per LSB, higher power. */
        COURSE
    }

    /** Minute / half-minute interrupt selection. */
    public enum MinuteInterrupt { OFF, MINUTE, HALF_MINUTE }

    /**
     * Alarm configuration; a {@code null} field is disabled (ignored in the match).
     *
     * @param second  0–59, or {@code null}
     * @param minute  0–59, or {@code null}
     * @param hour    0–23, or {@code null}
     * @param day     day of month 1–31, or {@code null}
     * @param weekday 0 (Sunday) – 6 (Saturday), or {@code null}
     */
    public record Alarm(Integer second, Integer minute, Integer hour, Integer day, Integer weekday) {}

    /**
     * Offset calibration value.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode   correction mode
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
     * @param connection configured I²C connection bound to address 0x51
     * @throws IOException on bus error
     */
    public PCF85063AFull(RegisterConnection connection) throws IOException {
        super(connection);
    }

    private void updateControl1(int clearMask, int setBits) throws IOException {
        writeReg(REG_CONTROL_1, (readControl1() & ~clearMask) | setBits);
    }

    /**
     * Read-modify-write {@code Control_2}. {@code AF} and {@code TF} are
     * written 1 (unchanged) unless explicitly cleared through
     * {@code clearMask} (AND semantics: write 0 clears, 1 keeps).
     */
    private void updateControl2(int clearMask, int setBits) throws IOException {
        int reg = readReg(REG_CONTROL_2);
        writeReg(REG_CONTROL_2, ((reg & ~clearMask) | setBits | (C2_FLAGS & ~clearMask)) & 0xFF);
    }

    private void updateTimerMode(int clearMask, int setBits) throws IOException {
        int reg = readReg(REG_TIMER_MODE);
        writeReg(REG_TIMER_MODE, ((reg & ~clearMask) | setBits) & 0x1F);
    }

    // -------------------------------------------------------------------------
    // Oscillator / clock control
    // -------------------------------------------------------------------------

    /**
     * @return the {@code OS} flag (bit 7 of {@code Seconds}) — the time may be invalid; cleared by {@link #setDatetime} or {@link #clearOscillatorStop()}
     * @throws IOException on bus error
     */
    public boolean oscillatorStopped() throws IOException {
        return (readReg(REG_SECONDS) & SECONDS_OS) != 0;
    }

    /**
     * Clear {@code OS}, keeping the current seconds value. If
     * {@link #oscillatorStopped()} is still true afterwards the oscillator
     * is not running.
     *
     * @throws IOException on bus error
     */
    public void clearOscillatorStop() throws IOException {
        writeReg(REG_SECONDS, readReg(REG_SECONDS) & 0x7F);
    }

    /**
     * Freeze the clock ({@code STOP}=1); the divider chain is held in reset.
     *
     * @throws IOException on bus error
     */
    public void stopClock() throws IOException {
        updateControl1(0, C1_STOP);
    }

    /**
     * Release the clock ({@code STOP}=0).
     *
     * @throws IOException on bus error
     */
    public void startClock() throws IOException {
        updateControl1(C1_STOP, 0);
    }

    // -------------------------------------------------------------------------
    // Alarm
    // -------------------------------------------------------------------------

    /**
     * Decode the alarm registers (0x0B–0x0F).
     *
     * @return the alarm; disabled fields are {@code null}
     * @throws IOException on bus error
     */
    public Alarm getAlarm() throws IOException {
        byte[] raw = readBurst(REG_SECOND_ALARM, 5);
        return new Alarm(
                (raw[0] & 0x80) != 0 ? null : bcdToInt(raw[0] & 0x7F),
                (raw[1] & 0x80) != 0 ? null : bcdToInt(raw[1] & 0x7F),
                (raw[2] & 0x80) != 0 ? null : bcdToInt(raw[2] & 0x3F),
                (raw[3] & 0x80) != 0 ? null : bcdToInt(raw[3] & 0x3F),
                (raw[4] & 0x80) != 0 ? null : raw[4] & 0x07);
    }

    /**
     * Write the alarm registers (0x0B–0x0F) and clear {@code AF}.
     * {@code null} fields are disabled; the alarm fires when every enabled
     * field matches.
     *
     * @param alarm the alarm configuration
     * @throws IOException on bus error
     */
    public void setAlarm(Alarm alarm) throws IOException {
        writeBurst(REG_SECOND_ALARM,
                alarm.second() == null ? 0x80 : intToBcd(alarm.second()) & 0x7F,
                alarm.minute() == null ? 0x80 : intToBcd(alarm.minute()) & 0x7F,
                alarm.hour() == null ? 0x80 : intToBcd(alarm.hour()) & 0x3F,
                alarm.day() == null ? 0x80 : intToBcd(alarm.day()) & 0x3F,
                alarm.weekday() == null ? 0x80 : alarm.weekday() & 0x07);
        updateControl2(C2_AF, 0);
    }

    // -------------------------------------------------------------------------
    // Countdown timer
    // -------------------------------------------------------------------------

    /**
     * Configure and start the countdown timer. The timer is disabled first so
     * {@code Timer_value} can be changed safely. Does not set {@code TIE} —
     * use {@link #enableInterrupt(int)} with {@link #SOURCE_TIMER}.
     *
     * @param value       countdown value, 1–255
     * @param sourceClock timer tick frequency
     * @param pulsed      pulsed ({@code true}) or level ({@code false}, INT follows TF) interrupt
     * @throws IOException on bus error
     */
    public void configureTimer(int value, SourceClock sourceClock, boolean pulsed) throws IOException {
        updateTimerMode(TM_TE, 0);
        writeReg(REG_TIMER_VALUE, value);
        updateTimerMode(TM_TCF_MASK | TM_TI_TP, (sourceClock.bits << 3) | (pulsed ? TM_TI_TP : 0) | TM_TE);
    }

    /**
     * Configure and start the countdown timer with a level interrupt.
     *
     * @param value       countdown value, 1–255
     * @param sourceClock timer tick frequency
     * @throws IOException on bus error
     */
    public void configureTimer(int value, SourceClock sourceClock) throws IOException {
        configureTimer(value, sourceClock, false);
    }

    /**
     * Stop the timer ({@code TE}=0, {@code TIE}=0) and select the 1/60&nbsp;Hz
     * clock for power saving.
     *
     * @throws IOException on bus error
     */
    public void disableTimer() throws IOException {
        updateTimerMode(TM_TE | TM_TIE | TM_TCF_MASK, TM_TCF_MASK);
    }

    /**
     * Read the live countdown value; reads until two consecutive reads agree.
     *
     * @return the current counter value, 0–255 (not the loaded one)
     * @throws IOException on bus error
     */
    public int readTimer() throws IOException {
        int prev = readReg(REG_TIMER_VALUE);
        for (;;) {
            int cur = readReg(REG_TIMER_VALUE);
            if (cur == prev) return cur;
            prev = cur;
        }
    }

    /**
     * Select the minute / half-minute interrupt (requires offset mode
     * {@link OffsetMode#NORMAL}).
     *
     * @param mode off, once per minute, or every 30&nbsp;s
     * @throws IOException on bus error
     */
    public void setMinuteInterrupt(MinuteInterrupt mode) throws IOException {
        int bits = mode == MinuteInterrupt.MINUTE ? C2_MI : mode == MinuteInterrupt.HALF_MINUTE ? C2_HMI : 0;
        updateControl2(C2_MI | C2_HMI, bits);
    }

    // -------------------------------------------------------------------------
    // CLKOUT
    // -------------------------------------------------------------------------

    /**
     * Drive {@code CLKOUT} at one of 32768, 16384, 8192, 4096, 2048, 1024 or
     * 1&nbsp;Hz (any other value disables it).
     *
     * @param frequencyHz output frequency in Hz
     * @throws IOException on bus error
     */
    public void setClockOutput(int frequencyHz) throws IOException {
        int cof;
        switch (frequencyHz) {
            case 32768: cof = 0; break;
            case 16384: cof = 1; break;
            case 8192:  cof = 2; break;
            case 4096:  cof = 3; break;
            case 2048:  cof = 4; break;
            case 1024:  cof = 5; break;
            case 1:     cof = 6; break;
            default:    cof = 7; break;
        }
        updateControl2(C2_COF, cof);
    }

    /**
     * Disable {@code CLKOUT} ({@code COF}=111, pin low) to save power.
     *
     * @throws IOException on bus error
     */
    public void disableClockOutput() throws IOException {
        updateControl2(C2_COF, C2_COF);
    }

    // -------------------------------------------------------------------------
    // Offset / oscillator load / RAM
    // -------------------------------------------------------------------------

    /**
     * Read the clock-offset calibration register.
     *
     * @return the offset (−64…+63 LSB) and its mode
     * @throws IOException on bus error
     */
    public Offset getOffset() throws IOException {
        int raw = readReg(REG_OFFSET);
        int offset = raw & 0x7F;
        if ((offset & 0x40) != 0) offset -= 128;
        return new Offset(offset, (raw & 0x80) != 0 ? OffsetMode.COURSE : OffsetMode.NORMAL);
    }

    /**
     * Write the clock-offset calibration register.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @param mode   correction mode (4.34 or 4.069&nbsp;ppm per LSB)
     * @throws IOException on bus error
     */
    public void setOffset(int offset, OffsetMode mode) throws IOException {
        writeReg(REG_OFFSET, (mode == OffsetMode.COURSE ? 0x80 : 0) | (offset & 0x7F));
    }

    /**
     * Write the clock-offset calibration register in normal mode.
     *
     * @param offset two's-complement correction, −64…+63 LSB
     * @throws IOException on bus error
     */
    public void setOffset(int offset) throws IOException {
        setOffset(offset, OffsetMode.NORMAL);
    }

    /**
     * Select the oscillator load capacitance ({@code CAP_SEL}) — match the
     * crystal's specified load.
     *
     * @param picofarads 7 or 12.5
     * @throws IllegalArgumentException for any other value
     * @throws IOException on bus error
     */
    public void setOscillatorLoad(double picofarads) throws IOException {
        if (picofarads != 7.0 && picofarads != 12.5) throw new IllegalArgumentException("picofarads must be 7 or 12.5");
        updateControl1(C1_CAP_SEL, picofarads == 12.5 ? C1_CAP_SEL : 0);
    }

    /**
     * @return the free general-purpose RAM byte, 0–255
     * @throws IOException on bus error
     */
    public int readRam() throws IOException {
        return readReg(REG_RAM);
    }

    /**
     * Write the free general-purpose RAM byte.
     *
     * @param value 0–255
     * @throws IOException on bus error
     */
    public void writeRam(int value) throws IOException {
        writeReg(REG_RAM, value);
    }

    /**
     * Send the software-reset sequence (0x58 to {@code Control_1}). Resets
     * ALL registers including the time (2000-01-01 00:00:00, {@code OS}=1).
     *
     * @throws IOException on bus error
     */
    public void softwareReset() throws IOException {
        writeReg(REG_CONTROL_1, 0x58);
    }

    // -------------------------------------------------------------------------
    // Interrupt API
    // -------------------------------------------------------------------------

    /**
     * Subscribe to interrupts using {@code connection.intPin()} (or a
     * 5&nbsp;ms polling thread if none is wired).
     *
     * @param callback called with the status mask ({@link #SOURCE_ALARM} / {@link #SOURCE_TIMER})
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
        }, "pcf85063a-poll");
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
     * Read {@code Control_2}, clear the set {@code AF}/{@code TF} flags
     * (enable bits and {@code COF} preserved).
     *
     * @return the pre-clear status mask — {@link #SOURCE_ALARM} and/or {@link #SOURCE_TIMER} (the timer, minute and half-minute sources all report as {@code SOURCE_TIMER})
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int ctrl2 = readReg(REG_CONTROL_2);
        int status = 0;
        if ((ctrl2 & C2_AF) != 0) status |= SOURCE_ALARM;
        if ((ctrl2 & C2_TF) != 0) status |= SOURCE_TIMER;
        if (status != 0) {
            // Write 0 only to the flags seen set, 1 to the others, so a flag
            // that sets between the read and this write is not lost.
            writeReg(REG_CONTROL_2, (ctrl2 & ~C2_FLAGS & 0xFF) | (C2_FLAGS & ~ctrl2 & 0xFF));
        }
        return status;
    }

    /**
     * Enable one or more interrupt sources ({@code AIE}, {@code TIE},
     * {@code MI}, {@code HMI}, {@code CIE}).
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

    private void setInterruptEnables(int source, boolean enable) throws IOException {
        int bits2 = ((source & SOURCE_ALARM) != 0 ? C2_AIE : 0)
                | ((source & SOURCE_MINUTE) != 0 ? C2_MI : 0)
                | ((source & SOURCE_HALF_MINUTE) != 0 ? C2_HMI : 0);
        if (bits2 != 0) updateControl2(enable ? 0 : bits2, enable ? bits2 : 0);
        if ((source & SOURCE_TIMER) != 0) updateTimerMode(enable ? 0 : TM_TIE, enable ? TM_TIE : 0);
        if ((source & SOURCE_CORRECTION) != 0) updateControl1(enable ? 0 : C1_CIE, enable ? C1_CIE : 0);
    }
}
