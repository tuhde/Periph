package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.OptionalDouble;

/**
 * DRV2605 full interface — extends {@link DRV2605Minimal} with the waveform
 * sequencer, real-time playback, mode and library control, drive
 * configuration, auto-calibration, diagnostics, audio-to-vibe, status readout
 * and one-time OTP programming.
 */
public class DRV2605Full extends DRV2605Minimal {

    private static final double[] SAMPLE_TIME_S = {150e-6, 200e-6, 250e-6, 300e-6};
    private static final int[] PEAK_TIMES_MS = {10, 20, 30, 40};
    private static final int[] FILTERS_HZ = {100, 125, 150, 200};

    /**
     * One step of a waveform sequence.
     *
     * @param delay {@code false} = ROM effect, {@code true} = delay
     * @param value effect identifier 1–123, or delay in ms (0–1270, 10 ms steps)
     */
    public record SequenceStep(boolean delay, int value) {
        /** A ROM-effect step. */
        public static SequenceStep effect(int id) { return new SequenceStep(false, id); }

        /** A delay step of {@code ms} milliseconds. */
        public static SequenceStep delay(int ms) { return new SequenceStep(true, ms); }
    }

    /**
     * Decoded {@code STATUS} register (reading it clears the latched flags).
     *
     * @param diagFailed  last calibration or diagnostic run failed
     * @param fbTimeout   feedback controller timed out
     * @param overTemp    overtemperature shutdown latched
     * @param overCurrent overcurrent latched
     */
    public record Status(boolean diagFailed, boolean fbTimeout, boolean overTemp, boolean overCurrent) {}

    /**
     * Auto-calibration result or stored calibration.
     *
     * @param ok       calibration converged (always {@code true} from {@link #getCalibration()})
     * @param comp     {@code A_CAL_COMP}
     * @param bemf     {@code A_CAL_BEMF}
     * @param bemfGain {@code BEMF_GAIN} (0–3)
     */
    public record Calibration(boolean ok, int comp, int bemf, int bemfGain) {}

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     * @param lra        {@code false} for an ERM, {@code true} for an LRA
     * @throws IOException on bus error
     */
    public DRV2605Full(RegisterConnection connection, boolean lra) throws IOException {
        super(connection, lra);
    }

    /**
     * Construct the driver for an ERM.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     * @throws IOException on bus error
     */
    public DRV2605Full(RegisterConnection connection) throws IOException {
        super(connection, false);
    }

    /**
     * Play up to 8 effects and delays. A delay step is 0–1270 ms in 10 ms steps;
     * the list is terminated with 0 if shorter than 8.
     *
     * @param items 1–8 steps
     * @throws IllegalArgumentException on an empty or oversized list or an out-of-range step
     * @throws IOException              on bus error
     */
    public void playSequence(SequenceStep... items) throws IOException {
        if (items.length < 1 || items.length > 8) throw new IllegalArgumentException("items must hold 1-8 steps");
        byte[] data = new byte[items.length < 8 ? items.length + 1 : 8];
        for (int i = 0; i < items.length; i++) {
            SequenceStep s = items[i];
            if (s.delay()) {
                if (s.value() < 0 || s.value() > 1270) throw new IllegalArgumentException("wait must be 0-1270 ms");
                data[i] = (byte) (0x80 | ((s.value() + 5) / 10));
            } else {
                if (s.value() < 1 || s.value() > 123) throw new IllegalArgumentException("effect must be 1-123");
                data[i] = (byte) s.value();
            }
        }
        ensureInternal();
        writeRegs(REG_SEQ1, data);
        writeReg(REG_GO, 1);
    }

    /**
     * Poll {@code GO} until playback finishes.
     *
     * @param timeoutMs give-up time in ms
     * @return {@code true} if playback finished, {@code false} on timeout
     * @throws IOException on bus error
     */
    public boolean waitDone(long timeoutMs) throws IOException {
        long waited = 0;
        while (isPlaying()) {
            if (waited >= timeoutMs) return false;
            sleepMs(2);
            waited += 2;
        }
        return true;
    }

    /** Cancel playback; also leaves real-time-playback mode (output to zero). */
    @Override
    public void stop() throws IOException {
        super.stop();
        if (mode == MODE_RTP) {
            writeReg(REG_RTP_INPUT, 0);
            writeMode(MODE_INTERNAL_TRIGGER, standby);
        }
    }

