package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * CS43L22 stereo DAC — full interface.
 *
 * <p>Adds per-channel volumes, output power selection, clocking and serial interface
 * configuration, tone control, the beep generator, the peak limiter, soft ramp / zero-cross,
 * analog passthrough, battery compensation, status reads and raw register access. Extends
 * {@link CS43L22Minimal}.
 */
@CompileStatic
class CS43L22Full extends CS43L22Minimal {

    public static final int OUT_FOLLOW_LOW = 0
    public static final int OUT_FOLLOW_HIGH = 1
    public static final int OUT_ON = 2
    public static final int OUT_OFF = 3
    public static final int FMT_LJ = 0
    public static final int FMT_I2S = 1
    public static final int FMT_RJ = 2
    public static final int BEEP_OFF = 0
    public static final int BEEP_SINGLE = 1
    public static final int BEEP_MULTIPLE = 2
    public static final int BEEP_CONTINUOUS = 3
    public static final int SRC_NORMAL = 0
    public static final int SRC_MIX = 1
    public static final int SRC_SWAPPED = 3
    public static final int GAIN_0_3959 = 0
    public static final int GAIN_0_4571 = 1
    public static final int GAIN_0_5111 = 2
    public static final int GAIN_0_6047 = 3
    public static final int GAIN_0_7099 = 4
    public static final int GAIN_0_8399 = 5
    public static final int GAIN_1_000 = 6
    public static final int GAIN_1_143 = 7

    private static final double[] HP_GAINS = [0.3959d, 0.4571d, 0.5111d, 0.6047d, 0.7099d, 0.8399d, 1.000d, 1.1430d] as double[]
    private static final int[] LIMIT_DB = [0, -3, -6, -9, -12, -18, -24, -30] as int[]
    private static final double[] FS_ROUND = [8000d, 12000d, 16000d, 24000d, 32000d, 48000d, 96000d] as double[]
    private static final double[] FS_44K = [11025d, 22050d, 44100d, 88200d] as double[]
    private static final double[] FS_FRAC = [12e6d / 1088d, 12e6d / 544d, 12e6d / 272d, 12e6d / 136d] as double[]
    private static final double[] FS_27M = [8000d, 12000d, 16000d, 24000d, 32000d, 48000d] as double[]

    /** Clocking settings (arguments of {@link #setClocking}). */
    static final class Clocking {
        /** Auto-detect the speed mode from MCLK / LRCK (slave mode). */
        final boolean auto
        /** Speed mode 0–3 (double, single, half, quarter). */
        final int speed
        /** True for 8, 16 and 32 kHz sample rates. */
        final boolean group32k
        /** True if MCLK is 27 MHz. */
        final boolean videoClk
        /** Internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136). */
        final int ratio
        /** Divide MCLK by 2. */
        final boolean mclkDiv2

        Clocking(boolean auto, int speed, boolean group32k, boolean videoClk, int ratio, boolean mclkDiv2) {
            this.auto = auto
            this.speed = speed
            this.group32k = group32k
            this.videoClk = videoClk
            this.ratio = ratio
            this.mclkDiv2 = mclkDiv2
        }
    }

    /** Decoded {@code STATUS} register (reading it clears the flags). */
    static final class Status {
        final boolean clockError
        final boolean dspAOverflow
        final boolean dspBOverflow
        final boolean pcmAOverflow
        final boolean pcmBOverflow

        Status(boolean clockError, boolean dspAOverflow, boolean dspBOverflow, boolean pcmAOverflow,
               boolean pcmBOverflow) {
            this.clockError = clockError
            this.dspAOverflow = dspAOverflow
            this.dspBOverflow = dspBOverflow
            this.pcmAOverflow = pcmAOverflow
            this.pcmBOverflow = pcmBOverflow
        }
    }

    /** Decoded {@code SPEAKER_STATUS} register. */
    static final class SpeakerStatus {
        final boolean speakerAShort
        final boolean speakerBShort
        final boolean spkrHpPin

