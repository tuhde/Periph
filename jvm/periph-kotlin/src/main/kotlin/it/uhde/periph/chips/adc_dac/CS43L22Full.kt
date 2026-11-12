package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CS43L22 stereo DAC — full interface.
 *
 * Adds per-channel volumes, output power selection, clocking and serial interface configuration,
 * tone control, the beep generator, the peak limiter, soft ramp / zero-cross, analog passthrough,
 * battery compensation, status reads and raw register access. Extends [CS43L22Minimal].
 *
 * @param connection I²C register connection bound to 0x4A / 0x4B
 * @param resPin optional output pin wired to the active-low RESET pin
 */
class CS43L22Full(connection: RegisterConnection, resPin: OutputPin? = null) :
    CS43L22Minimal(connection, resPin) {

    /**
     * Clocking settings (arguments of [setClocking]).
     *
     * @property auto auto-detect the speed mode from MCLK / LRCK (slave mode)
     * @property speed speed mode 0–3 (double, single, half, quarter)
     * @property group32k true for 8, 16 and 32 kHz sample rates
     * @property videoClk true if MCLK is 27 MHz
     * @property ratio internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136)
     * @property mclkDiv2 divide MCLK by 2
     */
    data class Clocking(
        val auto: Boolean = true,
        val speed: Int = 1,
        val group32k: Boolean = false,
        val videoClk: Boolean = false,
        val ratio: Int = 0,
        val mclkDiv2: Boolean = false,
    )

    /**
     * Decoded `STATUS` register (reading it clears the flags).
     *
     * @property clockError MCLK / LRCK ratio invalid (reads 1 right after the clocks start)
     * @property dspAOverflow DSP channel A overflow
     * @property dspBOverflow DSP channel B overflow
     * @property pcmAOverflow PCM channel A overflow
     * @property pcmBOverflow PCM channel B overflow
     */
    data class Status(
        val clockError: Boolean,
        val dspAOverflow: Boolean,
        val dspBOverflow: Boolean,
        val pcmAOverflow: Boolean,
        val pcmBOverflow: Boolean,
    )

    /**
     * Decoded `SPEAKER_STATUS` register.
     *
     * @property speakerAShort speaker A output shorted to ground
     * @property speakerBShort speaker B output shorted to ground
     * @property spkrHpPin level of the SPKR/HP_SW pin
     */
    data class SpeakerStatus(val speakerAShort: Boolean, val speakerBShort: Boolean, val spkrHpPin: Boolean)

    companion object {
        const val OUT_FOLLOW_LOW = 0
        const val OUT_FOLLOW_HIGH = 1
        const val OUT_ON = 2
        const val OUT_OFF = 3
        const val FMT_LJ = 0
        const val FMT_I2S = 1
        const val FMT_RJ = 2
        const val BEEP_OFF = 0
        const val BEEP_SINGLE = 1
        const val BEEP_MULTIPLE = 2
        const val BEEP_CONTINUOUS = 3
        const val SRC_NORMAL = 0
        const val SRC_MIX = 1
        const val SRC_SWAPPED = 3
        const val GAIN_0_3959 = 0
        const val GAIN_0_4571 = 1
        const val GAIN_0_5111 = 2
        const val GAIN_0_6047 = 3
        const val GAIN_0_7099 = 4
        const val GAIN_0_8399 = 5
        const val GAIN_1_000 = 6
        const val GAIN_1_143 = 7

        private val HP_GAINS = doubleArrayOf(0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430)
        private val LIMIT_DB = intArrayOf(0, -3, -6, -9, -12, -18, -24, -30)
        private val FS_ROUND = doubleArrayOf(8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0, 96000.0)
        private val FS_44K = doubleArrayOf(11025.0, 22050.0, 44100.0, 88200.0)
        private val FS_FRAC = doubleArrayOf(12e6 / 1088, 12e6 / 544, 12e6 / 272, 12e6 / 136)
        private val FS_27M = doubleArrayOf(8000.0, 12000.0, 16000.0, 24000.0, 32000.0, 48000.0)

        private fun isNear(v: Double, c: Double): Boolean = abs(v - c) <= c * 1e-4

        private fun nearAny(v: Double, list: DoubleArray, n: Int): Boolean {
            for (i in 0 until n) if (isNear(v, list[i])) return true
            return false
        }

        private fun nearestLimit(db: Int): Int {
            var best = 0
            var bestErr = abs(db - LIMIT_DB[0])
            for (i in 1 until 8) {
                val err = abs(db - LIMIT_DB[i])
                if (err < bestErr) {
                    best = i
                    bestErr = err
                }
            }
            return best
        }

        private fun documented(reg: Int): Boolean =
            reg == 0x00 || reg == 0x01 || reg == 0x02 || reg in 0x04..0x0A || reg in 0x0C..0x0F ||
                reg == 0x14 || reg == 0x15 || reg in 0x1A..0x29 || reg in 0x2E..0x32 || reg == 0x34 || reg == 0x47

        /**
         * Datasheet Table 1 clocking settings for an MCLK / sample-rate pair.
         *
         * @param mclkHz 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz, in Hz
         * @param fsHz sample rate in Hz (a rate listed for that MCLK in Table 1)
         * @return the arguments of [setClocking] with `auto` false
         * @throws IllegalArgumentException if the combination is not in the datasheet table
         */
        @JvmStatic
        fun clockingFor(mclkHz: Long, fsHz: Double): Clocking {
            val mclk = mclkHz.toDouble()
            var video = false
            var div2 = false
            var ratio = -1
            if (isNear(mclk, 12288000.0) || isNear(mclk, 18432000.0)) {
                if (nearAny(fsHz, FS_ROUND, 7)) ratio = 0
            } else if (isNear(mclk, 11289600.0)) {
                if (nearAny(fsHz, FS_44K, 4)) ratio = 0
            } else if (isNear(mclk, 16934400.0)) {
                if (nearAny(fsHz, FS_44K, 4)) ratio = 0
                else if (isNear(fsHz, 16934400.0 / 2112)) ratio = 2
            } else if (isNear(mclk, 12000000.0) || isNear(mclk, 24000000.0)) {
                div2 = mclk > 18000000.0
                if (nearAny(fsHz, FS_ROUND, 7)) ratio = 1
                else if (nearAny(fsHz, FS_FRAC, 4)) ratio = 3
            } else if (isNear(mclk, 27000000.0)) {
                video = true
                if (nearAny(fsHz, FS_27M, 6)) ratio = 1
                else if (nearAny(fsHz, FS_FRAC, 3)) ratio = 3
            }
            require(ratio >= 0) { "MCLK / sample-rate combination is not in the datasheet table" }
            val group32k = isNear(fsHz, 8000.0) || isNear(fsHz, 16000.0) || isNear(fsHz, 32000.0)
            val speed = if (fsHz <= 12100) 3 else if (fsHz <= 24100) 2 else if (fsHz <= 48100) 1 else 0
            return Clocking(false, speed, group32k, video, ratio, div2)
        }
    }

    /** Read the chip ID and revision: `Pair(0x1C, revision)`. */
    fun readId(): Pair<Int, Int> {
        val v = r(REG_ID)
        return Pair(v shr 3, v and 0x07)
    }

    /**
     * Pulse RESET and re-run the Minimal initialization.
     *
     * @throws IllegalStateException if no RESET pin was given
     */
    fun reset() {
        check(resPin != null) { "reset requires a RESET pin" }
        init()
    }

    /**
     * Select the power mode of the headphone and speaker amplifiers.
     *
     * @param headphone [OUT_FOLLOW_LOW] … [OUT_OFF] (0–3)
     * @param speaker same coding
     * @throws IllegalArgumentException if a mode is not 0–3
     */
    fun setOutputs(headphone: Int, speaker: Int) {
        require(headphone in 0..3 && speaker in 0..3) { "output mode must be 0-3" }
        w(REG_POWER_CTL_2, (headphone shl 6) or (headphone shl 4) or (speaker shl 2) or speaker)
    }

    /** Independent master volume of channel A (left) and B (right), −102 … +12 dB. */
    fun setVolumeChannels(leftDb: Double, rightDb: Double) {
        w(REG_MASTER_A_VOL, halfDbCode(leftDb, -102.0, 12.0))
        w(REG_MASTER_B_VOL, halfDbCode(rightDb, -102.0, 12.0))
    }

    /**
     * Headphone volume, 0 … −96 dB; below −96 dB the channel is muted (code 0x01).
     *
     * @param rightDb right channel; defaults to [leftDb]
     */
    fun setHeadphoneVolume(leftDb: Double, rightDb: Double = leftDb) {
        w(REG_HP_A_VOL, limitedVolumeCode(leftDb))
        w(REG_HP_B_VOL, limitedVolumeCode(rightDb))
    }

    /**
     * Speaker volume, 0 … −96 dB; below −96 dB the channel is muted.
     *
     * @param rightDb right channel; defaults to [leftDb]
     */
    fun setSpeakerVolume(leftDb: Double, rightDb: Double = leftDb) {
        w(REG_SPK_A_VOL, limitedVolumeCode(leftDb))
        w(REG_SPK_B_VOL, limitedVolumeCode(rightDb))
    }

    /**
     * PCM mixer volume, −51.5 … +12 dB (the mute bits are kept).
     *
     * @param rightDb right channel; defaults to [leftDb]
     */
    fun setPcmVolume(leftDb: Double, rightDb: Double = leftDb) {
        update(REG_PCM_A_VOL, 0x7F, halfDbCode(leftDb, -51.5, 12.0))
        update(REG_PCM_B_VOL, 0x7F, halfDbCode(rightDb, -51.5, 12.0))
    }

    /**
     * Mute or unmute the PCM channels.
     *
     * @param right channel B; defaults to [left]
     */
    fun mutePcm(left: Boolean, right: Boolean = left) {
        setBit(REG_PCM_A_VOL, 7, left)
        setBit(REG_PCM_B_VOL, 7, right)
    }

    /**
     * Select the headphone / line analog gain.
     *
     * @param index 0–7 ([GAIN_0_3959] … [GAIN_1_143])
     * @throws IllegalArgumentException if the index is not 0–7
     */
    fun setHeadphoneGain(index: Int) {
        require(index in 0..7) { "gain index must be 0-7" }
        update(REG_PLAYBACK_CTL_1, 0xE0, index shl 5)
    }

    /** Headphone analog gain multiplier (0.3959 … 1.143). */
    fun headphoneGain(): Double = HP_GAINS[r(REG_PLAYBACK_CTL_1) shr 5]

    /**
     * Mute or unmute the headphone and speaker outputs (both channels).
     *
     * @param headphone `true` mute, `false` unmute, `null` keep
     * @param speaker same for the speaker
     */
    fun muteOutputs(headphone: Boolean? = null, speaker: Boolean? = null) {
        if (headphone != null) update(REG_PLAYBACK_CTL_2, 0xC0, if (headphone) 0xC0 else 0)
        if (speaker != null) update(REG_PLAYBACK_CTL_2, 0x30, if (speaker) 0x30 else 0)
    }

    /** Configure the speaker channels: mono mix, channel swap, exact-50 % mute. */
    fun setSpeakerMode(mono: Boolean, swap: Boolean, mute5050: Boolean = false) {
        update(REG_PLAYBACK_CTL_2, 0x07, (if (mono) 0x02 else 0) or (if (swap) 0x04 else 0) or (if (mute5050) 0x01 else 0))
    }

    /**
     * Write `CLOCKING_CTL`.
     *
     * @param c the clocking settings (see [clockingFor])
     * @throws IllegalArgumentException if speed or ratio is not 0–3
     */
    fun setClocking(c: Clocking = Clocking()) {
        require(c.speed in 0..3 && c.ratio in 0..3) { "speed and ratio must be 0-3" }
        w(REG_CLOCKING_CTL, (if (c.auto) 0x80 else 0) or (c.speed shl 5) or (if (c.group32k) 0x10 else 0) or
            (if (c.videoClk) 0x08 else 0) or (c.ratio shl 1) or (if (c.mclkDiv2) 1 else 0))
    }

    /**
     * Configure the serial audio port (`INTERFACE_CTL_1` / `INTERFACE_CTL_2`).
     *
     * @param fmt [FMT_LJ], [FMT_I2S] or [FMT_RJ]
     * @param wordLength AWL code 0–3
     * @param dsp DSP (data-packed) mode; requires [FMT_LJ]
     * @throws IllegalArgumentException on an out-of-range argument or DSP without [FMT_LJ]
     */
    fun setInterface(
        master: Boolean = false,
        fmt: Int = FMT_I2S,
        wordLength: Int = 0,
        dsp: Boolean = false,
        invertSclk: Boolean = false,
        sclkEqMclk: Boolean = false,
        invertSwitch: Boolean = false,
    ) {
        require(fmt in 0..2 && wordLength in 0..3) { "fmt must be 0-2 and wordLength 0-3" }
        require(!dsp || fmt == FMT_LJ) { "DSP mode requires FMT_LJ" }
        w(REG_INTERFACE_CTL_1, (if (master) 0x80 else 0) or (if (invertSclk) 0x40 else 0) or
            (if (dsp) 0x10 else 0) or (fmt shl 2) or wordLength)
        w(REG_INTERFACE_CTL_2, (if (sclkEqMclk) 0x40 else 0) or (if (invertSwitch) 0x08 else 0))
    }

    /**
     * Treble / bass shelving filters.
     *
     * @param trebleDb −10.5 … +12 dB (nearest 1.5 dB step)
     * @param bassDb −10.5 … +12 dB
     * @param trebleCorner 0–3 (5 / 7 / 10 / 15 kHz)
     * @param bassCorner 0–3 (50 / 100 / 200 / 250 Hz)
     * @param enabled enable the tone control
     * @throws IllegalArgumentException if a corner is not 0–3
     */
    fun setTone(trebleDb: Double, bassDb: Double, trebleCorner: Int = 0, bassCorner: Int = 0, enabled: Boolean = true) {
        require(trebleCorner in 0..3 && bassCorner in 0..3) { "corner must be 0-3" }
        val treb = max(0, min(15, 8 - roundHalfUp(trebleDb / 1.5)))
        val bass = max(0, min(15, 8 - roundHalfUp(bassDb / 1.5)))
        w(REG_TONE_CTL, (treb shl 4) or bass)
        update(REG_BEEP_TONE_CFG, 0x1F, (trebleCorner shl 3) or (bassCorner shl 1) or (if (enabled) 1 else 0))
    }

    /**
     * Program the beep generator (timing first, mode last). [BEEP_OFF] only stops it.
     *
     * @param frequency code 0–15 (260.87 … 2181.82 Hz)
     * @param onTime code 0–15
     * @param offTime code 0–7
     * @param volumeDb +6 … −56 dB in 2 dB steps (nearest)
     * @param mode [BEEP_OFF] … [BEEP_CONTINUOUS]
     * @param mix mix the beep with the PCM stream
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IllegalStateException if a beep is active and `mode` is not [BEEP_OFF]
     */
    fun setBeep(frequency: Int, onTime: Int, offTime: Int, volumeDb: Double, mode: Int, mix: Boolean = true) {
        require(frequency in 0..15 && onTime in 0..15 && offTime in 0..7 && mode in 0..3) { "beep argument out of range" }
        if (mode == BEEP_OFF) {
            stopBeep()
            return
        }
        check((r(REG_BEEP_TONE_CFG) and 0xC0) == 0) { "beep active: call stopBeep() before changing the timing" }
        val n = max(0, min(31, roundHalfUp((6.0 - volumeDb) / 2.0)))
        w(REG_BEEP_FREQ_ON, (frequency shl 4) or onTime)
        w(REG_BEEP_VOL_OFF, (offTime shl 5) or ((6 - n) and 0x1F))
        update(REG_BEEP_TONE_CFG, 0xE0, (mode shl 6) or (if (mix) 0 else 0x20))
    }

    /** Stop the beep generator (`BEEP[1:0]` = 0). */
    fun stopBeep() {
        update(REG_BEEP_TONE_CFG, 0xC0, 0)
    }

    /**
     * Configure the peak limiter.
     *
     * @param maxDb maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB
     * @param cushionDb cushion threshold, same steps
     * @param release release rate 0 (fastest) … 63
     * @param attack attack rate 0 … 63
     * @param limitAll limit both channels together
     * @throws IllegalArgumentException if release or attack is not 0–63
     */
    fun setLimiter(
        enabled: Boolean,
        maxDb: Int = 0,
        cushionDb: Int = 0,
        release: Int = 0x3F,
        attack: Int = 0,
        limitAll: Boolean = true,
        softRampDisable: Boolean = false,
        zeroCrossDisable: Boolean = false,
    ) {
        require(release in 0..63 && attack in 0..63) { "release and attack must be 0-63" }
        w(REG_LIMIT_CTL_1, (nearestLimit(maxDb) shl 5) or (nearestLimit(cushionDb) shl 2) or
            (if (softRampDisable) 0x02 else 0) or (if (zeroCrossDisable) 1 else 0))
        w(REG_LIMIT_CTL_2, (if (enabled) 0x80 else 0) or (if (limitAll) 0x40 else 0) or release)
        w(REG_LIMIT_ATTACK, attack)
    }

    /** Soft-ramp volume transitions (`DIGSFT`, `ANLGSFTA`, `ANLGSFTB`). */
    fun setSoftRamp(digital: Boolean, analogLeft: Boolean = false, analogRight: Boolean = false) {
        setBit(REG_MISC_CTL, 1, digital)
        update(REG_ANALOG_ZC_SR, 0x0A, (if (analogLeft) 0x02 else 0) or (if (analogRight) 0x08 else 0))
    }

    /** Zero-cross volume transitions (`DIGZC`, `ANLGZCA`, `ANLGZCB`). */
    fun setZeroCross(digital: Boolean, analogLeft: Boolean = false, analogRight: Boolean = false) {
        setBit(REG_MISC_CTL, 0, digital)
        update(REG_ANALOG_ZC_SR, 0x05, (if (analogLeft) 0x01 else 0) or (if (analogRight) 0x04 else 0))
    }

    /** Digital de-emphasis filter. */
    fun setDeemphasis(enabled: Boolean) {
        setBit(REG_MISC_CTL, 2, enabled)
    }

    /**
     * Select the source of each PCM channel (`PCM_MIXER_SWAP`).
     *
     * @param leftSource [SRC_NORMAL], [SRC_MIX] or [SRC_SWAPPED]
     * @param rightSource same coding
     * @throws IllegalArgumentException if a source is not 0–3
     */
    fun setPcmSwap(leftSource: Int, rightSource: Int) {
        require(leftSource in 0..3 && rightSource in 0..3) { "source must be 0-3" }
        w(REG_PCM_MIXER_SWAP, (leftSource shl 6) or (rightSource shl 4))
    }

    /** Invert the PCM polarity of each channel. */
    fun invertPcm(left: Boolean, right: Boolean) {
        update(REG_PLAYBACK_CTL_1, 0x0C, (if (left) 0x04 else 0) or (if (right) 0x08 else 0))
    }

    /**
     * Route the analog line inputs to the headphone outputs.
     *
     * @param enabled enable passthrough on both channels
     * @param leftInput AIN1A … AIN4A (1–4)
     * @param rightInput AIN1B … AIN4B (1–4)
     * @throws IllegalArgumentException if an input is not 1–4
     */
    fun setPassthrough(enabled: Boolean, leftInput: Int = 1, rightInput: Int = 1) {
        require(leftInput in 1..4 && rightInput in 1..4) { "input must be 1-4" }
        w(REG_PASSTHRU_A_SEL, 0x80 or (1 shl (leftInput - 1)))
        w(REG_PASSTHRU_B_SEL, 0x80 or (1 shl (rightInput - 1)))
        update(REG_MISC_CTL, 0xC0, if (enabled) 0xC0 else 0)
    }

    /**
     * Passthrough volume, −60 … +12 dB.
     *
     * @param rightDb right channel; defaults to [leftDb]
     */
    fun setPassthroughVolume(leftDb: Double, rightDb: Double = leftDb) {
        w(REG_PASSTHRU_A_VOL, halfDbCode(leftDb, -60.0, 12.0))
        w(REG_PASSTHRU_B_VOL, halfDbCode(rightDb, -60.0, 12.0))
    }

    /**
     * Mute or unmute the passthrough channels.
     *
     * @param right channel B; defaults to [left]
     */
    fun mutePassthrough(left: Boolean, right: Boolean = left) {
        setBit(REG_MISC_CTL, 4, left)
        setBit(REG_MISC_CTL, 5, right)
    }

    /** Hold (`true`) or apply (`false`) register changes (`FREEZE`). */
    fun freeze(on: Boolean) {
        setBit(REG_MISC_CTL, 3, on)
    }

    /**
     * Battery compensation for the speaker supply VP.
     *
     * @param vpRefIndex VPREF code 0–15 (1.5 … 5.0 V; 0–7 for VA = 1.8 V, 8–15 for 2.5 V)
     * @throws IllegalArgumentException if `vpRefIndex` is not 0–15
     */
    fun setBatteryCompensation(enabled: Boolean, vpRefIndex: Int = 0, monitor: Boolean = false) {
        require(vpRefIndex in 0..15) { "vpRefIndex must be 0-15" }
        w(REG_BATTERY_COMP, (if (enabled) 0x80 else 0) or (if (monitor) 0x40 else 0) or vpRefIndex)
    }

    /**
     * VP supply voltage in volts: `VP_LEVEL × vaVolts / 63.3`.
     *
     * @param vaVolts the analog supply VA (1.8 or 2.5)
     */
    fun readVpVoltage(vaVolts: Double): Double = r(REG_VP_LEVEL) * vaVolts / 63.3

    /** Read the status register — **reading clears the flags**. */
    fun readStatus(): Status {
        val v = r(REG_STATUS)
        return Status((v and 0x40) != 0, (v and 0x20) != 0, (v and 0x10) != 0, (v and 0x08) != 0, (v and 0x04) != 0)
    }

    /** Read the speaker status register. */
    fun readSpeakerStatus(): SpeakerStatus {
        val v = r(REG_SPEAKER_STATUS)
        return SpeakerStatus((v and 0x20) != 0, (v and 0x10) != 0, (v and 0x08) != 0)
    }

    /**
     * Charge-pump frequency divider (frequency = 64·Fs / (N + 2)).
     *
     * @param n 0–15
     * @throws IllegalArgumentException if `n` is not 0–15
     */
    fun setChargePumpFrequency(n: Int) {
        require(n in 0..15) { "n must be 0-15" }
        update(REG_CHARGE_PUMP_FREQ, 0xF0, n shl 4)
    }

    /**
     * Read any documented register (reading `STATUS` clears it).
     *
     * @throws IllegalArgumentException if the register is reserved
     */
    fun readRegister(reg: Int): Int {
        require(documented(reg)) { "register is reserved" }
        return r(reg)
    }

    /**
     * Write any documented writable register.
     *
     * @throws IllegalArgumentException if the register is reserved or read-only
     */
    fun writeRegister(reg: Int, value: Int) {
        require(documented(reg) && reg != 0x01 && reg != 0x2E && reg != 0x30 && reg != 0x31) {
            "register is reserved or read-only"
        }
        w(reg, value)
    }
}
