package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * CS43L22 stereo DAC — full interface.
 *
 * <p>Adds per-channel volumes, output power selection, clocking and serial interface
 * configuration, tone control, the beep generator, the peak limiter, soft ramp / zero-cross,
 * analog passthrough, battery compensation, status reads and raw register access. Extends
 * {@link CS43L22Minimal}.
 */
public class CS43L22Full extends CS43L22Minimal {

    /** Output power mode: on when the SPKR/HP_SW pin is low. */
    public static final int OUT_FOLLOW_LOW = 0;
    /** Output power mode: on when the SPKR/HP_SW pin is high. */
    public static final int OUT_FOLLOW_HIGH = 1;
    /** Output power mode: always on. */
    public static final int OUT_ON = 2;
    /** Output power mode: always off. */
    public static final int OUT_OFF = 3;
    /** Serial format: left-justified. */
    public static final int FMT_LJ = 0;
    /** Serial format: I²S. */
    public static final int FMT_I2S = 1;
    /** Serial format: right-justified. */
    public static final int FMT_RJ = 2;
    /** Beep mode: off. */
    public static final int BEEP_OFF = 0;
    /** Beep mode: single beep. */
    public static final int BEEP_SINGLE = 1;
    /** Beep mode: repeated beeps. */
    public static final int BEEP_MULTIPLE = 2;
    /** Beep mode: continuous tone. */
    public static final int BEEP_CONTINUOUS = 3;
    /** PCM source: channel as is. */
    public static final int SRC_NORMAL = 0;
    /** PCM source: (L+R)/2. */
    public static final int SRC_MIX = 1;
    /** PCM source: swapped channel. */
    public static final int SRC_SWAPPED = 3;
    /** Headphone gain index: G = 0.3959. */
    public static final int GAIN_0_3959 = 0;
    /** Headphone gain index: G = 0.4571. */
    public static final int GAIN_0_4571 = 1;
    /** Headphone gain index: G = 0.5111. */
    public static final int GAIN_0_5111 = 2;
    /** Headphone gain index: G = 0.6047 (default). */
    public static final int GAIN_0_6047 = 3;
    /** Headphone gain index: G = 0.7099. */
    public static final int GAIN_0_7099 = 4;
    /** Headphone gain index: G = 0.8399. */
    public static final int GAIN_0_8399 = 5;
    /** Headphone gain index: G = 1.000. */
    public static final int GAIN_1_000 = 6;
    /** Headphone gain index: G = 1.143. */
    public static final int GAIN_1_143 = 7;

    private static final double[] HP_GAINS = {0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430};
    private static final int[] LIMIT_DB = {0, -3, -6, -9, -12, -18, -24, -30};
    private static final double[] FS_ROUND = {8000, 12000, 16000, 24000, 32000, 48000, 96000};
    private static final double[] FS_44K = {11025, 22050, 44100, 88200};
    private static final double[] FS_FRAC = {12e6 / 1088, 12e6 / 544, 12e6 / 272, 12e6 / 136};
    private static final double[] FS_27M = {8000, 12000, 16000, 24000, 32000, 48000};

    /** Clocking settings (arguments of {@link #setClocking}). */
    public static final class Clocking {
        /** Auto-detect the speed mode from MCLK / LRCK (slave mode). */
        public final boolean auto;
        /** Speed mode 0–3 (double, single, half, quarter). */
        public final int speed;
        /** True for 8, 16 and 32 kHz sample rates. */
        public final boolean group32k;
        /** True if MCLK is 27 MHz. */
        public final boolean videoClk;
        /** Internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136). */
        public final int ratio;
        /** Divide MCLK by 2. */
        public final boolean mclkDiv2;

        /** Create a clocking setting. */
        public Clocking(boolean auto, int speed, boolean group32k, boolean videoClk, int ratio, boolean mclkDiv2) {
            this.auto = auto;
            this.speed = speed;
            this.group32k = group32k;
            this.videoClk = videoClk;
            this.ratio = ratio;
            this.mclkDiv2 = mclkDiv2;
        }
    }

