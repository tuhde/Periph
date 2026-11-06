package it.uhde.periph.chips.motor

import groovy.transform.CompileStatic
import groovy.transform.Immutable
import it.uhde.periph.connection.RegisterConnection

/**
 * DRV2605 full interface — extends {@link DRV2605Minimal} with the waveform
 * sequencer, real-time playback, mode and library control, drive
 * configuration, auto-calibration, diagnostics, audio-to-vibe, status readout
 * and one-time OTP programming.
 */
@CompileStatic
class DRV2605Full extends DRV2605Minimal {

    private static final double[] SAMPLE_TIME_S = [150e-6d, 200e-6d, 250e-6d, 300e-6d] as double[]
    private static final int[] PEAK_TIMES_MS = [10, 20, 30, 40] as int[]
    private static final int[] FILTERS_HZ = [100, 125, 150, 200] as int[]

    /** One step of a waveform sequence: a ROM effect (1–123) or a delay in ms (0–1270, 10 ms steps). */
    @Immutable
    static class SequenceStep {
        /** {@code false} = ROM effect, {@code true} = delay. */
        boolean delay
        /** Effect identifier 1–123, or delay in ms. */
        int value

        /** A ROM-effect step. */
        static SequenceStep effect(int id) { new SequenceStep(false, id) }

        /** A delay step of {@code ms} milliseconds. */
        static SequenceStep delay(int ms) { new SequenceStep(true, ms) }
    }

    /** Decoded {@code STATUS} register (reading it clears the latched flags): diagnostics failed, feedback timeout, overtemperature, overcurrent. */
    @Immutable
    static class Status {
        boolean diagFailed, fbTimeout, overTemp, overCurrent
    }

    /** Auto-calibration result or stored calibration: converged flag, {@code A_CAL_COMP}, {@code A_CAL_BEMF}, {@code BEMF_GAIN} (0–3). */
    @Immutable
    static class Calibration {
        boolean ok
        int comp, bemf, bemfGain
    }

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     * @param lra        {@code false} for an ERM, {@code true} for an LRA
     */
    public DRV2605Full(RegisterConnection connection, boolean lra) {
        super(connection, lra)
    }

    /**
     * Construct the driver for an ERM.
     *
     * @param connection configured I²C connection bound to the device (0x5A)
     */
    public DRV2605Full(RegisterConnection connection) {
        super(connection, false)
    }

    /**
     * Play up to 8 effects and delays. A delay step is 0–1270 ms in 10 ms steps;
     * the list is terminated with 0 if shorter than 8.
     *
     * @param items 1–8 steps
     * @throws IllegalArgumentException on an empty or oversized list or an out-of-range step
     */
    public void playSequence(SequenceStep... items) {
        if (items.length < 1 || items.length > 8) throw new IllegalArgumentException('items must hold 1-8 steps')
        byte[] data = new byte[items.length < 8 ? items.length + 1 : 8]
        for (int i = 0; i < items.length; i++) {
            SequenceStep s = items[i]
            if (s.delay) {
                if (s.value < 0 || s.value > 1270) throw new IllegalArgumentException('wait must be 0-1270 ms')
                data[i] = (byte) (0x80 | ((s.value + 5).intdiv(10)))
            } else {
                if (s.value < 1 || s.value > 123) throw new IllegalArgumentException('effect must be 1-123')
                data[i] = (byte) s.value
            }
        }
        ensureInternal()
        writeRegs(REG_SEQ1, data)
        writeReg(REG_GO, 1)
    }

    /**
     * Poll {@code GO} until playback finishes.
     *
     * @param timeoutMs give-up time in ms
     * @return {@code true} if playback finished, {@code false} on timeout
     */
    public boolean waitDone(long timeoutMs = 5000L) {
        long waited = 0
        while (isPlaying()) {
            if (waited >= timeoutMs) return false
            sleepMs(2)
            waited += 2
        }
        return true
    }

    /** Cancel playback; also leaves real-time-playback mode (output to zero). */
    @Override
    public void stop() {
        super.stop()
        if (currentMode == MODE_RTP) {
            writeReg(REG_RTP_INPUT, 0)
            writeMode(MODE_INTERNAL_TRIGGER, inStandby)
        }
    }

    /**
     * Select the ROM library: 0 empty, 1–5 ERM libraries A–E, 6 LRA library.
     *
     * @param library 0–6
     */
    public void setLibrary(int library) {
        if (library < 0 || library > 6) throw new IllegalArgumentException('library must be 0-6')
        updateReg(REG_LIBRARY, 0x07, library)
    }

