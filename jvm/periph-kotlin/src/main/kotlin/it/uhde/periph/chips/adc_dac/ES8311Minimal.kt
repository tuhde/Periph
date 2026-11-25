package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import kotlin.math.abs
import kotlin.math.floor

/** Guide clock table rows: MCLK Hz, fs Hz, then registers 0x02, 0x03, 0x04, 0x05. */
private val ES8311_PRESETS = arrayOf(
    longArrayOf(24576000, 32000, 0x40, 0x10, 0x10, 0x00),
    longArrayOf(24576000, 48000, 0x20, 0x10, 0x10, 0x00),
    longArrayOf(12288000, 32000, 0x48, 0x10, 0x10, 0x00),
    longArrayOf(12288000, 48000, 0x00, 0x10, 0x10, 0x00),
    longArrayOf(18432000, 32000, 0x20, 0x12, 0x12, 0x00),
    longArrayOf(18432000, 48000, 0x48, 0x10, 0x10, 0x00),
    longArrayOf(11289600, 44100, 0x00, 0x10, 0x10, 0x00),
    longArrayOf(12000000, 32000, 0x00, 0x17, 0x17, 0x00),
    longArrayOf(12000000, 44100, 0x00, 0x11, 0x11, 0x00),
    longArrayOf(12000000, 48000, 0x98, 0x19, 0x19, 0x00),
    longArrayOf(26000000, 32000, 0x40, 0x10, 0x10, 0x00),
    longArrayOf(3000000, 32000, 0x18, 0x52, 0x17, 0x41),
    longArrayOf(25000000, 32000, 0x48, 0x55, 0x10, 0x21),
    longArrayOf(25000000, 48000, 0x28, 0x55, 0x10, 0x21),
)
private val ES8311_PREFERRED_RATIOS = longArrayOf(256, 384, 512, 768, 1024, 1536)

/**
 * Solve the clock registers 0x02–0x05 for an MCLK / fs pair: the guide's preset table first
 * (3.3 V only), then a search over pre-divider, multiplier and ADC/DAC divider for an allowed
 * MCLK:LRCK ratio.
 *
 * @return intArrayOf(0x02, 0x03, 0x04, 0x05).
 * @throws IllegalArgumentException if no valid divider set exists.
 */
internal fun es8311SolveClock(mclkHz: Long, fsHz: Long, dvddVolts: Double): IntArray {
    require(fsHz > 0) { "sample rate must be positive" }
    val low = dvddVolts < 2.5
    if (!low) {
        for (p in ES8311_PRESETS) {
            if (p[0] == mclkHz && p[1] == fsHz) {
                return intArrayOf(p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt())
            }
        }
    }
    val preLimit = if (low) 4_500_000L else 10_000_000L
    val clkLimit = if (low) 13_000_000L else 35_000_000L
    var bestKey: LongArray? = null
    var bestR02 = 0
    var bestOsr = 0
    var bestDiv = 0
    val mults = longArrayOf(1, 2, 4, 8)
    for (dp in 1L..8L) {
        if (mclkHz >= preLimit * dp) continue
        for (mi in 0 until 4) {
            val mult = mults[mi]
            for (div in 0L until 16L) {
                val den = dp * (div + 1)
                if (mclkHz * mult >= clkLimit * den) continue
                if ((mclkHz * mult) % (den * fsHz) != 0L) continue
                val ratio = mclkHz * mult / (den * fsHz)
                if (ratio % 16 != 0L || ratio < 256 || ratio > 1008) continue
                var rank = 99L
                for (i in ES8311_PREFERRED_RATIOS.indices) if (ES8311_PREFERRED_RATIOS[i] == ratio) rank = i.toLong()
                val key = longArrayOf(rank, dp, mult, div)
                if (bestKey == null || es8311KeyLess(key, bestKey)) {
                    bestKey = key
                    bestR02 = (((dp - 1) shl 5) or (mi.toLong() shl 3)).toInt()
                    bestOsr = (ratio / 16).toInt()
                    bestDiv = div.toInt()
                }
            }
        }
    }
    requireNotNull(bestKey) { "no valid ES8311 clock divider for MCLK $mclkHz Hz / fs $fsHz Hz" }
    return intArrayOf(bestR02, bestOsr, bestOsr, (bestDiv shl 4) or bestDiv)
}

private fun es8311KeyLess(a: LongArray, b: LongArray): Boolean {
    for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i]
    return false
}