    /** Decoded {@code STATUS} register (reading it clears the flags). */
    public static final class Status {
        /** MCLK / LRCK ratio invalid (reads 1 right after the clocks start). */
        public final boolean clockError;
        /** DSP channel A overflow. */
        public final boolean dspAOverflow;
        /** DSP channel B overflow. */
        public final boolean dspBOverflow;
        /** PCM channel A overflow. */
        public final boolean pcmAOverflow;
        /** PCM channel B overflow. */
        public final boolean pcmBOverflow;

        /** Create a status value. */
        public Status(boolean clockError, boolean dspAOverflow, boolean dspBOverflow,
                      boolean pcmAOverflow, boolean pcmBOverflow) {
            this.clockError = clockError;
            this.dspAOverflow = dspAOverflow;
            this.dspBOverflow = dspBOverflow;
            this.pcmAOverflow = pcmAOverflow;
            this.pcmBOverflow = pcmBOverflow;
        }
    }

    /** Decoded {@code SPEAKER_STATUS} register. */
    public static final class SpeakerStatus {
        /** Speaker A output shorted to ground. */
        public final boolean speakerAShort;
        /** Speaker B output shorted to ground. */
        public final boolean speakerBShort;
        /** Level of the SPKR/HP_SW pin. */
        public final boolean spkrHpPin;

        /** Create a speaker status value. */
        public SpeakerStatus(boolean speakerAShort, boolean speakerBShort, boolean spkrHpPin) {
            this.speakerAShort = speakerAShort;
            this.speakerBShort = speakerBShort;
            this.spkrHpPin = spkrHpPin;
        }
    }

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection I²C register connection bound to 0x4A / 0x4B
     */
    public CS43L22Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Construct the driver; performs no bus traffic.
     *
     * @param connection I²C register connection bound to 0x4A / 0x4B
     * @param resPin optional output pin wired to the active-low RESET pin, or {@code null}
     */
    public CS43L22Full(RegisterConnection connection, OutputPin resPin) {
        super(connection, resPin);
    }

    private static boolean isNear(double v, double c) {
        return Math.abs(v - c) <= c * 1e-4;
    }

    private static boolean nearAny(double v, double[] list, int n) {
        for (int i = 0; i < n; i++) {
            if (isNear(v, list[i])) {
                return true;
            }
        }
        return false;
    }

    private static int nearestLimit(int db) {
        int best = 0;
        int bestErr = Math.abs(db - LIMIT_DB[0]);
        for (int i = 1; i < 8; i++) {
            int err = Math.abs(db - LIMIT_DB[i]);
            if (err < bestErr) {
                best = i;
                bestErr = err;
            }
        }
        return best;
    }

    private static boolean documented(int reg) {
        return reg == 0x00 || reg == 0x01 || reg == 0x02 || (reg >= 0x04 && reg <= 0x0A)
                || (reg >= 0x0C && reg <= 0x0F) || reg == 0x14 || reg == 0x15 || (reg >= 0x1A && reg <= 0x29)
                || (reg >= 0x2E && reg <= 0x32) || reg == 0x34 || reg == 0x47;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what);
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
    public static Clocking clockingFor(long mclkHz, double fsHz) {
        double mclk = mclkHz;
        boolean video = false;
        boolean div2 = false;
        int ratio = -1;
        if (isNear(mclk, 12288000) || isNear(mclk, 18432000)) {
            if (nearAny(fsHz, FS_ROUND, 7)) {
                ratio = 0;
            }
        } else if (isNear(mclk, 11289600)) {
            if (nearAny(fsHz, FS_44K, 4)) {
                ratio = 0;
            }
        } else if (isNear(mclk, 16934400)) {
            if (nearAny(fsHz, FS_44K, 4)) {
                ratio = 0;
            } else if (isNear(fsHz, 16934400.0 / 2112)) {
                ratio = 2;
            }
        } else if (isNear(mclk, 12000000) || isNear(mclk, 24000000)) {
            div2 = mclk > 18000000;
            if (nearAny(fsHz, FS_ROUND, 7)) {
                ratio = 1;
            } else if (nearAny(fsHz, FS_FRAC, 4)) {
                ratio = 3;
            }
        } else if (isNear(mclk, 27000000)) {
            video = true;
            if (nearAny(fsHz, FS_27M, 6)) {
                ratio = 1;
            } else if (nearAny(fsHz, FS_FRAC, 3)) {
                ratio = 3;
            }
        }
        check(ratio >= 0, "MCLK / sample-rate combination is not in the datasheet table");
        boolean group32k = isNear(fsHz, 8000) || isNear(fsHz, 16000) || isNear(fsHz, 32000);
        int speed = fsHz <= 12100 ? 3 : fsHz <= 24100 ? 2 : fsHz <= 48100 ? 1 : 0;
        return new Clocking(false, speed, group32k, video, ratio, div2);
    }