    /**
     * Set {@code MODE<2:0>} and leave standby.
     *
     * @param newMode 0–7
     */
    public void setMode(int newMode) {
        if (newMode < 0 || newMode > 7) throw new IllegalArgumentException('mode must be 0-7')
        writeMode(newMode, false)
    }

    /** Enter software standby (also stops playback). */
    public void standby() {
        writeMode(currentMode, true)
    }

    /** Leave software standby. */
    public void wake() {
        writeMode(currentMode, false)
    }

    /** {@code DEV_RESET} and wait for completion; re-create the driver afterwards. */
    public void reset() {
        resetChip()
    }

    /**
     * Put the outputs in true high-impedance state.
     *
     * @param enabled {@code true} to float the outputs
     */
    public void setHiZ(boolean enabled) {
        updateReg(REG_LIBRARY, 0x10, enabled ? 0x10 : 0)
    }

    private boolean isLra() {
        return (readReg(REG_FEEDBACK) & 0x80) != 0
    }

    private boolean isOpenLoop(boolean lra) {
        return (readReg(REG_CONTROL3) & (lra ? 0x01 : 0x20)) != 0
    }

    /**
     * Select ERM ({@code false}) or LRA ({@code true}); run {@code calibrate} afterwards.
     *
     * @param lra actuator type
     */
    public void setActuator(boolean lra) {
        updateReg(REG_FEEDBACK, 0x80, lra ? 0x80 : 0)
    }

    /**
     * Enable open-loop drive ({@code ERM_OPEN_LOOP} or {@code LRA_OPEN_LOOP} by actuator type).
     *
     * @param enabled open loop
     */
    public void setOpenLoop(boolean enabled) {
        int mask = isLra() ? 0x01 : 0x20
        updateReg(REG_CONTROL3, mask, enabled ? mask : 0)
    }

    /**
     * Select bidirectional ({@code true}) or unidirectional ({@code false}) input.
     *
     * @param enabled bidirectional
     * @throws IllegalStateException if unidirectional is requested while the loop is open
     */
    public void setBidirectional(boolean enabled) {
        if (!enabled && isOpenLoop(isLra())) {
            throw new IllegalStateException('unidirectional input requires closed-loop operation')
        }
        updateReg(REG_CONTROL2, 0x80, enabled ? 0x80 : 0)
    }

    private void enterRtp() {
        if (currentMode != MODE_RTP || inStandby) writeMode(MODE_RTP, false)
    }

    /**
     * Stream a signed real-time-playback amplitude. Enters RTP mode if not
     * already there; leave with {@link #stop()}, {@link #setMode(int)} or {@link #standby()}.
     *
     * @param level amplitude 0.0–1.0 (mapped to 0–127)
     */
    public void rtpLevel(double level) {
        if (!(level >= 0.0d && level <= 1.0d)) throw new IllegalArgumentException('level must be 0.0-1.0')
        if (currentMode != MODE_RTP) updateReg(REG_CONTROL3, 0x08, 0)
        enterRtp()
        writeReg(REG_RTP_INPUT, (int) (level * 127 + 0.5d))
    }

    /**
     * Write a raw {@code RTP_INPUT} byte, interpreted per the RTP data format.
     *
     * @param value 0–255
     */
    public void rtpWrite(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException('value must be 0-255')
        enterRtp()
        writeReg(REG_RTP_INPUT, value)
    }

    /**
     * Select unsigned ({@code true}) or signed ({@code false}) RTP data.
     *
     * @param unsigned data format
     */
    public void setRtpFormat(boolean unsigned) {
        updateReg(REG_CONTROL3, 0x08, unsigned ? 0x08 : 0)
    }

    /**
     * Select analog ({@code true}) or PWM ({@code false}) input for mode 3.
     *
     * @param analog input type
     */
    public void setInput(boolean analog) {
        updateReg(REG_CONTROL3, 0x02, analog ? 0x02 : 0)
    }

    private int voltsToRatedCode(double volts, double lraFreqHz) {
        if (!isLra()) return clampCode(volts / 21.33e-3d)
        if (!(lraFreqHz > 0)) throw new IllegalArgumentException('lraFreqHz is required for an LRA')
        double tSample = SAMPLE_TIME_S[(readReg(REG_CONTROL2) >> 4) & 0x03]
        double factor = 1.0d - (4 * tSample + 300e-6d) * lraFreqHz
        if (factor <= 0) throw new IllegalArgumentException('lraFreqHz too high for the sample time')
        return clampCode(volts * Math.sqrt(factor) / 20.71e-3d)
    }