    /**
     * Select the ROM library: 0 empty, 1–5 ERM libraries A–E, 6 LRA library.
     *
     * @param library 0–6
     * @throws IOException on bus error
     */
    public void setLibrary(int library) throws IOException {
        if (library < 0 || library > 6) throw new IllegalArgumentException("library must be 0-6");
        updateReg(REG_LIBRARY, 0x07, library);
    }

    /**
     * Set {@code MODE<2:0>} and leave standby.
     *
     * @param newMode 0–7
     * @throws IOException on bus error
     */
    public void setMode(int newMode) throws IOException {
        if (newMode < 0 || newMode > 7) throw new IllegalArgumentException("mode must be 0-7");
        writeMode(newMode, false);
    }

    /** Enter software standby (also stops playback). @throws IOException on bus error */
    public void standby() throws IOException {
        writeMode(mode, true);
    }

    /** Leave software standby. @throws IOException on bus error */
    public void wake() throws IOException {
        writeMode(mode, false);
    }

    /** {@code DEV_RESET} and wait for completion; re-create the driver afterwards. @throws IOException on bus error or timeout */
    public void reset() throws IOException {
        resetChip();
    }

    /** Put the outputs in true high-impedance state. @param enabled {@code true} to float the outputs @throws IOException on bus error */
    public void setHiZ(boolean enabled) throws IOException {
        updateReg(REG_LIBRARY, 0x10, enabled ? 0x10 : 0);
    }

    private boolean isLra() throws IOException {
        return (readReg(REG_FEEDBACK) & 0x80) != 0;
    }

    private boolean isOpenLoop(boolean lra) throws IOException {
        return (readReg(REG_CONTROL3) & (lra ? 0x01 : 0x20)) != 0;
    }

    /** Select ERM ({@code false}) or LRA ({@code true}); run {@code calibrate} afterwards. @param lra actuator type @throws IOException on bus error */
    public void setActuator(boolean lra) throws IOException {
        updateReg(REG_FEEDBACK, 0x80, lra ? 0x80 : 0);
    }

    /** Enable open-loop drive ({@code ERM_OPEN_LOOP} or {@code LRA_OPEN_LOOP} by actuator type). @param enabled open loop @throws IOException on bus error */
    public void setOpenLoop(boolean enabled) throws IOException {
        int mask = isLra() ? 0x01 : 0x20;
        updateReg(REG_CONTROL3, mask, enabled ? mask : 0);
    }

    /**
     * Select bidirectional ({@code true}) or unidirectional ({@code false}) input.
     *
     * @param enabled bidirectional
     * @throws IllegalStateException if unidirectional is requested while the loop is open
     * @throws IOException           on bus error
     */
    public void setBidirectional(boolean enabled) throws IOException {
        if (!enabled && isOpenLoop(isLra())) {
            throw new IllegalStateException("unidirectional input requires closed-loop operation");
        }
        updateReg(REG_CONTROL2, 0x80, enabled ? 0x80 : 0);
    }

    private void enterRtp() throws IOException {
        if (mode != MODE_RTP || standby) writeMode(MODE_RTP, false);
    }

    /**
     * Stream a signed real-time-playback amplitude. Enters RTP mode if not
     * already there; leave with {@link #stop()}, {@link #setMode(int)} or {@link #standby()}.
     *
     * @param level amplitude 0.0–1.0 (mapped to 0–127)
     * @throws IOException on bus error
     */
    public void rtpLevel(double level) throws IOException {
        if (!(level >= 0.0 && level <= 1.0)) throw new IllegalArgumentException("level must be 0.0-1.0");
        if (mode != MODE_RTP) updateReg(REG_CONTROL3, 0x08, 0);
        enterRtp();
        writeReg(REG_RTP_INPUT, (int) (level * 127 + 0.5));
    }

    /** Write a raw {@code RTP_INPUT} byte (0–255), interpreted per the RTP data format. @param value raw byte @throws IOException on bus error */
    public void rtpWrite(int value) throws IOException {
        if (value < 0 || value > 255) throw new IllegalArgumentException("value must be 0-255");
        enterRtp();
        writeReg(REG_RTP_INPUT, value);
    }