    /**
     * Read the chip ID and revision.
     *
     * @return {@code {0x1C, revision}}
     * @throws IOException on I²C error
     */
    public int[] readId() throws IOException {
        int v = r(REG_ID);
        return new int[]{v >> 3, v & 0x07};
    }

    /**
     * Pulse RESET and re-run the Minimal initialization.
     *
     * @throws IOException on I²C error
     * @throws IllegalStateException if no RESET pin was given
     */
    public void reset() throws IOException {
        if (resPin == null) {
            throw new IllegalStateException("reset requires a RESET pin");
        }
        init();
    }

    /**
     * Select the power mode of the headphone and speaker amplifiers.
     *
     * @param headphone {@link #OUT_FOLLOW_LOW} … {@link #OUT_OFF} (0–3)
     * @param speaker same coding
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if a mode is not 0–3
     */
    public void setOutputs(int headphone, int speaker) throws IOException {
        check(headphone >= 0 && headphone <= 3 && speaker >= 0 && speaker <= 3, "output mode must be 0-3");
        w(REG_POWER_CTL_2, (headphone << 6) | (headphone << 4) | (speaker << 2) | speaker);
    }

    /**
     * Independent master volume of channel A (left) and B (right).
     *
     * @param leftDb −102 … +12 dB
     * @param rightDb −102 … +12 dB
     * @throws IOException on I²C error
     */
    public void setVolumeChannels(double leftDb, double rightDb) throws IOException {
        w(REG_MASTER_A_VOL, halfDbCode(leftDb, -102.0, 12.0));
        w(REG_MASTER_B_VOL, halfDbCode(rightDb, -102.0, 12.0));
    }

    /**
     * Headphone volume, both channels.
     *
     * @param db 0 … −96 dB; below −96 dB the channels are muted (code 0x01)
     * @throws IOException on I²C error
     */
    public void setHeadphoneVolume(double db) throws IOException {
        setHeadphoneVolume(db, db);
    }

    /**
     * Headphone volume.
     *
     * @param leftDb 0 … −96 dB; below −96 dB muted
     * @param rightDb right channel
     * @throws IOException on I²C error
     */
    public void setHeadphoneVolume(double leftDb, double rightDb) throws IOException {
        w(REG_HP_A_VOL, limitedVolumeCode(leftDb));
        w(REG_HP_B_VOL, limitedVolumeCode(rightDb));
    }

    /**
     * Speaker volume, both channels.
     *
     * @param db 0 … −96 dB; below −96 dB the channels are muted
     * @throws IOException on I²C error
     */
    public void setSpeakerVolume(double db) throws IOException {
        setSpeakerVolume(db, db);
    }

    /**
     * Speaker volume.
     *
     * @param leftDb 0 … −96 dB; below −96 dB muted
     * @param rightDb right channel
     * @throws IOException on I²C error
     */
    public void setSpeakerVolume(double leftDb, double rightDb) throws IOException {
        w(REG_SPK_A_VOL, limitedVolumeCode(leftDb));
        w(REG_SPK_B_VOL, limitedVolumeCode(rightDb));
    }

    /**
     * PCM mixer volume, both channels (the mute bits are kept).
     *
     * @param db −51.5 … +12 dB
     * @throws IOException on I²C error
     */
    public void setPcmVolume(double db) throws IOException {
        setPcmVolume(db, db);
    }