        SpeakerStatus(boolean speakerAShort, boolean speakerBShort, boolean spkrHpPin) {
            this.speakerAShort = speakerAShort
            this.speakerBShort = speakerBShort
            this.spkrHpPin = spkrHpPin
        }
    }

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection I²C register connection bound to 0x4A / 0x4B
     * @param resPin optional output pin wired to the active-low RESET pin, or {@code null}
     */
    CS43L22Full(RegisterConnection connection, OutputPin resPin = null) {
        super(connection, resPin)
    }

    private static boolean isNear(double v, double c) {
        return Math.abs(v - c) <= c * 1e-4d
    }

    private static boolean nearAny(double v, double[] list, int n) {
        for (int i = 0; i < n; i++) {
            if (isNear(v, list[i])) {
                return true
            }
        }
        return false
    }

    private static int nearestLimit(int db) {
        int best = 0
        int bestErr = Math.abs(db - LIMIT_DB[0])
        for (int i = 1; i < 8; i++) {
            int err = Math.abs(db - LIMIT_DB[i])
            if (err < bestErr) {
                best = i
                bestErr = err
            }
        }
        return best
    }

    private static boolean documented(int reg) {
        return reg == 0x00 || reg == 0x01 || reg == 0x02 || (reg >= 0x04 && reg <= 0x0A) ||
                (reg >= 0x0C && reg <= 0x0F) || reg == 0x14 || reg == 0x15 || (reg >= 0x1A && reg <= 0x29) ||
                (reg >= 0x2E && reg <= 0x32) || reg == 0x34 || reg == 0x47
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what)
        }
    }

    /**
     * Datasheet Table 1 clocking settings for an MCLK / sample-rate pair.
     *
     * @param mclkHz 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz, in Hz
     * @param fsHz sample rate in Hz (a rate listed for that MCLK in Table 1)
     * @return the arguments of {@link #setClocking} with {@code auto} false
     * @throws IllegalArgumentException if the combination is not in the datasheet table
     */
    static Clocking clockingFor(long mclkHz, double fsHz) {
        double mclk = (double) mclkHz
        boolean video = false
        boolean div2 = false
        int ratio = -1
        if (isNear(mclk, 12288000d) || isNear(mclk, 18432000d)) {
            if (nearAny(fsHz, FS_ROUND, 7)) {
                ratio = 0
            }
        } else if (isNear(mclk, 11289600d)) {
            if (nearAny(fsHz, FS_44K, 4)) {
                ratio = 0
            }
        } else if (isNear(mclk, 16934400d)) {
            if (nearAny(fsHz, FS_44K, 4)) {
                ratio = 0
            } else if (isNear(fsHz, 16934400d / 2112d)) {
                ratio = 2
            }
        } else if (isNear(mclk, 12000000d) || isNear(mclk, 24000000d)) {
            div2 = mclk > 18000000d
            if (nearAny(fsHz, FS_ROUND, 7)) {
                ratio = 1
            } else if (nearAny(fsHz, FS_FRAC, 4)) {
                ratio = 3
            }
        } else if (isNear(mclk, 27000000d)) {
            video = true
            if (nearAny(fsHz, FS_27M, 6)) {
                ratio = 1
            } else if (nearAny(fsHz, FS_FRAC, 3)) {
                ratio = 3
            }
        }
        check(ratio >= 0, 'MCLK / sample-rate combination is not in the datasheet table')
        boolean group32k = isNear(fsHz, 8000d) || isNear(fsHz, 16000d) || isNear(fsHz, 32000d)
        int speed = fsHz <= 12100d ? 3 : fsHz <= 24100d ? 2 : fsHz <= 48100d ? 1 : 0
        return new Clocking(false, speed, group32k, video, ratio, div2)
    }

    /** Read the chip ID and revision: {@code [0x1C, revision]}. */
    int[] readId() {
        int v = r(REG_ID)
        return [v >> 3, v & 0x07] as int[]
    }

    /**
     * Pulse RESET and re-run the Minimal initialization.
     *
     * @throws IllegalStateException if no RESET pin was given
     */
    void reset() {
        if (resPin == null) {
            throw new IllegalStateException('reset requires a RESET pin')
        }
        init()
    }

    /**
     * Select the power mode of the headphone and speaker amplifiers.
     *
     * @param headphone {@link #OUT_FOLLOW_LOW} … {@link #OUT_OFF} (0–3)
     * @param speaker same coding
     * @throws IllegalArgumentException if a mode is not 0–3
     */
    void setOutputs(int headphone, int speaker) {
        check(headphone >= 0 && headphone <= 3 && speaker >= 0 && speaker <= 3, 'output mode must be 0-3')
        w(REG_POWER_CTL_2, (headphone << 6) | (headphone << 4) | (speaker << 2) | speaker)
    }

    /** Independent master volume of channel A (left) and B (right), −102 … +12 dB. */
    void setVolumeChannels(double leftDb, double rightDb) {
        w(REG_MASTER_A_VOL, halfDbCode(leftDb, -102.0d, 12.0d))
        w(REG_MASTER_B_VOL, halfDbCode(rightDb, -102.0d, 12.0d))
    }

    /** Headphone volume, both channels: 0 … −96 dB; below −96 dB muted (code 0x01). */
    void setHeadphoneVolume(double db) {
        setHeadphoneVolume(db, db)
    }

    /** Headphone volume, 0 … −96 dB; below −96 dB the channel is muted (code 0x01). */
    void setHeadphoneVolume(double leftDb, double rightDb) {
        w(REG_HP_A_VOL, limitedVolumeCode(leftDb))
        w(REG_HP_B_VOL, limitedVolumeCode(rightDb))
    }

    /** Speaker volume, both channels: 0 … −96 dB; below −96 dB muted. */
    void setSpeakerVolume(double db) {
        setSpeakerVolume(db, db)
    }

    /** Speaker volume, 0 … −96 dB; below −96 dB the channel is muted. */
    void setSpeakerVolume(double leftDb, double rightDb) {
        w(REG_SPK_A_VOL, limitedVolumeCode(leftDb))
        w(REG_SPK_B_VOL, limitedVolumeCode(rightDb))
    }

    /** PCM mixer volume, both channels, −51.5 … +12 dB (the mute bits are kept). */
    void setPcmVolume(double db) {
        setPcmVolume(db, db)
    }

    /** PCM mixer volume, −51.5 … +12 dB (the mute bits are kept). */
    void setPcmVolume(double leftDb, double rightDb) {
        update(REG_PCM_A_VOL, 0x7F, halfDbCode(leftDb, -51.5d, 12.0d))
        update(REG_PCM_B_VOL, 0x7F, halfDbCode(rightDb, -51.5d, 12.0d))
    }

    /** Mute or unmute the PCM channels. */
    void mutePcm(boolean left, boolean right) {
        setBit(REG_PCM_A_VOL, 7, left)
        setBit(REG_PCM_B_VOL, 7, right)
    }

    /**
     * Select the headphone / line analog gain.
     *
     * @param index 0–7 ({@link #GAIN_0_3959} … {@link #GAIN_1_143})
     * @throws IllegalArgumentException if the index is not 0–7
     */
    void setHeadphoneGain(int index) {
        check(index >= 0 && index <= 7, 'gain index must be 0-7')
        update(REG_PLAYBACK_CTL_1, 0xE0, index << 5)
    }

    /** Headphone analog gain multiplier (0.3959 … 1.143). */
    double headphoneGain() {
        return HP_GAINS[r(REG_PLAYBACK_CTL_1) >> 5]
    }

    /**
     * Mute or unmute the headphone and speaker outputs (both channels).
     *
     * @param headphone {@code TRUE} mute, {@code FALSE} unmute, {@code null} keep
     * @param speaker same for the speaker
     */
    void muteOutputs(Boolean headphone, Boolean speaker) {
        if (headphone != null) {
            update(REG_PLAYBACK_CTL_2, 0xC0, headphone.booleanValue() ? 0xC0 : 0)
        }
        if (speaker != null) {
            update(REG_PLAYBACK_CTL_2, 0x30, speaker.booleanValue() ? 0x30 : 0)
        }
    }

    /** Configure the speaker channels: mono mix, channel swap, exact-50 % mute. */
    void setSpeakerMode(boolean mono, boolean swap, boolean mute5050 = false) {
        update(REG_PLAYBACK_CTL_2, 0x07, (mono ? 0x02 : 0) | (swap ? 0x04 : 0) | (mute5050 ? 0x01 : 0))
    }

    /**
     * Write {@code CLOCKING_CTL}.
     *
     * @param c the clocking settings (see {@link #clockingFor})
     * @throws IllegalArgumentException if speed or ratio is not 0–3
     */
    void setClocking(Clocking c) {
        check(c.speed >= 0 && c.speed <= 3 && c.ratio >= 0 && c.ratio <= 3, 'speed and ratio must be 0-3')
        w(REG_CLOCKING_CTL, (c.auto ? 0x80 : 0) | (c.speed << 5) | (c.group32k ? 0x10 : 0) |
                (c.videoClk ? 0x08 : 0) | (c.ratio << 1) | (c.mclkDiv2 ? 1 : 0))
    }

    /**
     * Configure the serial audio port ({@code INTERFACE_CTL_1} / {@code INTERFACE_CTL_2}).
     *
     * @param fmt {@link #FMT_LJ}, {@link #FMT_I2S} or {@link #FMT_RJ}
     * @param wordLength AWL code 0–3
     * @param dsp DSP (data-packed) mode; requires {@link #FMT_LJ}
     * @throws IllegalArgumentException on an out-of-range argument or DSP without {@link #FMT_LJ}
     */
    void setInterface(boolean master, int fmt, int wordLength, boolean dsp, boolean invertSclk,
                      boolean sclkEqMclk, boolean invertSwitch) {
        check(fmt >= 0 && fmt <= 2 && wordLength >= 0 && wordLength <= 3, 'fmt must be 0-2 and wordLength 0-3')
        check(!dsp || fmt == FMT_LJ, 'DSP mode requires FMT_LJ')
        w(REG_INTERFACE_CTL_1, (master ? 0x80 : 0) | (invertSclk ? 0x40 : 0) | (dsp ? 0x10 : 0) |
                (fmt << 2) | wordLength)
        w(REG_INTERFACE_CTL_2, (sclkEqMclk ? 0x40 : 0) | (invertSwitch ? 0x08 : 0))
    }

    /** Configure the serial audio port as an I²S slave, 24-bit (the Minimal default). */
    void setInterface() {
        setInterface(false, FMT_I2S, 0, false, false, false, false)
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
    void setTone(double trebleDb, double bassDb, int trebleCorner = 0, int bassCorner = 0, boolean enabled = true) {
        check(trebleCorner >= 0 && trebleCorner <= 3 && bassCorner >= 0 && bassCorner <= 3, 'corner must be 0-3')
        int treb = Math.max(0, Math.min(15, 8 - roundHalfUp(trebleDb / 1.5d)))
        int bass = Math.max(0, Math.min(15, 8 - roundHalfUp(bassDb / 1.5d)))
        w(REG_TONE_CTL, (treb << 4) | bass)
        update(REG_BEEP_TONE_CFG, 0x1F, (trebleCorner << 3) | (bassCorner << 1) | (enabled ? 1 : 0))
    }

    /**
     * Program the beep generator (timing first, mode last). {@link #BEEP_OFF} only stops it.
     *
     * @param frequency code 0–15 (260.87 … 2181.82 Hz)
     * @param onTime code 0–15
     * @param offTime code 0–7
     * @param volumeDb +6 … −56 dB in 2 dB steps (nearest)
     * @param mode {@link #BEEP_OFF} … {@link #BEEP_CONTINUOUS}
     * @param mix mix the beep with the PCM stream
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IllegalStateException if a beep is active and {@code mode} is not {@link #BEEP_OFF}
     */
    void setBeep(int frequency, int onTime, int offTime, double volumeDb, int mode, boolean mix = true) {
        check(frequency >= 0 && frequency <= 15 && onTime >= 0 && onTime <= 15 && offTime >= 0 && offTime <= 7 &&
                mode >= 0 && mode <= 3, 'beep argument out of range')
        if (mode == BEEP_OFF) {
            stopBeep()
            return
        }
        if ((r(REG_BEEP_TONE_CFG) & 0xC0) != 0) {
            throw new IllegalStateException('beep active: call stopBeep() before changing the timing')
        }
        int n = Math.max(0, Math.min(31, roundHalfUp((6.0d - volumeDb) / 2.0d)))
        w(REG_BEEP_FREQ_ON, (frequency << 4) | onTime)
        w(REG_BEEP_VOL_OFF, (offTime << 5) | ((6 - n) & 0x1F))
        update(REG_BEEP_TONE_CFG, 0xE0, (mode << 6) | (mix ? 0 : 0x20))
    }

    /** Stop the beep generator ({@code BEEP[1:0]} = 0). */
    void stopBeep() {
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
    void setLimiter(boolean enabled, int maxDb = 0, int cushionDb = 0, int release = 0x3F, int attack = 0,
                    boolean limitAll = true, boolean softRampDisable = false, boolean zeroCrossDisable = false) {
        check(release >= 0 && release <= 63 && attack >= 0 && attack <= 63, 'release and attack must be 0-63')
        w(REG_LIMIT_CTL_1, (nearestLimit(maxDb) << 5) | (nearestLimit(cushionDb) << 2) |
                (softRampDisable ? 0x02 : 0) | (zeroCrossDisable ? 1 : 0))
        w(REG_LIMIT_CTL_2, (enabled ? 0x80 : 0) | (limitAll ? 0x40 : 0) | release)
        w(REG_LIMIT_ATTACK, attack)
    }

    /** Soft-ramp volume transitions ({@code DIGSFT}, {@code ANLGSFTA}, {@code ANLGSFTB}). */
    void setSoftRamp(boolean digital, boolean analogLeft = false, boolean analogRight = false) {
        setBit(REG_MISC_CTL, 1, digital)
        update(REG_ANALOG_ZC_SR, 0x0A, (analogLeft ? 0x02 : 0) | (analogRight ? 0x08 : 0))
    }

    /** Zero-cross volume transitions ({@code DIGZC}, {@code ANLGZCA}, {@code ANLGZCB}). */
    void setZeroCross(boolean digital, boolean analogLeft = false, boolean analogRight = false) {
        setBit(REG_MISC_CTL, 0, digital)
        update(REG_ANALOG_ZC_SR, 0x05, (analogLeft ? 0x01 : 0) | (analogRight ? 0x04 : 0))
    }

    /** Digital de-emphasis filter. */
    void setDeemphasis(boolean enabled) {
        setBit(REG_MISC_CTL, 2, enabled)
    }

    /**
     * Select the source of each PCM channel ({@code PCM_MIXER_SWAP}).
     *
     * @param leftSource {@link #SRC_NORMAL}, {@link #SRC_MIX} or {@link #SRC_SWAPPED}
     * @param rightSource same coding
     * @throws IllegalArgumentException if a source is not 0–3
     */
    void setPcmSwap(int leftSource, int rightSource) {
        check(leftSource >= 0 && leftSource <= 3 && rightSource >= 0 && rightSource <= 3, 'source must be 0-3')
        w(REG_PCM_MIXER_SWAP, (leftSource << 6) | (rightSource << 4))
    }

    /** Invert the PCM polarity of each channel. */
    void invertPcm(boolean left, boolean right) {
        update(REG_PLAYBACK_CTL_1, 0x0C, (left ? 0x04 : 0) | (right ? 0x08 : 0))
    }

    /**
     * Route the analog line inputs to the headphone outputs.
     *
     * @param enabled enable passthrough on both channels
     * @param leftInput AIN1A … AIN4A (1–4)
     * @param rightInput AIN1B … AIN4B (1–4)
     * @throws IllegalArgumentException if an input is not 1–4
     */
    void setPassthrough(boolean enabled, int leftInput = 1, int rightInput = 1) {
        check(leftInput >= 1 && leftInput <= 4 && rightInput >= 1 && rightInput <= 4, 'input must be 1-4')
        w(REG_PASSTHRU_A_SEL, 0x80 | (1 << (leftInput - 1)))
        w(REG_PASSTHRU_B_SEL, 0x80 | (1 << (rightInput - 1)))
        update(REG_MISC_CTL, 0xC0, enabled ? 0xC0 : 0)
    }

    /** Passthrough volume, −60 … +12 dB. */
    void setPassthroughVolume(double leftDb, double rightDb) {
        w(REG_PASSTHRU_A_VOL, halfDbCode(leftDb, -60.0d, 12.0d))
        w(REG_PASSTHRU_B_VOL, halfDbCode(rightDb, -60.0d, 12.0d))
    }

    /** Mute or unmute the passthrough channels. */
    void mutePassthrough(boolean left, boolean right) {
        setBit(REG_MISC_CTL, 4, left)
        setBit(REG_MISC_CTL, 5, right)
    }

    /** Hold ({@code true}) or apply ({@code false}) register changes ({@code FREEZE}). */
    void freeze(boolean on) {
        setBit(REG_MISC_CTL, 3, on)
    }

    /**
     * Battery compensation for the speaker supply VP.
     *
     * @param vpRefIndex VPREF code 0–15 (1.5 … 5.0 V; 0–7 for VA = 1.8 V, 8–15 for 2.5 V)
     * @throws IllegalArgumentException if {@code vpRefIndex} is not 0–15
     */
    void setBatteryCompensation(boolean enabled, int vpRefIndex = 0, boolean monitor = false) {
        check(vpRefIndex >= 0 && vpRefIndex <= 15, 'vpRefIndex must be 0-15')
        w(REG_BATTERY_COMP, (enabled ? 0x80 : 0) | (monitor ? 0x40 : 0) | vpRefIndex)
    }

    /**
     * VP supply voltage in volts: {@code VP_LEVEL × vaVolts / 63.3}.
     *
     * @param vaVolts the analog supply VA (1.8 or 2.5)
     */
    double readVpVoltage(double vaVolts) {
        return r(REG_VP_LEVEL) * vaVolts / 63.3d
    }

    /** Read the status register — reading clears the flags. */
    Status readStatus() {
        int v = r(REG_STATUS)
        return new Status((v & 0x40) != 0, (v & 0x20) != 0, (v & 0x10) != 0, (v & 0x08) != 0, (v & 0x04) != 0)
    }

    /** Read the speaker status register. */
    SpeakerStatus readSpeakerStatus() {
        int v = r(REG_SPEAKER_STATUS)
        return new SpeakerStatus((v & 0x20) != 0, (v & 0x10) != 0, (v & 0x08) != 0)
    }

    /**
     * Charge-pump frequency divider (frequency = 64·Fs / (N + 2)).
     *
     * @param n 0–15
     * @throws IllegalArgumentException if {@code n} is not 0–15
     */
    void setChargePumpFrequency(int n) {
        check(n >= 0 && n <= 15, 'n must be 0-15')
        update(REG_CHARGE_PUMP_FREQ, 0xF0, n << 4)
    }

    /**
     * Read any documented register (reading {@code STATUS} clears it).
     *
     * @throws IllegalArgumentException if the register is reserved
     */
    int readRegister(int reg) {
        check(documented(reg), 'register is reserved')
        return r(reg)
    }

    /**
     * Write any documented writable register.
     *
     * @throws IllegalArgumentException if the register is reserved or read-only
     */
    void writeRegister(int reg, int value) {
        check(documented(reg) && reg != 0x01 && reg != 0x2E && reg != 0x30 && reg != 0x31,
                'register is reserved or read-only')
        w(reg, value)
    }
}
