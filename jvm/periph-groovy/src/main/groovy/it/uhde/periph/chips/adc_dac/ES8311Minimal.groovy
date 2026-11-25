package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * ES8311 — low-power mono audio codec, I²C control port (Everest Semiconductor), minimal driver.
 *
 * <p>Brings the codec up for voice playback and recording and lets the application set output
 * volume, mute and microphone gain. The audio stream itself runs over a separate I²S port that
 * this library does not provide.
 *
 * <p>Construction runs the initialization sequence: software reset, clock tree for the given MCLK /
 * sample rate, I²S 16-bit slave, analog path, analog microphone at 24 dB, ADC 0 dB, DAC volume
 * −95.5 dB (silent until {@link #setVolume}). Default I²C address 0x18 (CE low) or 0x19 (CE high).
 * The registers 0x04, 0x0B–0x0C and 0x10–0x13 are undocumented in the user guide; the vendor
 * reference-driver values are used — verify on hardware.
 */
@CompileStatic
class ES8311Minimal {

    protected static final int REG_RESET = 0x00
    protected static final int REG_CLK1 = 0x01
    protected static final int REG_CLK2 = 0x02
    protected static final int REG_CLK3 = 0x03
    protected static final int REG_CLK4 = 0x04
    protected static final int REG_CLK5 = 0x05
    protected static final int REG_CLK6 = 0x06
    protected static final int REG_CLK7 = 0x07
    protected static final int REG_CLK8 = 0x08
    protected static final int REG_SDP_IN = 0x09
    protected static final int REG_SDP_OUT = 0x0A
    protected static final int REG_SYS_PWR = 0x0D
    protected static final int REG_SYS_ADC = 0x0E
    protected static final int REG_SYS_LP = 0x0F
    protected static final int REG_SYS_MIC = 0x14
    protected static final int REG_ADC_RAMP = 0x15
    protected static final int REG_ADC_SCALE = 0x16
    protected static final int REG_ADC_VOLUME = 0x17
    protected static final int REG_ADC_ALC1 = 0x18
    protected static final int REG_ADC_ALC2 = 0x19
    protected static final int REG_ADC_AUTOMUTE = 0x1A
    protected static final int REG_ADC_HPF1 = 0x1B
    protected static final int REG_ADC_HPF2_EQ = 0x1C
    protected static final int REG_ADCEQ = 0x1D
    protected static final int REG_DAC_MUTE = 0x31
    protected static final int REG_DAC_VOLUME = 0x32
    protected static final int REG_DAC_DRC1 = 0x34
    protected static final int REG_DAC_DRC2 = 0x35
    protected static final int REG_DAC_RAMP_EQ = 0x37
    protected static final int REG_DACEQ = 0x38
    protected static final int REG_GPIO = 0x44

    /** Guide clock table rows: {MCLK Hz, fs Hz, 0x02, 0x03, 0x04, 0x05}. */
    private static final long[][] PRESETS = [
        [24576000L, 32000L, 0x40L, 0x10L, 0x10L, 0x00L] as long[],
        [24576000L, 48000L, 0x20L, 0x10L, 0x10L, 0x00L] as long[],
        [12288000L, 32000L, 0x48L, 0x10L, 0x10L, 0x00L] as long[],
        [12288000L, 48000L, 0x00L, 0x10L, 0x10L, 0x00L] as long[],
        [18432000L, 32000L, 0x20L, 0x12L, 0x12L, 0x00L] as long[],
        [18432000L, 48000L, 0x48L, 0x10L, 0x10L, 0x00L] as long[],
        [11289600L, 44100L, 0x00L, 0x10L, 0x10L, 0x00L] as long[],
        [12000000L, 32000L, 0x00L, 0x17L, 0x17L, 0x00L] as long[],
        [12000000L, 44100L, 0x00L, 0x11L, 0x11L, 0x00L] as long[],
        [12000000L, 48000L, 0x98L, 0x19L, 0x19L, 0x00L] as long[],
        [26000000L, 32000L, 0x40L, 0x10L, 0x10L, 0x00L] as long[],
        [3000000L, 32000L, 0x18L, 0x52L, 0x17L, 0x41L] as long[],
        [25000000L, 32000L, 0x48L, 0x55L, 0x10L, 0x21L] as long[],
        [25000000L, 48000L, 0x28L, 0x55L, 0x10L, 0x21L] as long[],
    ] as long[][]
    private static final long[] PREFERRED_RATIOS = [256L, 384L, 512L, 768L, 1024L, 1536L] as long[]

    /** ALC / DRC target levels in dB, indexed by the 4-bit register code. */
    static final double[] ALC_LEVELS_DB = [-30.1d, -24.1d, -20.6d, -18.1d, -16.1d, -14.5d, -13.2d, -12.0d,
            -11.0d, -10.1d, -9.3d, -8.5d, -7.8d, -7.2d, -6.6d, -6.0d] as double[]
    /** Noise-gate thresholds in dB, indexed by the 4-bit register code. */
    static final double[] NOISE_GATE_DB = [-96d, -90d, -84d, -78d, -72d, -66d, -60d, -54d,
            -51d, -48d, -45d, -42d, -39d, -36d, -33d, -30d] as double[]

    protected final RegisterConnection connection
    protected long mclkHz
    protected long fs
    protected final double dvdd

    /**
     * Create the driver and run the initialization sequence (MCLK 12.288 MHz, 48 kHz, 3.3 V limits).
     *
     * @param connection I²C register connection bound to 0x18 / 0x19.
     */
    ES8311Minimal(RegisterConnection connection) {
        this(connection, 12288000L, 48000L, 3.3d)
    }

    /**
     * Create the driver and run the initialization sequence (3.3 V clock limits).
     *
     * @param connection I²C register connection bound to 0x18 / 0x19.
     * @param mclkHz master clock in Hz.
     * @param sampleRateHz audio sample rate in Hz.
     */
    ES8311Minimal(RegisterConnection connection, long mclkHz, long sampleRateHz) {
        this(connection, mclkHz, sampleRateHz, 3.3d)
    }

    /**
     * Create the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to 0x18 / 0x19.
     * @param mclkHz master clock in Hz.
     * @param sampleRateHz audio sample rate in Hz.
     * @param dvddVolts DVDD supply, selects the 3.3 V or 1.8 V clock limits.
     * @throws IllegalArgumentException if the MCLK : sample-rate pair has no valid divider set.
     */
    ES8311Minimal(RegisterConnection connection, long mclkHz, long sampleRateHz, double dvddVolts) {
        this.connection = connection
        this.mclkHz = mclkHz
        this.fs = sampleRateHz
        this.dvdd = dvddVolts
        int[] clk = solveClock(mclkHz, sampleRateHz, dvddVolts)
        softReset()
        w(REG_CLK1, 0x3F)
        writeClock(clk)
        w(REG_CLK6, 0x03)
        w(REG_CLK7, 0x00)
        w(REG_CLK8, 0xFF)
        w(REG_SDP_IN, 0x0C)
        w(REG_SDP_OUT, 0x0C)
        powerUpAnalog()
        w(REG_ADC_SCALE, 0x04)
        w(REG_SYS_MIC, 0x18)
        w(REG_ADC_VOLUME, 0xBF)
        w(REG_DAC_VOLUME, 0x00)
    }

    protected void w(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected int r(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void update(int reg, int mask, int value) {
        w(reg, (r(reg) & ~mask & 0xFF) | (value & mask))
    }

    protected void softReset() {
        w(REG_RESET, 0x1F)
        Thread.sleep(20)
        w(REG_RESET, 0x00)
        w(REG_RESET, 0x80)
    }

    protected void writeClock(int[] clk) {
        w(REG_CLK2, clk[0])
        w(REG_CLK3, clk[1])
        w(REG_CLK4, clk[2])
        w(REG_CLK5, clk[3])
    }

    protected void powerUpAnalog() {
        w(0x0B, 0x00)
        w(0x0C, 0x00)
        w(0x10, 0x1F)
        w(0x11, 0x7F)
        w(REG_SYS_PWR, 0x01)
        w(REG_SYS_ADC, 0x02)
        w(0x12, 0x00)
        w(0x13, 0x10)
        w(REG_ADC_HPF2_EQ, 0x6A)
        w(REG_DAC_RAMP_EQ, 0x08)
    }

    /** Round half away from zero (matches the C++ / Go / Rust ports). */
    protected static int round(double x) {
        return (int) (x < 0 ? -Math.floor(-x + 0.5d) : Math.floor(x + 0.5d))
    }

    protected static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v))
    }

    protected static int nearest(double[] table, double value) {
        int best = 0
        for (int i = 1; i < table.length; i++) {
            if (Math.abs(table[i] - value) < Math.abs(table[best] - value)) best = i
        }
        return best
    }

    protected static int volumeToReg(double db) {
        return clamp(round(db / 0.5d) + 191, 0, 255)
    }

    /**
     * Solve the clock registers 0x02–0x05 for an MCLK / fs pair: the guide's preset table first
     * (3.3 V only), then a search over pre-divider, multiplier and ADC/DAC divider for an allowed
     * MCLK:LRCK ratio.
     *
     * @return {0x02, 0x03, 0x04, 0x05}.
     * @throws IllegalArgumentException if no valid divider set exists.
     */
    protected static int[] solveClock(long mclkHz, long fsHz, double dvddVolts) {
        if (fsHz <= 0L) throw new IllegalArgumentException("sample rate must be positive")
        boolean low = dvddVolts < 2.5d
        if (!low) {
            for (long[] p : PRESETS) {
                if (p[0] == mclkHz && p[1] == fsHz) {
                    return [(int) p[2], (int) p[3], (int) p[4], (int) p[5]] as int[]
                }
            }
        }
        long preLimit = low ? 4500000L : 10000000L
        long clkLimit = low ? 13000000L : 35000000L
        long[] bestKey = null
        int bestR02 = 0
        int bestOsr = 0
        int bestDiv = 0
        long[] mults = [1L, 2L, 4L, 8L] as long[]
        for (long dp = 1L; dp <= 8L; dp++) {
            if (mclkHz >= preLimit * dp) continue
            for (int mi = 0; mi < 4; mi++) {
                long mult = mults[mi]
                for (long div = 0L; div < 16L; div++) {
                    long den = dp * (div + 1L)
                    if (mclkHz * mult >= clkLimit * den) continue
                    if ((mclkHz * mult) % (den * fsHz) != 0L) continue
                    long ratio = (long) (mclkHz * mult / (den * fsHz))
                    if (ratio % 16L != 0L || ratio < 256L || ratio > 1008L) continue
                    long rank = 99L
                    for (int i = 0; i < PREFERRED_RATIOS.length; i++) if (PREFERRED_RATIOS[i] == ratio) rank = (long) i
                    long[] key = [rank, dp, mult, div] as long[]
                    if (bestKey == null || keyLess(key, bestKey)) {
                        bestKey = key
                        bestR02 = (int) (((dp - 1L) << 5) | ((long) mi << 3))
                        bestOsr = (int) (ratio / 16L)
                        bestDiv = (int) div
                    }
                }
            }
        }
        if (bestKey == null) {
            throw new IllegalArgumentException("no valid ES8311 clock divider for MCLK " + mclkHz + " Hz / fs " + fsHz + " Hz")
        }
        return [bestR02, bestOsr, bestOsr, (bestDiv << 4) | bestDiv] as int[]
    }

    private static boolean keyLess(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    /**
     * Set the DAC digital volume.
     *
     * @param db −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
     */
    void setVolume(double db) {
        w(REG_DAC_VOLUME, volumeToReg(db))
    }

    /**
     * Mute or unmute the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).
     *
     * @param muted true to mute.
     */
    void setMute(boolean muted) {
        update(REG_DAC_MUTE, 0x60, muted ? 0x60 : 0x00)
    }

    /**
     * Set the analog microphone PGA gain.
     *
     * @param db 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
     */
    void setMicGain(double db) {
        update(REG_SYS_MIC, 0x0F, clamp(round(db / 3.0d), 0, 10))
    }
}