    /**
     * PCM mixer volume (the mute bits are kept).
     *
     * @param leftDb −51.5 … +12 dB
     * @param rightDb −51.5 … +12 dB
     * @throws IOException on I²C error
     */
    public void setPcmVolume(double leftDb, double rightDb) throws IOException {
        update(REG_PCM_A_VOL, 0x7F, halfDbCode(leftDb, -51.5, 12.0));
        update(REG_PCM_B_VOL, 0x7F, halfDbCode(rightDb, -51.5, 12.0));
    }

    /**
     * Mute or unmute the PCM channels.
     *
     * @param left {@code true} to mute channel A
     * @param right {@code true} to mute channel B
     * @throws IOException on I²C error
     */
    public void mutePcm(boolean left, boolean right) throws IOException {
        setBit(REG_PCM_A_VOL, 7, left);
        setBit(REG_PCM_B_VOL, 7, right);
    }

    /**
     * Select the headphone / line analog gain.
     *
     * @param index 0–7 ({@link #GAIN_0_3959} … {@link #GAIN_1_143})
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the index is not 0–7
     */
    public void setHeadphoneGain(int index) throws IOException {
        check(index >= 0 && index <= 7, "gain index must be 0-7");
        update(REG_PLAYBACK_CTL_1, 0xE0, index << 5);
    }

    /**
     * Headphone analog gain multiplier.
     *
     * @return 0.3959 … 1.143
     * @throws IOException on I²C error
     */
    public double headphoneGain() throws IOException {
        return HP_GAINS[r(REG_PLAYBACK_CTL_1) >> 5];
    }

    /**
     * Mute or unmute the headphone and speaker outputs (both channels).
     *
     * @param headphone {@code TRUE} mute, {@code FALSE} unmute, {@code null} keep
     * @param speaker same for the speaker
     * @throws IOException on I²C error
     */
    public void muteOutputs(Boolean headphone, Boolean speaker) throws IOException {
        if (headphone != null) {
            update(REG_PLAYBACK_CTL_2, 0xC0, headphone ? 0xC0 : 0);
        }
        if (speaker != null) {
            update(REG_PLAYBACK_CTL_2, 0x30, speaker ? 0x30 : 0);
        }
    }

    /**
     * Configure the speaker channels.
     *
     * @param mono mix both channels to mono
     * @param swap swap the speaker channels
     * @param mute5050 mute as an exact 50 % duty cycle
     * @throws IOException on I²C error
     */
    public void setSpeakerMode(boolean mono, boolean swap, boolean mute5050) throws IOException {
        update(REG_PLAYBACK_CTL_2, 0x07, (mono ? 0x02 : 0) | (swap ? 0x04 : 0) | (mute5050 ? 0x01 : 0));
    }

    /**
     * Write {@code CLOCKING_CTL}.
     *
     * @param c the clocking settings (see {@link #clockingFor})
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if speed or ratio is not 0–3
     */
    public void setClocking(Clocking c) throws IOException {
        check(c.speed >= 0 && c.speed <= 3 && c.ratio >= 0 && c.ratio <= 3, "speed and ratio must be 0-3");
        w(REG_CLOCKING_CTL, (c.auto ? 0x80 : 0) | (c.speed << 5) | (c.group32k ? 0x10 : 0)
                | (c.videoClk ? 0x08 : 0) | (c.ratio << 1) | (c.mclkDiv2 ? 1 : 0));
    }