    /** Select unsigned ({@code true}) or signed ({@code false}) RTP data. @param unsigned data format @throws IOException on bus error */
    public void setRtpFormat(boolean unsigned) throws IOException {
        updateReg(REG_CONTROL3, 0x08, unsigned ? 0x08 : 0);
    }

    /** Select analog ({@code true}) or PWM ({@code false}) input for mode 3. @param analog input type @throws IOException on bus error */
    public void setInput(boolean analog) throws IOException {
        updateReg(REG_CONTROL3, 0x02, analog ? 0x02 : 0);
    }

    private int voltsToRatedCode(double volts, double lraFreqHz) throws IOException {
        if (!isLra()) return clampCode(volts / 21.33e-3);
        if (!(lraFreqHz > 0)) throw new IllegalArgumentException("lraFreqHz is required for an LRA");
        double tSample = SAMPLE_TIME_S[(readReg(REG_CONTROL2) >> 4) & 0x03];
        double factor = 1.0 - (4 * tSample + 300e-6) * lraFreqHz;
        if (factor <= 0) throw new IllegalArgumentException("lraFreqHz too high for the sample time");
        return clampCode(volts * Math.sqrt(factor) / 20.71e-3);
    }

    /**
     * Set {@code RATED_VOLTAGE} from volts (RMS for an LRA).
     *
     * @param volts     rated voltage in V
     * @param lraFreqHz LRA resonance in Hz; required (&gt; 0) for an LRA, ignored for an ERM
     * @throws IOException on bus error
     */
    public void setRatedVoltage(double volts, double lraFreqHz) throws IOException {
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(volts, lraFreqHz));
    }

    /** Set {@code RATED_VOLTAGE} from volts for an ERM. @param volts rated voltage in V @throws IOException on bus error */
    public void setRatedVoltage(double volts) throws IOException {
        setRatedVoltage(volts, 0.0);
    }

    /** Set {@code RATED_VOLTAGE} from a raw code. @param code 0–255 @throws IOException on bus error */
    public void setRatedVoltageCode(int code) throws IOException {
        if (code < 0 || code > 255) throw new IllegalArgumentException("code must be 0-255");
        writeReg(REG_RATED_VOLTAGE, code);
    }

    /**
     * Set {@code OD_CLAMP} from volts (RMS for LRA open loop).
     *
     * @param volts     clamp voltage in V
     * @param lraFreqHz LRA frequency in Hz; required (&gt; 0) for LRA open loop
     * @throws IllegalStateException for ERM closed loop (needs undocumented timing values; use {@link #setOverdriveClampCode(int)})
     * @throws IOException           on bus error
     */
    public void setOverdriveClamp(double volts, double lraFreqHz) throws IOException {
        boolean lra = isLra();
        boolean open = isOpenLoop(lra);
        int code;
        if (lra && open) {
            if (!(lraFreqHz > 0)) throw new IllegalArgumentException("lraFreqHz is required for LRA open loop");
            code = clampCode(volts / (21.33e-3 * Math.sqrt(1.0 - lraFreqHz * 800e-6)));
        } else if (!lra && !open) {
            throw new IllegalStateException("ERM closed-loop clamp in volts needs undocumented timing values; pass a code");
        } else {
            code = clampCode(volts / 21.96e-3);
        }
        writeReg(REG_OD_CLAMP, code);
    }

    /** Set {@code OD_CLAMP} from volts for ERM open loop or LRA closed loop. @param volts clamp voltage in V @throws IOException on bus error */
    public void setOverdriveClamp(double volts) throws IOException {
        setOverdriveClamp(volts, 0.0);
    }

    /** Set {@code OD_CLAMP} from a raw code. @param code 0–255 @throws IOException on bus error */
    public void setOverdriveClampCode(int code) throws IOException {
        if (code < 0 || code > 255) throw new IllegalArgumentException("code must be 0-255");
        writeReg(REG_OD_CLAMP, code);
    }

    private static int driveTimeCode(double ms, boolean lra) {
        int code = lra ? (int) ((ms - 0.5) / 0.1 + 0.5) : (int) ((ms - 1.0) / 0.2 + 0.5);
        return Math.max(0, Math.min(31, code));
    }

    /** Set {@code DRIVE_TIME} in ms (LRA: half the resonance period; ERM: back-EMF sample period). @param ms time in ms @throws IOException on bus error */
    public void setDriveTime(double ms) throws IOException {
        updateReg(REG_CONTROL1, 0x1F, driveTimeCode(ms, isLra()));
    }

    /**
     * Set the {@code FEEDBACK_CONTROL} fields (actuator-type bit preserved).
     *
     * @param brakeFactor {@code FB_BRAKE_FACTOR} 0–7
     * @param loopGain    {@code LOOP_GAIN} 0–3
     * @param bemfGain    {@code BEMF_GAIN} 0–3
     * @throws IOException on bus error
     */
    public void setFeedback(int brakeFactor, int loopGain, int bemfGain) throws IOException {
        if (brakeFactor < 0 || brakeFactor > 7 || loopGain < 0 || loopGain > 3 || bemfGain < 0 || bemfGain > 3) {
            throw new IllegalArgumentException("brakeFactor 0-7, loopGain 0-3, bemfGain 0-3");
        }
        updateReg(REG_FEEDBACK, 0x7F, (brakeFactor << 4) | (loopGain << 2) | bemfGain);
    }

    /**
     * Set the {@code CONTROL1}–{@code CONTROL3} fields not covered by other setters.
     *
     * @param startupBoost    higher loop gain during overdrive
     * @param brakeStabilizer reduce loop gain near the end of braking
     * @param sampleTime      LRA sample time 0–3
     * @param blankingTime    blanking time 0–3
     * @param idissTime       current-dissipation time 0–3
     * @param lraDriveTwice   update the LRA drive twice per cycle
     * @param supplyComp      keep supply compensation enabled
     * @param noiseGate       noise-gate threshold 0–3
     * @throws IOException on bus error
     */
    public void setAdvanced(boolean startupBoost, boolean brakeStabilizer, int sampleTime, int blankingTime,
                            int idissTime, boolean lraDriveTwice, boolean supplyComp, int noiseGate)
            throws IOException {
        for (int v : new int[]{sampleTime, blankingTime, idissTime, noiseGate}) {
            if (v < 0 || v > 3) throw new IllegalArgumentException("sampleTime, blankingTime, idissTime, noiseGate must be 0-3");
        }
        updateReg(REG_CONTROL1, 0x80, startupBoost ? 0x80 : 0);
        updateReg(REG_CONTROL2, 0x7F, (brakeStabilizer ? 0x40 : 0) | (sampleTime << 4) | (blankingTime << 2) | idissTime);
        updateReg(REG_CONTROL3, 0xD4, (noiseGate << 6) | (supplyComp ? 0 : 0x10) | (lraDriveTwice ? 0x04 : 0));
    }

    /** Set the {@code CONTROL1}–{@code CONTROL3} fields to the chip's reset values. @throws IOException on bus error */
    public void setAdvanced() throws IOException {
        setAdvanced(true, true, 3, 1, 1, false, true, 2);
    }

    /**
     * Set the {@code ODT}, {@code SPT}, {@code SNT}, {@code BRT} offsets in ms (5 ms steps, signed 8-bit).
     *
     * @param overdriveMs   overdrive time offset
     * @param sustainPosMs  positive sustain-time offset
     * @param sustainNegMs  negative sustain-time offset
     * @param brakeMs       brake-time offset
     * @throws IOException on bus error
     */
    public void setTimeOffsets(int overdriveMs, int sustainPosMs, int sustainNegMs, int brakeMs) throws IOException {
        int[] values = {overdriveMs, sustainPosMs, sustainNegMs, brakeMs};
        byte[] data = new byte[4];
        for (int i = 0; i < 4; i++) {
            int steps = values[i] >= 0 ? (values[i] + 2) / 5 : -((-values[i] + 2) / 5);
            if (steps < -128 || steps > 127) throw new IllegalArgumentException("time offset out of range (-640..635 ms)");
            data[i] = (byte) steps;
        }
        writeRegs(REG_ODT, data);
    }

    /**
     * Run the auto-calibration procedure. The standby state is restored afterwards.
     *
     * @param lra              {@code true} for an LRA
     * @param ratedVoltage     rated voltage in V (RMS for an LRA)
     * @param overdriveVoltage overdrive clamp in V (ERM: open-loop conversion V / 21.96 mV, since the
     *                         closed-loop correction needs undocumented timing values)
     * @param lraFreqHz        LRA resonance in Hz (required for an LRA); also sets {@code DRIVE_TIME} to half the period
     * @return the calibration result; {@code ok} is {@code true} if it converged
     * @throws IOException on bus error
     */
    public Calibration calibrate(boolean lra, double ratedVoltage, double overdriveVoltage, double lraFreqHz)
            throws IOException {
        if (lra && !(lraFreqHz > 0)) throw new IllegalArgumentException("lraFreqHz is required for an LRA");
        boolean wasStandby = standby;
        writeMode(MODE_AUTO_CALIBRATION, false);
        int bemfGain = readReg(REG_FEEDBACK) & 0x03;
        writeReg(REG_FEEDBACK, (lra ? 0x80 : 0) | (2 << 4) | (2 << 2) | bemfGain);
        updateReg(REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1); // SAMPLE_TIME 3, BLANKING 1, IDISS 1
        if (lra) updateReg(REG_CONTROL1, 0x1F, driveTimeCode(500.0 / lraFreqHz, true));
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(ratedVoltage, lraFreqHz));
        writeReg(REG_OD_CLAMP, clampCode(overdriveVoltage / 21.96e-3));
        updateReg(REG_CONTROL4, 0x31, 3 << 4); // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        writeReg(REG_GO, 1);
        boolean finished = waitDone(3000);
        boolean diagFailed = (readReg(REG_STATUS) & 0x08) != 0;
        Calibration c = getCalibration();
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
        return new Calibration(finished && !diagFailed, c.comp(), c.bemf(), c.bemfGain());
    }

    /** Run the auto-calibration procedure for an ERM. @see #calibrate(boolean, double, double, double) */
    public Calibration calibrate(boolean lra, double ratedVoltage, double overdriveVoltage) throws IOException {
        return calibrate(lra, ratedVoltage, overdriveVoltage, 0.0);
    }

    /** Read the stored calibration. @return {@code A_CAL_COMP}, {@code A_CAL_BEMF} and {@code BEMF_GAIN} ({@code ok} is always true) @throws IOException on bus error */
    public Calibration getCalibration() throws IOException {
        byte[] data = connection.read(REG_A_CAL_COMP, 2);
        return new Calibration(true, data[0] & 0xFF, data[1] & 0xFF, readReg(REG_FEEDBACK) & 0x03);
    }

    /**
     * Restore {@code A_CAL_COMP}, {@code A_CAL_BEMF} and {@code BEMF_GAIN} from an earlier calibration.
     *
     * @param comp     0–255
     * @param bemf     0–255
     * @param bemfGain 0–3
     * @throws IOException on bus error
     */
    public void setCalibration(int comp, int bemf, int bemfGain) throws IOException {
        if (comp < 0 || comp > 255 || bemf < 0 || bemf > 255 || bemfGain < 0 || bemfGain > 3) {
            throw new IllegalArgumentException("comp/bemf 0-255, bemfGain 0-3");
        }
        writeRegs(REG_A_CAL_COMP, new byte[]{(byte) comp, (byte) bemf});
        updateReg(REG_FEEDBACK, 0x03, bemfGain);
    }

    /** Run the actuator diagnostics. @return {@code true} if the actuator is OK ({@code DIAG_RESULT} = 0) @throws IOException on bus error */
    public boolean diagnose() throws IOException {
        boolean wasStandby = standby;
        writeMode(MODE_DIAGNOSTICS, false);
        writeReg(REG_GO, 1);
        boolean finished = waitDone(3000);
        boolean diagFailed = (readReg(REG_STATUS) & 0x08) != 0;
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby);
        return finished && !diagFailed;
    }

    /**
     * Configure the audio-to-vibe engine and enter audio-to-vibe mode.
     *
     * @param acCouple   enable the 0.9 V common-mode for AC-coupled input
     * @param peakTimeMs peak-detection time: 10, 20, 30 or 40 ms
     * @param filterHz   low-pass filter corner: 100, 125, 150 or 200 Hz
     * @param minInputV  minimum input level in V peak-to-peak (0–1.8)
     * @param maxInputV  maximum input level in V peak-to-peak (0–1.8)
     * @param minDrive   minimum output drive fraction (0.0–1.0)
     * @param maxDrive   maximum output drive fraction (0.0–1.0)
     * @throws IOException on bus error
     */
    public void setAudioToVibe(boolean acCouple, int peakTimeMs, int filterHz, double minInputV, double maxInputV,
                               double minDrive, double maxDrive) throws IOException {
        int peak = -1, filter = -1;
        for (int i = 0; i < 4; i++) {
            if (PEAK_TIMES_MS[i] == peakTimeMs) peak = i;
            if (FILTERS_HZ[i] == filterHz) filter = i;
        }
        if (peak < 0 || filter < 0) throw new IllegalArgumentException("peakTimeMs in 10/20/30/40, filterHz in 100/125/150/200");
        if (!(minInputV >= 0 && minInputV <= 1.8 && maxInputV >= 0 && maxInputV <= 1.8
                && minDrive >= 0 && minDrive <= 1 && maxDrive >= 0 && maxDrive <= 1)) {
            throw new IllegalArgumentException("input levels 0-1.8 V, drive 0.0-1.0");
        }
        writeRegs(REG_ATH_CTRL, new byte[]{
                (byte) ((peak << 2) | filter),
                (byte) clampCode(minInputV * 255 / 1.8), (byte) clampCode(maxInputV * 255 / 1.8),
                (byte) clampCode(minDrive * 255), (byte) clampCode(maxDrive * 255)});
        updateReg(REG_CONTROL1, 0x20, acCouple ? 0x20 : 0);
        updateReg(REG_CONTROL3, 0x02, 0x02);
        writeMode(MODE_AUDIO_TO_VIBE, false);
    }

    /** Configure audio-to-vibe with the default parameters and enter that mode. @throws IOException on bus error */
    public void setAudioToVibe() throws IOException {
        setAudioToVibe(true, 20, 125, 0.1, 1.8, 0.1, 1.0);
    }

    /** Read {@code STATUS} (clears the latched flags). @return the decoded status @throws IOException on bus error */
    public Status readStatus() throws IOException {
        int s = readReg(REG_STATUS);
        return new Status((s & 0x08) != 0, (s & 0x04) != 0, (s & 0x02) != 0, (s & 0x01) != 0);
    }

    /** {@code DEVICE_ID} (3 for the DRV2605); clears the latched status flags. @return the device id @throws IOException on bus error */
    public int readDeviceId() throws IOException {
        return readReg(REG_STATUS) >> 5;
    }

    /** VDD in V from {@code VBAT}; valid only during playback. @return supply voltage @throws IOException on bus error */
    public double readSupplyVoltage() throws IOException {
        return readReg(REG_VBAT) * 5.6 / 255.0;
    }

    /** LRA resonance frequency in Hz; valid only during LRA playback. @return the frequency, empty if {@code LRA_PERIOD} is 0 @throws IOException on bus error */
    public OptionalDouble readLraFrequency() throws IOException {
        int raw = readReg(REG_LRA_PERIOD);
        return raw == 0 ? OptionalDouble.empty() : OptionalDouble.of(1e6 / (raw * 98.46));
    }

    /**
     * Burn registers 0x16–0x1A into OTP. <b>Irreversible, one time only</b>; VDD must be 4.0–4.4 V.
     *
     * @param confirm must be {@code true}
     * @throws IllegalArgumentException if not confirmed
     * @throws IllegalStateException    if the OTP is already programmed
     * @throws IOException              on bus error or if programming does not complete
     */
    public void programOtp(boolean confirm) throws IOException {
        if (!confirm) throw new IllegalArgumentException("programOtp is irreversible; pass confirm=true");
        if (otpProgrammed()) throw new IllegalStateException("OTP already programmed");
        updateReg(REG_CONTROL4, 0x01, 0x01);
        for (int i = 0; i < 100; i++) {
            sleepMs(10);
            if (otpProgrammed()) return;
        }
        throw new IOException("OTP programming did not complete");
    }

    /** {@code OTP_STATUS}: whether the OTP has been programmed. @return {@code true} if programmed @throws IOException on bus error */
    public boolean otpProgrammed() throws IOException {
        return (readReg(REG_CONTROL4) & 0x04) != 0;
    }
}
