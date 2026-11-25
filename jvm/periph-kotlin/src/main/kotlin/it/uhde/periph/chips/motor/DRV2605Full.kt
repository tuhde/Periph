package it.uhde.periph.chips.motor

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.sqrt

/**
 * DRV2605 full interface — extends [DRV2605Minimal] with the waveform
 * sequencer, real-time playback, mode and library control, drive
 * configuration, auto-calibration, diagnostics, audio-to-vibe, status readout
 * and one-time OTP programming.
 *
 * @param connection configured I²C connection bound to the device (0x5A)
 * @param lra `false` for an ERM, `true` for an LRA
 */
class DRV2605Full @JvmOverloads constructor(
    connection: RegisterConnection,
    lra: Boolean = false,
) : DRV2605Minimal(connection, lra) {

    /**
     * One step of a waveform sequence.
     *
     * @property delay `false` = ROM effect, `true` = delay
     * @property value effect identifier 1–123, or delay in ms (0–1270, 10 ms steps)
     */
    data class SequenceStep(val delay: Boolean, val value: Int) {
        companion object {
            /** A ROM-effect step. */
            fun effect(id: Int) = SequenceStep(false, id)

            /** A delay step of [ms] milliseconds. */
            fun delay(ms: Int) = SequenceStep(true, ms)
        }
    }

    /**
     * Decoded `STATUS` register (reading it clears the latched flags).
     *
     * @property diagFailed last calibration or diagnostic run failed
     * @property fbTimeout feedback controller timed out
     * @property overTemp overtemperature shutdown latched
     * @property overCurrent overcurrent latched
     */
    data class Status(val diagFailed: Boolean, val fbTimeout: Boolean, val overTemp: Boolean, val overCurrent: Boolean)

    /**
     * Auto-calibration result or stored calibration.
     *
     * @property ok calibration converged (always `true` from [getCalibration])
     * @property comp `A_CAL_COMP`
     * @property bemf `A_CAL_BEMF`
     * @property bemfGain `BEMF_GAIN` (0–3)
     */
    data class Calibration(val ok: Boolean, val comp: Int, val bemf: Int, val bemfGain: Int)

    private companion object {
        val SAMPLE_TIME_S = doubleArrayOf(150e-6, 200e-6, 250e-6, 300e-6)
        val PEAK_TIMES_MS = intArrayOf(10, 20, 30, 40)
        val FILTERS_HZ = intArrayOf(100, 125, 150, 200)

        fun driveTimeCode(ms: Double, lra: Boolean): Int {
            val code = if (lra) ((ms - 0.5) / 0.1 + 0.5).toInt() else ((ms - 1.0) / 0.2 + 0.5).toInt()
            return code.coerceIn(0, 31)
        }
    }

    /**
     * Play up to 8 effects and delays. A delay step is 0–1270 ms in 10 ms steps;
     * the list is terminated with 0 if shorter than 8.
     *
     * @param items 1–8 steps
     * @throws IllegalArgumentException on an empty or oversized list or an out-of-range step
     */
    fun playSequence(vararg items: SequenceStep) {
        require(items.size in 1..8) { "items must hold 1-8 steps" }
        val data = ByteArray(if (items.size < 8) items.size + 1 else 8)
        for ((i, s) in items.withIndex()) {
            if (s.delay) {
                require(s.value in 0..1270) { "wait must be 0-1270 ms" }
                data[i] = (0x80 or ((s.value + 5) / 10)).toByte()
            } else {
                require(s.value in 1..123) { "effect must be 1-123" }
                data[i] = s.value.toByte()
            }
        }
        ensureInternal()
        writeRegs(REG_SEQ1, data)
        writeReg(REG_GO, 1)
    }

    /**
     * Poll `GO` until playback finishes.
     *
     * @param timeoutMs give-up time in ms
     * @return `true` if playback finished, `false` on timeout
     */
    @JvmOverloads
    fun waitDone(timeoutMs: Long = 5000): Boolean {
        var waited = 0L
        while (isPlaying()) {
            if (waited >= timeoutMs) return false
            sleepMs(2)
            waited += 2
        }
        return true
    }

    /** Cancel playback; also leaves real-time-playback mode (output to zero). */
    override fun stop() {
        super.stop()
        if (currentMode == MODE_RTP) {
            writeReg(REG_RTP_INPUT, 0)
            writeMode(MODE_INTERNAL_TRIGGER, inStandby)
        }
    }

    /** Select the ROM library: 0 empty, 1–5 ERM libraries A–E, 6 LRA library. */
    fun setLibrary(library: Int) {
        require(library in 0..6) { "library must be 0-6" }
        updateReg(REG_LIBRARY, 0x07, library)
    }

    /** Set `MODE<2:0>` (0–7) and leave standby. */
    fun setMode(mode: Int) {
        require(mode in 0..7) { "mode must be 0-7" }
        writeMode(mode, false)
    }

    /** Enter software standby (also stops playback). */
    fun standby() {
        writeMode(currentMode, true)
    }

    /** Leave software standby. */
    fun wake() {
        writeMode(currentMode, false)
    }

    /** `DEV_RESET` and wait for completion; re-create the driver afterwards. */
    fun reset() {
        resetChip()
    }

    /** Put the outputs in true high-impedance state. */
    fun setHiZ(enabled: Boolean) {
        updateReg(REG_LIBRARY, 0x10, if (enabled) 0x10 else 0)
    }

    private fun isLra(): Boolean = readReg(REG_FEEDBACK) and 0x80 != 0

    private fun isOpenLoop(lra: Boolean): Boolean = readReg(REG_CONTROL3) and (if (lra) 0x01 else 0x20) != 0

    /** Select ERM (`false`) or LRA (`true`); run [calibrate] afterwards. */
    fun setActuator(lra: Boolean) {
        updateReg(REG_FEEDBACK, 0x80, if (lra) 0x80 else 0)
    }

    /** Enable open-loop drive (`ERM_OPEN_LOOP` or `LRA_OPEN_LOOP` by actuator type). */
    fun setOpenLoop(enabled: Boolean) {
        val mask = if (isLra()) 0x01 else 0x20
        updateReg(REG_CONTROL3, mask, if (enabled) mask else 0)
    }

    /**
     * Select bidirectional (`true`) or unidirectional (`false`) input.
     *
     * @throws IllegalStateException if unidirectional is requested while the loop is open
     */
    fun setBidirectional(enabled: Boolean) {
        check(enabled || !isOpenLoop(isLra())) { "unidirectional input requires closed-loop operation" }
        updateReg(REG_CONTROL2, 0x80, if (enabled) 0x80 else 0)
    }

    private fun enterRtp() {
        if (currentMode != MODE_RTP || inStandby) writeMode(MODE_RTP, false)
    }

    /**
     * Stream a signed real-time-playback amplitude. Enters RTP mode if not
     * already there; leave with [stop], [setMode] or [standby].
     *
     * @param level amplitude 0.0–1.0 (mapped to 0–127)
     */
    fun rtpLevel(level: Double) {
        require(level in 0.0..1.0) { "level must be 0.0-1.0" }
        if (currentMode != MODE_RTP) updateReg(REG_CONTROL3, 0x08, 0)
        enterRtp()
        writeReg(REG_RTP_INPUT, (level * 127 + 0.5).toInt())
    }

    /** Write a raw `RTP_INPUT` byte (0–255), interpreted per the RTP data format. */
    fun rtpWrite(value: Int) {
        require(value in 0..255) { "value must be 0-255" }
        enterRtp()
        writeReg(REG_RTP_INPUT, value)
    }

    /** Select unsigned (`true`) or signed (`false`) RTP data. */
    fun setRtpFormat(unsigned: Boolean) {
        updateReg(REG_CONTROL3, 0x08, if (unsigned) 0x08 else 0)
    }

    /** Select analog (`true`) or PWM (`false`) input for mode 3. */
    fun setInput(analog: Boolean) {
        updateReg(REG_CONTROL3, 0x02, if (analog) 0x02 else 0)
    }

    private fun voltsToRatedCode(volts: Double, lraFreqHz: Double): Int {
        if (!isLra()) return clampCode(volts / 21.33e-3)
        require(lraFreqHz > 0) { "lraFreqHz is required for an LRA" }
        val tSample = SAMPLE_TIME_S[(readReg(REG_CONTROL2) shr 4) and 0x03]
        val factor = 1.0 - (4 * tSample + 300e-6) * lraFreqHz
        require(factor > 0) { "lraFreqHz too high for the sample time" }
        return clampCode(volts * sqrt(factor) / 20.71e-3)
    }

    /**
     * Set `RATED_VOLTAGE` from volts (RMS for an LRA).
     *
     * @param volts rated voltage in V
     * @param lraFreqHz LRA resonance in Hz; required (> 0) for an LRA, ignored for an ERM
     */
    @JvmOverloads
    fun setRatedVoltage(volts: Double, lraFreqHz: Double = 0.0) {
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(volts, lraFreqHz))
    }

    /** Set `RATED_VOLTAGE` from a raw code (0–255). */
    fun setRatedVoltageCode(code: Int) {
        require(code in 0..255) { "code must be 0-255" }
        writeReg(REG_RATED_VOLTAGE, code)
    }

    /**
     * Set `OD_CLAMP` from volts (RMS for LRA open loop).
     *
     * @param volts clamp voltage in V
     * @param lraFreqHz LRA frequency in Hz; required (> 0) for LRA open loop
     * @throws IllegalStateException for ERM closed loop (needs undocumented timing values; use [setOverdriveClampCode])
     */
    @JvmOverloads
    fun setOverdriveClamp(volts: Double, lraFreqHz: Double = 0.0) {
        val lra = isLra()
        val open = isOpenLoop(lra)
        val code = when {
            lra && open -> {
                require(lraFreqHz > 0) { "lraFreqHz is required for LRA open loop" }
                clampCode(volts / (21.33e-3 * sqrt(1.0 - lraFreqHz * 800e-6)))
            }
            !lra && !open -> throw IllegalStateException(
                "ERM closed-loop clamp in volts needs undocumented timing values; pass a code")
            else -> clampCode(volts / 21.96e-3)
        }
        writeReg(REG_OD_CLAMP, code)
    }

    /** Set `OD_CLAMP` from a raw code (0–255). */
    fun setOverdriveClampCode(code: Int) {
        require(code in 0..255) { "code must be 0-255" }
        writeReg(REG_OD_CLAMP, code)
    }

    /** Set `DRIVE_TIME` in ms (LRA: half the resonance period; ERM: back-EMF sample period). */
    fun setDriveTime(ms: Double) {
        updateReg(REG_CONTROL1, 0x1F, driveTimeCode(ms, isLra()))
    }

    /**
     * Set the `FEEDBACK_CONTROL` fields (actuator-type bit preserved).
     *
     * @param brakeFactor `FB_BRAKE_FACTOR` 0–7
     * @param loopGain `LOOP_GAIN` 0–3
     * @param bemfGain `BEMF_GAIN` 0–3
     */
    @JvmOverloads
    fun setFeedback(brakeFactor: Int = 3, loopGain: Int = 1, bemfGain: Int = 2) {
        require(brakeFactor in 0..7 && loopGain in 0..3 && bemfGain in 0..3) {
            "brakeFactor 0-7, loopGain 0-3, bemfGain 0-3"
        }
        updateReg(REG_FEEDBACK, 0x7F, (brakeFactor shl 4) or (loopGain shl 2) or bemfGain)
    }

    /**
     * Set the `CONTROL1`–`CONTROL3` fields not covered by other setters (defaults are the chip's reset values).
     *
     * @param startupBoost higher loop gain during overdrive
     * @param brakeStabilizer reduce loop gain near the end of braking
     * @param sampleTime LRA sample time 0–3
     * @param blankingTime blanking time 0–3
     * @param idissTime current-dissipation time 0–3
     * @param lraDriveTwice update the LRA drive twice per cycle
     * @param supplyComp keep supply compensation enabled
     * @param noiseGate noise-gate threshold 0–3
     */
    @JvmOverloads
    fun setAdvanced(
        startupBoost: Boolean = true,
        brakeStabilizer: Boolean = true,
        sampleTime: Int = 3,
        blankingTime: Int = 1,
        idissTime: Int = 1,
        lraDriveTwice: Boolean = false,
        supplyComp: Boolean = true,
        noiseGate: Int = 2,
    ) {
        require(sampleTime in 0..3 && blankingTime in 0..3 && idissTime in 0..3 && noiseGate in 0..3) {
            "sampleTime, blankingTime, idissTime, noiseGate must be 0-3"
        }
        updateReg(REG_CONTROL1, 0x80, if (startupBoost) 0x80 else 0)
        updateReg(REG_CONTROL2, 0x7F,
            (if (brakeStabilizer) 0x40 else 0) or (sampleTime shl 4) or (blankingTime shl 2) or idissTime)
        updateReg(REG_CONTROL3, 0xD4,
            (noiseGate shl 6) or (if (supplyComp) 0 else 0x10) or (if (lraDriveTwice) 0x04 else 0))
    }

    /** Set the `ODT`, `SPT`, `SNT`, `BRT` offsets in ms (5 ms steps, signed 8-bit: -640..635 ms). */
    @JvmOverloads
    fun setTimeOffsets(overdriveMs: Int = 0, sustainPosMs: Int = 0, sustainNegMs: Int = 0, brakeMs: Int = 0) {
        val data = ByteArray(4)
        for ((i, ms) in intArrayOf(overdriveMs, sustainPosMs, sustainNegMs, brakeMs).withIndex()) {
            val steps = if (ms >= 0) (ms + 2) / 5 else -((-ms + 2) / 5)
            require(steps in -128..127) { "time offset out of range (-640..635 ms)" }
            data[i] = steps.toByte()
        }
        writeRegs(REG_ODT, data)
    }

    /**
     * Run the auto-calibration procedure. The standby state is restored afterwards.
     *
     * @param lra `true` for an LRA
     * @param ratedVoltage rated voltage in V (RMS for an LRA)
     * @param overdriveVoltage overdrive clamp in V (ERM: open-loop conversion V / 21.96 mV, since the
     *   closed-loop correction needs undocumented timing values)
     * @param lraFreqHz LRA resonance in Hz (required for an LRA); also sets `DRIVE_TIME` to half the period
     * @return the calibration result; `ok` is `true` if it converged
     */
    @JvmOverloads
    fun calibrate(lra: Boolean, ratedVoltage: Double, overdriveVoltage: Double, lraFreqHz: Double = 0.0): Calibration {
        require(!lra || lraFreqHz > 0) { "lraFreqHz is required for an LRA" }
        val wasStandby = inStandby
        writeMode(MODE_AUTO_CALIBRATION, false)
        val bemfGain = readReg(REG_FEEDBACK) and 0x03
        writeReg(REG_FEEDBACK, (if (lra) 0x80 else 0) or (2 shl 4) or (2 shl 2) or bemfGain)
        updateReg(REG_CONTROL2, 0x3F, (3 shl 4) or (1 shl 2) or 1) // SAMPLE_TIME 3, BLANKING 1, IDISS 1
        if (lra) updateReg(REG_CONTROL1, 0x1F, driveTimeCode(500.0 / lraFreqHz, true))
        writeReg(REG_RATED_VOLTAGE, voltsToRatedCode(ratedVoltage, lraFreqHz))
        writeReg(REG_OD_CLAMP, clampCode(overdriveVoltage / 21.96e-3))
        updateReg(REG_CONTROL4, 0x31, 3 shl 4) // AUTO_CAL_TIME = 3; OTP_PROGRAM stays 0
        writeReg(REG_GO, 1)
        val finished = waitDone(3000)
        val diagFailed = readReg(REG_STATUS) and 0x08 != 0
        val c = getCalibration()
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby)
        return Calibration(finished && !diagFailed, c.comp, c.bemf, c.bemfGain)
    }

    /** Read the stored `A_CAL_COMP`, `A_CAL_BEMF` and `BEMF_GAIN` (`ok` is always `true`). */
    fun getCalibration(): Calibration {
        val data = connection.read(REG_A_CAL_COMP, 2)
        return Calibration(true, data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, readReg(REG_FEEDBACK) and 0x03)
    }

    /** Restore `A_CAL_COMP` (0–255), `A_CAL_BEMF` (0–255) and `BEMF_GAIN` (0–3) from an earlier calibration. */
    fun setCalibration(comp: Int, bemf: Int, bemfGain: Int) {
        require(comp in 0..255 && bemf in 0..255 && bemfGain in 0..3) { "comp/bemf 0-255, bemfGain 0-3" }
        writeRegs(REG_A_CAL_COMP, byteArrayOf(comp.toByte(), bemf.toByte()))
        updateReg(REG_FEEDBACK, 0x03, bemfGain)
    }

    /** Run the actuator diagnostics; `true` if the actuator is OK (`DIAG_RESULT` = 0). */
    fun diagnose(): Boolean {
        val wasStandby = inStandby
        writeMode(MODE_DIAGNOSTICS, false)
        writeReg(REG_GO, 1)
        val finished = waitDone(3000)
        val diagFailed = readReg(REG_STATUS) and 0x08 != 0
        writeMode(MODE_INTERNAL_TRIGGER, wasStandby)
        return finished && !diagFailed
    }

    /**
     * Configure the audio-to-vibe engine and enter audio-to-vibe mode.
     *
     * @param acCouple enable the 0.9 V common-mode for AC-coupled input
     * @param peakTimeMs peak-detection time: 10, 20, 30 or 40 ms
     * @param filterHz low-pass filter corner: 100, 125, 150 or 200 Hz
     * @param minInputV minimum input level in V peak-to-peak (0–1.8)
     * @param maxInputV maximum input level in V peak-to-peak (0–1.8)
     * @param minDrive minimum output drive fraction (0.0–1.0)
     * @param maxDrive maximum output drive fraction (0.0–1.0)
     */
    @JvmOverloads
    fun setAudioToVibe(
        acCouple: Boolean = true,
        peakTimeMs: Int = 20,
        filterHz: Int = 125,
        minInputV: Double = 0.1,
        maxInputV: Double = 1.8,
        minDrive: Double = 0.1,
        maxDrive: Double = 1.0,
    ) {
        val peak = PEAK_TIMES_MS.indexOf(peakTimeMs)
        val filter = FILTERS_HZ.indexOf(filterHz)
        require(peak >= 0 && filter >= 0) { "peakTimeMs in 10/20/30/40, filterHz in 100/125/150/200" }
        require(minInputV in 0.0..1.8 && maxInputV in 0.0..1.8 && minDrive in 0.0..1.0 && maxDrive in 0.0..1.0) {
            "input levels 0-1.8 V, drive 0.0-1.0"
        }
        writeRegs(REG_ATH_CTRL, byteArrayOf(
            ((peak shl 2) or filter).toByte(),
            clampCode(minInputV * 255 / 1.8).toByte(), clampCode(maxInputV * 255 / 1.8).toByte(),
            clampCode(minDrive * 255).toByte(), clampCode(maxDrive * 255).toByte()))
        updateReg(REG_CONTROL1, 0x20, if (acCouple) 0x20 else 0)
        updateReg(REG_CONTROL3, 0x02, 0x02)
        writeMode(MODE_AUDIO_TO_VIBE, false)
    }

    /** Read `STATUS` (clears the latched flags). */
    fun readStatus(): Status {
        val s = readReg(REG_STATUS)
        return Status(s and 0x08 != 0, s and 0x04 != 0, s and 0x02 != 0, s and 0x01 != 0)
    }

    /** `DEVICE_ID` (3 for the DRV2605); clears the latched status flags. */
    fun readDeviceId(): Int = readReg(REG_STATUS) shr 5

    /** VDD in V from `VBAT`; valid only during playback. */
    fun readSupplyVoltage(): Double = readReg(REG_VBAT) * 5.6 / 255.0

    /** LRA resonance frequency in Hz; valid only during LRA playback. `null` if `LRA_PERIOD` is 0. */
    fun readLraFrequency(): Double? {
        val raw = readReg(REG_LRA_PERIOD)
        return if (raw == 0) null else 1e6 / (raw * 98.46)
    }

    /**
     * Burn registers 0x16–0x1A into OTP. **Irreversible, one time only**; VDD must be 4.0–4.4 V.
     *
     * @param confirm must be `true`
     * @throws IllegalArgumentException if not confirmed
     * @throws IllegalStateException if the OTP is already programmed
     * @throws IOException if programming does not complete
     */
    @JvmOverloads
    fun programOtp(confirm: Boolean = false) {
        require(confirm) { "programOtp is irreversible; pass confirm=true" }
        check(!otpProgrammed()) { "OTP already programmed" }
        updateReg(REG_CONTROL4, 0x01, 0x01)
        repeat(100) {
            sleepMs(10)
            if (otpProgrammed()) return
        }
        throw IOException("OTP programming did not complete")
    }

    /** `OTP_STATUS`: whether the OTP has been programmed. */
    fun otpProgrammed(): Boolean = readReg(REG_CONTROL4) and 0x04 != 0
}