    /**
     * Configure the serial audio port ({@code INTERFACE_CTL_1} / {@code INTERFACE_CTL_2}).
     *
     * @param master master mode (clocks are outputs)
     * @param fmt {@link #FMT_LJ}, {@link #FMT_I2S} or {@link #FMT_RJ}
     * @param wordLength AWL code 0–3
     * @param dsp DSP (data-packed) mode; requires {@link #FMT_LJ}
     * @param invertSclk invert the SCLK polarity
     * @param sclkEqMclk SCLK = MCLK (master mode)
     * @param invertSwitch invert the SPKR/HP_SW pin sense
     * @throws IOException on I²C error
     * @throws IllegalArgumentException on an out-of-range argument or DSP without {@link #FMT_LJ}
     */
    public void setInterface(boolean master, int fmt, int wordLength, boolean dsp, boolean invertSclk,
                             boolean sclkEqMclk, boolean invertSwitch) throws IOException {
        check(fmt >= 0 && fmt <= 2 && wordLength >= 0 && wordLength <= 3, "fmt must be 0-2 and wordLength 0-3");
        check(!dsp || fmt == FMT_LJ, "DSP mode requires FMT_LJ");
        w(REG_INTERFACE_CTL_1, (master ? 0x80 : 0) | (invertSclk ? 0x40 : 0) | (dsp ? 0x10 : 0)
                | (fmt << 2) | wordLength);
        w(REG_INTERFACE_CTL_2, (sclkEqMclk ? 0x40 : 0) | (invertSwitch ? 0x08 : 0));
    }