    /**
     * Set {@code RATED_VOLTAGE} from volts (RMS for an LRA).
     *
     * @param volts     rated voltage in V
     * @param lraFreqHz LRA resonance in Hz; required (&gt; 0) for an LRA, ignored for an ERM
     */
    public void setRatedVoltage(double volts, double lraFreqHz = 0.0d) {
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(volts, lraFreqHz))
    }

    /**
     * Set {@code RATED_VOLTAGE} from a raw code.
     *
     * @param code 0–255
     */
    public void setRatedVoltageCode(int code) {
        if (code < 0 || code > 255) throw new IllegalArgumentException('code must be 0-255')
        writeReg(REG_RATED_VOLTAGE, code)
    }

    /**
     * Set {@code OD_CLAMP} from volts (RMS for LRA open loop).
     *
     * @param volts     clamp voltage in V
     * @param lraFreqHz LRA frequency in Hz; required (&gt; 0) for LRA open loop
     * @throws IllegalStateException for ERM closed loop (needs undocumented timing values; use {@link #setOverdriveClampCode(int)})
     */
    public void setOverdriveClamp(double volts, double lraFreqHz = 0.0d) {
        boolean lra = isLra()
        boolean open = isOpenLoop(lra)
        int code
        if (lra && open) {
            if (!(lraFreqHz > 0)) throw new IllegalArgumentException('lraFreqHz is required for LRA open loop')
            code = clampCode(volts / (21.33e-3d * Math.sqrt(1.0d - lraFreqHz * 800e-6d)))
        } else if (!lra && !open) {
            throw new IllegalStateException('ERM closed-loop clamp in volts needs undocumented timing values; pass a code')
        } else {
            code = clampCode(volts / 21.96e-3d)
        }
        writeReg(REG_OD_CLAMP, code)
    }

    /**
     * Set {@code OD_CLAMP} from a raw code.
     *
     * @param code 0–255
     */
    public void setOverdriveClampCode(int code) {
        if (code < 0 || code > 255) throw new IllegalArgumentException('code must be 0-255')
        writeReg(REG_OD_CLAMP, code)
    }

    private static int driveTimeCode(double ms, boolean lra) {
        int code = lra ? (int) ((ms - 0.5d) / 0.1d + 0.5d) : (int) ((ms - 1.0d) / 0.2d + 0.5d)
        return Math.max(0, Math.min(31, code))
    }

    /**
     * Set {@code DRIVE_TIME} in ms (LRA: half the resonance period; ERM: back-EMF sample period).
     *
     * @param ms time in ms
     */
    public void setDriveTime(double ms) {
        updateReg(REG_CONTROL1, 0x1F, driveTimeCode(ms, isLra()))
    }

    /**
     * Set the {@code FEEDBACK_CONTROL} fields (actuator-type bit preserved).
     *
     * @param brakeFactor {@code FB_BRAKE_FACTOR} 0–7
     * @param loopGain    {@code LOOP_GAIN} 0–3
     * @param bemfGain    {@code BEMF_GAIN} 0–3
     */
    public void setFeedback(int brakeFactor = 3, int loopGain = 1, int bemfGain = 2) {
        if (brakeFactor < 0 || brakeFactor > 7 || loopGain < 0 || loopGain > 3 || bemfGain < 0 || bemfGain > 3) {
            throw new IllegalArgumentException('brakeFactor 0-7, loopGain 0-3, bemfGain 0-3')
        }
        updateReg(REG_FEEDBACK, 0x7F, (brakeFactor << 4) | (loopGain << 2) | bemfGain)
    }

    /**
     * Set the {@code CONTROL1}–{@code CONTROL3} fields not covered by other setters (defaults are the chip's reset values).
     *
     * @param startupBoost    higher loop gain during overdrive
     * @param brakeStabilizer reduce loop gain near the end of braking
     * @param sampleTime      LRA sample time 0–3
     * @param blankingTime    blanking time 0–3
     * @param idissTime       current-dissipation time 0–3
     * @param lraDriveTwice   update the LRA drive twice per cycle
     * @param supplyComp      keep supply compensation enabled
     * @param noiseGate       noise-gate threshold 0–3
     */
    public void setAdvanced(boolean startupBoost = true, boolean brakeStabilizer = true, int sampleTime = 3,
                            int blankingTime = 1, int idissTime = 1, boolean lraDriveTwice = false,
                            boolean supplyComp = true, int noiseGate = 2) {
        for (int v : [sampleTime, blankingTime, idissTime, noiseGate] as int[]) {
            if (v < 0 || v > 3) throw new IllegalArgumentException('sampleTime, blankingTime, idissTime, noiseGate must be 0-3')
        }
        updateReg(REG_CONTROL1, 0x80, startupBoost ? 0x80 : 0)
        updateReg(REG_CONTROL2, 0x7F, (brakeStabilizer ? 0x40 : 0) | (sampleTime << 4) | (blankingTime << 2) | idissTime)
        updateReg(REG_CONTROL3, 0xD4, (noiseGate << 6) | (supplyComp ? 0 : 0x10) | (lraDriveTwice ? 0x04 : 0))
    }

    /**
     * Set the {@code ODT}, {@code SPT}, {@code SNT}, {@code BRT} offsets in ms (5 ms steps, signed 8-bit: -640..635 ms).
     *
     * @param overdriveMs  overdrive time offset
     * @param sustainPosMs positive sustain-time offset
     * @param sustainNegMs negative sustain-time offset
     * @param brakeMs      brake-time offset
     */
    public void setTimeOffsets(int overdriveMs = 0, int sustainPosMs = 0, int sustainNegMs = 0, int brakeMs = 0) {
        int[] values = [overdriveMs, sustainPosMs, sustainNegMs, brakeMs] as int[]
        byte[] data = new byte[4]
        for (int i = 0; i < 4; i++) {
            int steps = values[i] >= 0 ? (values[i] + 2).intdiv(5) : -((-values[i] + 2).intdiv(5))
            if (steps < -128 || steps > 127) throw new IllegalArgumentException('time offset out of range (-640..635 ms)')
            data[i] = (byte) steps
        }
        writeRegs(REG_ODT, data)
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
     */
    public Calibration calibrate(boolean lra, double ratedVoltage, double overdriveVoltage, double lraFreqHz = 0.0d) {
        if (lra && !(lraFreqHz > 0)) throw new IllegalArgumentException('lraFreqHz is required for an LRA')
        boolean wasStandby = inStandby
        writeMode(MODE_AUTO_CALIBRATION, false)
        int bemfGain = readReg(REG_FEEDBACK) & 0x03
        writeReg(REG_FEEDBACK, (lra ? 0x80 : 0) | (2 << 4) | (2 << 2) | bemfGain)
        updateReg(REG_CONTROL2, 0x3F, (3 << 4) | (1 << 2) | 1) // SAMPLE_TIME 3, BLANKING 1, IDISS 1
        if (lra) updateReg(REG_CONTROL1, 0x1F, driveTimeCode(500.0d / lraFreqHz, true))
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(ratedVoltage, lraFreqHz))
        writeReg(REG_OD_CLAMP, clampCode(overdriveVoltage / 21.96e-3d))
        updateReg(REG_CONTROL4, 0x31, 3 << 4) // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        writeReg(REG_GO, 1)
        boolean finished = waitDone(3000L)
        boolean diagFailed = (readReg(REG_STATUS) & 0x08) != 0
        Calibration c = getCalibration()
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby)
        return new Calibration(finished && !diagFailed, c.comp, c.bemf, c.bemfGain)
    }

    /**
     * Read the stored calibration.
     *
     * @return {@code A_CAL_COMP}, {@code A_CAL_BEMF} and {@code BEMF_GAIN} ({@code ok} is always true)
     */
    public Calibration getCalibration() {
        byte[] data = connection.read(REG_A_CAL_COMP, 2)
        return new Calibration(true, data[0] & 0xFF, data[1] & 0xFF, readReg(REG_FEEDBACK) & 0x03)
    }

    /**
     * Restore {@code A_CAL_COMP}, {@code A_CAL_BEMF} and {@code BEMF_GAIN} from an earlier calibration.
     *
     * @param comp     0–255
     * @param bemf     0–255
     * @param bemfGain 0–3
     */
    public void setCalibration(int comp, int bemf, int bemfGain) {
        if (comp < 0 || comp > 255 || bemf < 0 || bemf > 255 || bemfGain < 0 || bemfGain > 3) {
            throw new IllegalArgumentException('comp/bemf 0-255, bemfGain 0-3')
        }
        writeRegs(REG_A_CAL_COMP, new byte[]{(byte) comp, (byte) bemf})
        updateReg(REG_FEEDBACK, 0x03, bemfGain)
    }

    /**
     * Run the actuator diagnostics.
     *
     * @return {@code true} if the actuator is OK ({@code DIAG_RESULT} = 0)
     */
    public boolean diagnose() {
        boolean wasStandby = inStandby
        writeMode(MODE_DIAGNOSTICS, false)
        writeReg(REG_GO, 1)
        boolean finished = waitDone(3000L)
        boolean diagFailed = (readReg(REG_STATUS) & 0x08) != 0
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby)
        return finished && !diagFailed
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
     */
    public void setAudioToVibe(boolean acCouple = true, int peakTimeMs = 20, int filterHz = 125,
                               double minInputV = 0.1d, double maxInputV = 1.8d, double minDrive = 0.1d,
                               double maxDrive = 1.0d) {
        int peak = -1, filter = -1
        for (int i = 0; i < 4; i++) {
            if (PEAK_TIMES_MS[i] == peakTimeMs) peak = i
            if (FILTERS_HZ[i] == filterHz) filter = i
        }
        if (peak < 0 || filter < 0) throw new IllegalArgumentException('peakTimeMs in 10/20/30/40, filterHz in 100/125/150/200')
        if (!(minInputV >= 0 && minInputV <= 1.8d && maxInputV >= 0 && maxInputV <= 1.8d
                && minDrive >= 0 && minDrive <= 1 && maxDrive >= 0 && maxDrive <= 1)) {
            throw new IllegalArgumentException('input levels 0-1.8 V, drive 0.0-1.0')
        }
        writeRegs(REG_ATH_CTRL, new byte[]{
                (byte) ((peak << 2) | filter),
                (byte) clampCode(minInputV * 255 / 1.8d), (byte) clampCode(maxInputV * 255 / 1.8d),
                (byte) clampCode(minDrive * 255), (byte) clampCode(maxDrive * 255)})
        updateReg(REG_CONTROL1, 0x20, acCouple ? 0x20 : 0)
        updateReg(REG_CONTROL3, 0x02, 0x02)
        writeMode(MODE_AUDIO_TO_VIBE, false)
    }

    /**
     * Read {@code STATUS} (clears the latched flags).
     *
     * @return the decoded status
     */
    public Status readStatus() {
        int s = readReg(REG_STATUS)
        return new Status((s & 0x08) != 0, (s & 0x04) != 0, (s & 0x02) != 0, (s & 0x01) != 0)
    }

    /**
     * {@code DEVICE_ID} (3 for the DRV2605); clears the latched status flags.
     *
     * @return the device id
     */
    public int readDeviceId() {
        return readReg(REG_STATUS) >> 5
    }

    /**
     * VDD in V from {@code VBAT}; valid only during playback.
     *
     * @return supply voltage
     */
    public double readSupplyVoltage() {
        return readReg(REG_VBAT) * 5.6d / 255.0d
    }

    /**
     * LRA resonance frequency in Hz; valid only during LRA playback.
     *
     * @return the frequency, empty if {@code LRA_PERIOD} is 0
     */
    public OptionalDouble readLraFrequency() {
        int raw = readReg(REG_LRA_PERIOD)
        return raw == 0 ? OptionalDouble.empty() : OptionalDouble.of(1e6d / (raw * 98.46d))
    }

    /**
     * Burn registers 0x16–0x1A into OTP. <b>Irreversible, one time only</b>; VDD must be 4.0–4.4 V.
     *
     * @param confirm must be {@code true}
     * @throws IllegalArgumentException if not confirmed
     * @throws IllegalStateException    if the OTP is already programmed
     * @throws IOException              if programming does not complete
     */
    public void programOtp(boolean confirm = false) {
        if (!confirm) throw new IllegalArgumentException('programOtp is irreversible; pass confirm=true')
        if (otpProgrammed()) throw new IllegalStateException('OTP already programmed')
        updateReg(REG_CONTROL4, 0x01, 0x01)
        for (int i = 0; i < 100; i++) {
            sleepMs(10)
            if (otpProgrammed()) return
        }
        throw new IOException('OTP programming did not complete')
    }

    /**
     * {@code OTP_STATUS}: whether the OTP has been programmed.
     *
     * @return {@code true} if programmed
     */
    public boolean otpProgrammed() {
        return (readReg(REG_CONTROL4) & 0x04) != 0
    }
}