/**
 * ES8311 — low-power mono audio codec, I²C control port (Everest Semiconductor), minimal driver.
 *
 * Brings the codec up for voice playback and recording and lets the application set output
 * volume, mute and microphone gain. The audio stream itself runs over a separate I²S port that
 * this library does not provide.
 *
 * Construction runs the initialization sequence: software reset, clock tree for the given MCLK /
 * sample rate, I²S 16-bit slave, analog path, analog microphone at 24 dB, ADC 0 dB, DAC volume
 * −95.5 dB (silent until [setVolume]). Default I²C address 0x18 (CE low) or 0x19 (CE high).
 * The registers 0x04, 0x0B–0x0C and 0x10–0x13 are undocumented in the user guide; the vendor
 * reference-driver values are used — verify on hardware.
 *
 * @param connection I²C register connection bound to 0x18 / 0x19.
 * @param mclkHz master clock in Hz.
 * @param sampleRateHz audio sample rate in Hz.
 * @param dvddVolts DVDD supply, selects the 3.3 V or 1.8 V clock limits.
 * @throws IllegalArgumentException if the MCLK : sample-rate pair has no valid divider set.
 */
open class ES8311Minimal @JvmOverloads constructor(
    protected val connection: RegisterConnection,
    mclkHz: Long = 12288000,
    sampleRateHz: Long = 48000,
    protected val dvdd: Double = 3.3
) {
    companion object {
        const val REG_RESET = 0x00
        const val REG_CLK1 = 0x01
        const val REG_CLK2 = 0x02
        const val REG_CLK3 = 0x03
        const val REG_CLK4 = 0x04
        const val REG_CLK5 = 0x05
        const val REG_CLK6 = 0x06
        const val REG_CLK7 = 0x07
        const val REG_CLK8 = 0x08
        const val REG_SDP_IN = 0x09
        const val REG_SDP_OUT = 0x0A
        const val REG_SYS_PWR = 0x0D
        const val REG_SYS_ADC = 0x0E
        const val REG_SYS_LP = 0x0F
        const val REG_SYS_MIC = 0x14
        const val REG_ADC_RAMP = 0x15
        const val REG_ADC_SCALE = 0x16
        const val REG_ADC_VOLUME = 0x17
        const val REG_ADC_ALC1 = 0x18
        const val REG_ADC_ALC2 = 0x19
        const val REG_ADC_AUTOMUTE = 0x1A
        const val REG_ADC_HPF1 = 0x1B
        const val REG_ADC_HPF2_EQ = 0x1C
        const val REG_ADCEQ = 0x1D
        const val REG_DAC_MUTE = 0x31
        const val REG_DAC_VOLUME = 0x32
        const val REG_DAC_DRC1 = 0x34
        const val REG_DAC_DRC2 = 0x35
        const val REG_DAC_RAMP_EQ = 0x37
        const val REG_DACEQ = 0x38
        const val REG_GPIO = 0x44

        /** ALC / DRC target levels in dB, indexed by the 4-bit register code. */
        val ALC_LEVELS_DB = doubleArrayOf(-30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
            -11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0)

        /** Noise-gate thresholds in dB, indexed by the 4-bit register code. */
        val NOISE_GATE_DB = doubleArrayOf(-96.0, -90.0, -84.0, -78.0, -72.0, -66.0, -60.0, -54.0,
            -51.0, -48.0, -45.0, -42.0, -39.0, -36.0, -33.0, -30.0)
    }

    protected var mclkHz: Long = mclkHz
    protected var fs: Long = sampleRateHz

    init {
        val clk = es8311SolveClock(mclkHz, sampleRateHz, dvdd)
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

    protected fun w(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    protected fun r(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun update(reg: Int, mask: Int, value: Int) {
        w(reg, (r(reg) and mask.inv() and 0xFF) or (value and mask))
    }

    protected fun softReset() {
        w(REG_RESET, 0x1F)
        Thread.sleep(20)
        w(REG_RESET, 0x00)
        w(REG_RESET, 0x80)
    }

    protected fun writeClock(clk: IntArray) {
        w(REG_CLK2, clk[0])
        w(REG_CLK3, clk[1])
        w(REG_CLK4, clk[2])
        w(REG_CLK5, clk[3])
    }

    protected fun powerUpAnalog() {
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
    protected fun round(x: Double): Int = (if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)).toInt()

    protected fun nearest(table: DoubleArray, value: Double): Int {
        var best = 0
        for (i in 1 until table.size) if (abs(table[i] - value) < abs(table[best] - value)) best = i
        return best
    }

    protected fun volumeToReg(db: Double): Int = (round(db / 0.5) + 191).coerceIn(0, 255)

    /**
     * Set the DAC digital volume.
     *
     * @param db −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
     */
    fun setVolume(db: Double) {
        w(REG_DAC_VOLUME, volumeToReg(db))
    }

    /**
     * Mute or unmute the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).
     *
     * @param muted true to mute.
     */
    fun setMute(muted: Boolean) {
        update(REG_DAC_MUTE, 0x60, if (muted) 0x60 else 0x00)
    }

    /**
     * Set the analog microphone PGA gain.
     *
     * @param db 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
     */
    fun setMicGain(db: Double) {
        update(REG_SYS_MIC, 0x0F, round(db / 3.0).coerceIn(0, 10))
    }
}