    /**
     * Treble / bass shelving filters.
     *
     * @param trebleDb −10.5 … +12 dB (nearest 1.5 dB step)
     * @param bassDb −10.5 … +12 dB
     * @param trebleCorner 0–3 (5 / 7 / 10 / 15 kHz)
     * @param bassCorner 0–3 (50 / 100 / 200 / 250 Hz)
     * @param enabled enable the tone control
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if a corner is not 0–3
     */
    public void setTone(double trebleDb, double bassDb, int trebleCorner, int bassCorner, boolean enabled)
            throws IOException {
        check(trebleCorner >= 0 && trebleCorner <= 3 && bassCorner >= 0 && bassCorner <= 3, "corner must be 0-3");
        int treb = Math.max(0, Math.min(15, 8 - roundHalfUp(trebleDb / 1.5)));
        int bass = Math.max(0, Math.min(15, 8 - roundHalfUp(bassDb / 1.5)));
        w(REG_TONE_CTL, (treb << 4) | bass);
        update(REG_BEEP_TONE_CFG, 0x1F, (trebleCorner << 3) | (bassCorner << 1) | (enabled ? 1 : 0));
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
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an argument is out of range
     * @throws IllegalStateException if a beep is active and {@code mode} is not {@link #BEEP_OFF}
     */
    public void setBeep(int frequency, int onTime, int offTime, double volumeDb, int mode, boolean mix)
            throws IOException {
        check(frequency >= 0 && frequency <= 15 && onTime >= 0 && onTime <= 15 && offTime >= 0 && offTime <= 7
                && mode >= 0 && mode <= 3, "beep argument out of range");
        if (mode == BEEP_OFF) {
            stopBeep();
            return;
        }
        if ((r(REG_BEEP_TONE_CFG) & 0xC0) != 0) {
            throw new IllegalStateException("beep active: call stopBeep() before changing the timing");
        }
        int n = Math.max(0, Math.min(31, roundHalfUp((6.0 - volumeDb) / 2.0)));
        w(REG_BEEP_FREQ_ON, (frequency << 4) | onTime);
        w(REG_BEEP_VOL_OFF, (offTime << 5) | ((6 - n) & 0x1F));
        update(REG_BEEP_TONE_CFG, 0xE0, (mode << 6) | (mix ? 0 : 0x20));
    }

    /**
     * Stop the beep generator ({@code BEEP[1:0]} = 0).
     *
     * @throws IOException on I²C error
     */
    public void stopBeep() throws IOException {
        update(REG_BEEP_TONE_CFG, 0xC0, 0);
    }

    /**
     * Configure the peak limiter.
     *
     * @param enabled enable the limiter
     * @param maxDb maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB
     * @param cushionDb cushion threshold, same steps
     * @param release release rate 0 (fastest) … 63
     * @param attack attack rate 0 … 63
     * @param limitAll limit both channels together
     * @param softRampDisable disable soft ramp while limiting
     * @param zeroCrossDisable disable zero-cross while limiting
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if release or attack is not 0–63
     */
    public void setLimiter(boolean enabled, int maxDb, int cushionDb, int release, int attack, boolean limitAll,
                           boolean softRampDisable, boolean zeroCrossDisable) throws IOException {
        check(release >= 0 && release <= 63 && attack >= 0 && attack <= 63, "release and attack must be 0-63");
        w(REG_LIMIT_CTL_1, (nearestLimit(maxDb) << 5) | (nearestLimit(cushionDb) << 2)
                | (softRampDisable ? 0x02 : 0) | (zeroCrossDisable ? 1 : 0));
        w(REG_LIMIT_CTL_2, (enabled ? 0x80 : 0) | (limitAll ? 0x40 : 0) | release);
        w(REG_LIMIT_ATTACK, attack);
    }

    /**
     * Configure the peak limiter with the default release (slowest), attack 0 and limit-all.
     *
     * @param enabled enable the limiter
     * @param maxDb maximum threshold
     * @param cushionDb cushion threshold
     * @throws IOException on I²C error
     */
    public void setLimiter(boolean enabled, int maxDb, int cushionDb) throws IOException {
        setLimiter(enabled, maxDb, cushionDb, 0x3F, 0, true, false, false);
    }

    /**
     * Soft-ramp volume transitions.
     *
     * @param digital digital volume ({@code DIGSFT})
     * @param analogLeft analog channel A ({@code ANLGSFTA})
     * @param analogRight analog channel B ({@code ANLGSFTB})
     * @throws IOException on I²C error
     */
    public void setSoftRamp(boolean digital, boolean analogLeft, boolean analogRight) throws IOException {
        setBit(REG_MISC_CTL, 1, digital);
        update(REG_ANALOG_ZC_SR, 0x0A, (analogLeft ? 0x02 : 0) | (analogRight ? 0x08 : 0));
    }

    /**
     * Zero-cross volume transitions.
     *
     * @param digital digital volume ({@code DIGZC})
     * @param analogLeft analog channel A ({@code ANLGZCA})
     * @param analogRight analog channel B ({@code ANLGZCB})
     * @throws IOException on I²C error
     */
    public void setZeroCross(boolean digital, boolean analogLeft, boolean analogRight) throws IOException {
        setBit(REG_MISC_CTL, 0, digital);
        update(REG_ANALOG_ZC_SR, 0x05, (analogLeft ? 0x01 : 0) | (analogRight ? 0x04 : 0));
    }

    /**
     * Digital de-emphasis filter.
     *
     * @param enabled {@code true} to enable
     * @throws IOException on I²C error
     */
    public void setDeemphasis(boolean enabled) throws IOException {
        setBit(REG_MISC_CTL, 2, enabled);
    }

    /**
     * Select the source of each PCM channel ({@code PCM_MIXER_SWAP}).
     *
     * @param leftSource {@link #SRC_NORMAL}, {@link #SRC_MIX} or {@link #SRC_SWAPPED}
     * @param rightSource same coding
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if a source is not 0–3
     */
    public void setPcmSwap(int leftSource, int rightSource) throws IOException {
        check(leftSource >= 0 && leftSource <= 3 && rightSource >= 0 && rightSource <= 3, "source must be 0-3");
        w(REG_PCM_MIXER_SWAP, (leftSource << 6) | (rightSource << 4));
    }

    /**
     * Invert the PCM polarity of each channel.
     *
     * @param left invert channel A
     * @param right invert channel B
     * @throws IOException on I²C error
     */
    public void invertPcm(boolean left, boolean right) throws IOException {
        update(REG_PLAYBACK_CTL_1, 0x0C, (left ? 0x04 : 0) | (right ? 0x08 : 0));
    }

    /**
     * Route the analog line inputs to the headphone outputs.
     *
     * @param enabled enable passthrough on both channels
     * @param leftInput AIN1A … AIN4A (1–4)
     * @param rightInput AIN1B … AIN4B (1–4)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if an input is not 1–4
     */
    public void setPassthrough(boolean enabled, int leftInput, int rightInput) throws IOException {
        check(leftInput >= 1 && leftInput <= 4 && rightInput >= 1 && rightInput <= 4, "input must be 1-4");
        w(REG_PASSTHRU_A_SEL, 0x80 | (1 << (leftInput - 1)));
        w(REG_PASSTHRU_B_SEL, 0x80 | (1 << (rightInput - 1)));
        update(REG_MISC_CTL, 0xC0, enabled ? 0xC0 : 0);
    }

    /**
     * Passthrough volume.
     *
     * @param leftDb −60 … +12 dB
     * @param rightDb −60 … +12 dB
     * @throws IOException on I²C error
     */
    public void setPassthroughVolume(double leftDb, double rightDb) throws IOException {
        w(REG_PASSTHRU_A_VOL, halfDbCode(leftDb, -60.0, 12.0));
        w(REG_PASSTHRU_B_VOL, halfDbCode(rightDb, -60.0, 12.0));
    }

    /**
     * Mute or unmute the passthrough channels.
     *
     * @param left {@code true} to mute channel A
     * @param right {@code true} to mute channel B
     * @throws IOException on I²C error
     */
    public void mutePassthrough(boolean left, boolean right) throws IOException {
        setBit(REG_MISC_CTL, 4, left);
        setBit(REG_MISC_CTL, 5, right);
    }

    /**
     * Hold ({@code true}) or apply ({@code false}) register changes ({@code FREEZE}).
     *
     * @param on {@code true} to freeze
     * @throws IOException on I²C error
     */
    public void freeze(boolean on) throws IOException {
        setBit(REG_MISC_CTL, 3, on);
    }

    /**
     * Battery compensation for the speaker supply VP.
     *
     * @param enabled enable battery compensation
     * @param vpRefIndex VPREF code 0–15 (1.5 … 5.0 V; 0–7 for VA = 1.8 V, 8–15 for 2.5 V)
     * @param monitor enable the VP monitor
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if {@code vpRefIndex} is not 0–15
     */
    public void setBatteryCompensation(boolean enabled, int vpRefIndex, boolean monitor) throws IOException {
        check(vpRefIndex >= 0 && vpRefIndex <= 15, "vpRefIndex must be 0-15");
        w(REG_BATTERY_COMP, (enabled ? 0x80 : 0) | (monitor ? 0x40 : 0) | vpRefIndex);
    }

    /**
     * VP supply voltage.
     *
     * @param vaVolts the analog supply VA (1.8 or 2.5)
     * @return {@code VP_LEVEL × vaVolts / 63.3} in volts
     * @throws IOException on I²C error
     */
    public double readVpVoltage(double vaVolts) throws IOException {
        return r(REG_VP_LEVEL) * vaVolts / 63.3;
    }

    /**
     * Read the status register — reading clears the flags.
     *
     * @return the decoded flags
     * @throws IOException on I²C error
     */
    public Status readStatus() throws IOException {
        int v = r(REG_STATUS);
        return new Status((v & 0x40) != 0, (v & 0x20) != 0, (v & 0x10) != 0, (v & 0x08) != 0, (v & 0x04) != 0);
    }

    /**
     * Read the speaker status register.
     *
     * @return the decoded flags
     * @throws IOException on I²C error
     */
    public SpeakerStatus readSpeakerStatus() throws IOException {
        int v = r(REG_SPEAKER_STATUS);
        return new SpeakerStatus((v & 0x20) != 0, (v & 0x10) != 0, (v & 0x08) != 0);
    }

    /**
     * Charge-pump frequency divider (frequency = 64·Fs / (N + 2)).
     *
     * @param n 0–15
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if {@code n} is not 0–15
     */
    public void setChargePumpFrequency(int n) throws IOException {
        check(n >= 0 && n <= 15, "n must be 0-15");
        update(REG_CHARGE_PUMP_FREQ, 0xF0, n << 4);
    }

    /**
     * Read any documented register (reading {@code STATUS} clears it).
     *
     * @param reg register address
     * @return the register value
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the register is reserved
     */
    public int readRegister(int reg) throws IOException {
        check(documented(reg), "register is reserved");
        return r(reg);
    }

    /**
     * Write any documented writable register.
     *
     * @param reg register address
     * @param value byte to write
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if the register is reserved or read-only
     */
    public void writeRegister(int reg, int value) throws IOException {
        check(documented(reg) && reg != 0x01 && reg != 0x2E && reg != 0x30 && reg != 0x31,
                "register is reserved or read-only");
        w(reg, value);
    }
}
