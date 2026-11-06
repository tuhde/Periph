package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * ADS1014 full interface — extends [ADS1014Minimal] with PGA gain, data
 * rate, continuous conversion, the programmable comparator and the Level-2
 * `ALERT/RDY` interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (edge direction follows
 * the configured `COMP_POL`), otherwise a 5 ms polling thread that reports
 * each change of [pollInterrupt].
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
class ADS1014Full(connection: RegisterConnection) : ADS1014Minimal(connection) {

    companion object {
        /** Default I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = ADS1014Minimal.DEFAULT_ADDRESS

        /** Interrupt source: a conversion finished. */
        const val SOURCE_CONVERSION_READY = ADS1014Minimal.SOURCE_CONVERSION_READY

        /** Interrupt source: the result is beyond the comparator thresholds. */
        const val SOURCE_COMPARATOR = ADS1014Minimal.SOURCE_COMPARATOR

        /** PGA full-scale ±6.144 V. */
        const val PGA_6_144 = 0
        /** PGA full-scale ±4.096 V. */
        const val PGA_4_096 = 1
        /** PGA full-scale ±2.048 V (default). */
        const val PGA_2_048 = 2
        /** PGA full-scale ±1.024 V. */
        const val PGA_1_024 = 3
        /** PGA full-scale ±0.512 V. */
        const val PGA_0_512 = 4
        /** PGA full-scale ±0.256 V. */
        const val PGA_0_256 = 5

        /** Data rate 128 SPS. */
        const val DR_128 = 0
        /** Data rate 250 SPS. */
        const val DR_250 = 1
        /** Data rate 490 SPS. */
        const val DR_490 = 2
        /** Data rate 920 SPS. */
        const val DR_920 = 3
        /** Data rate 1600 SPS (default). */
        const val DR_1600 = 4
        /** Data rate 2400 SPS. */
        const val DR_2400 = 5
        /** Data rate 3300 SPS. */
        const val DR_3300 = 6

        /** Traditional comparator. */
        const val COMP_TRADITIONAL = 0
        /** Window comparator. */
        const val COMP_WINDOW = 1
        /** `ALERT/RDY` active low (default). */
        const val ACTIVE_LOW = 0
        /** `ALERT/RDY` active high. */
        const val ACTIVE_HIGH = 1
        /** Assert after 1 out-of-range conversion. */
        const val QUEUE_1 = 0
        /** Assert after 2 consecutive out-of-range conversions. */
        const val QUEUE_2 = 1
        /** Assert after 4 consecutive out-of-range conversions. */
        const val QUEUE_4 = 2

        private const val CONFIG_RESET = 0x8583
        private const val LO_RESET = 0x8000
        private const val HI_RESET = 0x7FFF

        private fun toCode(volts: Double, step: Double): Int =
            Math.round(volts / step).coerceIn(-2048L, 2047L).toInt()
    }

    private var loRaw = -2048
    private var hiRaw = 2047

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling: Boolean = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun writeConfig() = writeReg(REG_CONFIG, configWord(false, continuous))

    // -- Gain and data rate --------------------------------------------------

    /**
     * Select the full-scale range used by all following reads. Thresholds must
     * be rewritten after a gain change.
     *
     * @param pga [PGA_6_144] … [PGA_0_256]
     * @throws IllegalArgumentException if [pga] is outside 0–5 (6 and 7 alias ±0.256 V)
     */
    fun setGain(pga: Int) {
        require(pga in PGA_6_144..PGA_0_256) { "pga must be 0-5" }
        this.pga = pga
        if (continuous) writeConfig()
    }

    /** Read the configured PGA code (0–5). */
    fun getGain(): Int = pga

    /** Full-scale range of the current gain (±V): 6.144, 4.096, 2.048, 1.024, 0.512 or 0.256. */
    fun fullScale(): Double = FULL_SCALE[pga]

    /** Size of one conversion code: `fullScale() / 2048` volts. */
    fun lsb(): Double = lsbVolts()

    /**
     * Select the conversion data rate.
     *
     * @param dr [DR_128] … [DR_3300]
     * @throws IllegalArgumentException if [dr] is outside 0–6 (7 aliases 3300 SPS)
     */
    fun setDataRate(dr: Int) {
        require(dr in DR_128..DR_3300) { "dr must be 0-6" }
        this.dr = dr
        if (continuous) writeConfig()
    }

    /** Read the configured `DR` code (0–6). */
    fun getDataRate(): Int = dr

    /** Current data rate: 128, 250, 490, 920, 1600, 2400 or 3300 samples per second. */
    fun dataRateSps(): Int = SPS[dr]

    // -- Continuous conversion -----------------------------------------------

    /** Start continuous conversion (`MODE` = 0) at the current data rate. */
    fun startContinuous() {
        continuous = true
        writeConfig()
    }

    /**
     * Read the most recent completed result without touching Config (valid at
     * least 1/DR after [startContinuous]).
     *
     * @return 12-bit two's-complement code, −2048…2047
     */
    fun readContinuousRaw(): Int = readConversion()

    /** Read the most recent completed result in volts: `readContinuousRaw() × lsb()`. */
    fun readContinuous(): Double = readConversion() * lsbVolts()

    /** Stop continuous conversion (`MODE` = 1, single-shot / power-down). */
    fun stopContinuous() {
        continuous = false
        writeConfig()
    }

    /**
     * Report whether a single-shot conversion is running (`OS` = 0; only
     * meaningful in single-shot mode).
     */
    fun isConverting(): Boolean = readReg(REG_CONFIG) and CFG_OS == 0

    /** Read the raw 16-bit CONFIG register. */
    fun readConfig(): Int = readReg(REG_CONFIG)

    /**
     * Write the reset values back (`CONFIG` 0x8583, `LO_THRESH` 0x8000,
     * `HI_THRESH` 0x7FFF) and clear the cached settings. Not an I²C
     * general-call reset.
     */
    fun reset() {
        writeReg(REG_CONFIG, CONFIG_RESET)
        writeReg(REG_LO_THRESH, LO_RESET)
        writeReg(REG_HI_THRESH, HI_RESET)
        pga = PGA_2_048
        dr = DR_1600
        continuous = false
        compMode = COMP_TRADITIONAL
        compPol = ACTIVE_LOW
        compLat = false
        queue = QUEUE_1
        source = 0
        loRaw = -2048
        hiRaw = 2047
    }

    // -- Comparator ----------------------------------------------------------

    /**
     * Configure the comparator and enable it as the `ALERT/RDY` source.
     *
     * @param mode [COMP_TRADITIONAL] or [COMP_WINDOW]
     * @param polarity [ACTIVE_LOW] or [ACTIVE_HIGH]
     * @param latch hold the alert until `CONVERSION` is read
     * @param queue [QUEUE_1], [QUEUE_2] or [QUEUE_4] consecutive out-of-range conversions
     * @throws IllegalArgumentException if an argument is out of range
     */
    fun configureComparator(mode: Int, polarity: Int, latch: Boolean, queue: Int) {
        require(mode == COMP_TRADITIONAL || mode == COMP_WINDOW) { "mode must be 0 or 1" }
        require(polarity == ACTIVE_LOW || polarity == ACTIVE_HIGH) { "polarity must be 0 or 1" }
        require(queue in QUEUE_1..QUEUE_4) { "queue must be 0, 1 or 2" }
        compMode = mode
        compPol = polarity
        compLat = latch
        this.queue = queue
        source = SOURCE_COMPARATOR
        writeConfig()
    }

    /** Disable the comparator (`COMP_QUE` = 11); `ALERT/RDY` goes high-impedance. */
    fun disableComparator() {
        source = 0
        writeConfig()
    }

    private fun writeThreshold(reg: Int, raw: Int) {
        require(raw in -2048..2047) { "raw must be -2048..2047" }
        writeReg(reg, (raw and 0x0FFF) shl 4)
    }

    /**
     * Write the comparator low threshold (`LO_THRESH` = `raw << 4`).
     *
     * @param raw 12-bit two's-complement code, −2048…2047
     * @throws IllegalArgumentException if [raw] is out of range
     */
    fun setLowThresholdRaw(raw: Int) {
        writeThreshold(REG_LO_THRESH, raw)
        loRaw = raw
    }

    /**
     * Write the comparator high threshold (`HI_THRESH` = `raw << 4`).
     *
     * @param raw 12-bit two's-complement code, −2048…2047
     * @throws IllegalArgumentException if [raw] is out of range
     */
    fun setHighThresholdRaw(raw: Int) {
        writeThreshold(REG_HI_THRESH, raw)
        hiRaw = raw
    }

    /**
     * Write both comparator thresholds from volts (converted with [lsb],
     * clamped to the code range).
     *
     * @throws IllegalArgumentException if [high] is not greater than [low]
     */
    fun setThresholds(low: Double, high: Double) {
        require(high > low) { "high must be greater than low" }
        val step = lsbVolts()
        setLowThresholdRaw(toCode(low, step))
        setHighThresholdRaw(toCode(high, step))
    }

    /** Read both thresholds back as sign-extended 12-bit codes `{low, high}`. */
    fun getThresholdsRaw(): IntArray {
        val lo = Register.toSigned(readReg(REG_LO_THRESH), 16) shr 4
        val hi = Register.toSigned(readReg(REG_HI_THRESH), 16) shr 4
        return intArrayOf(lo, hi)
    }

    /** Release a latched comparator output by reading `CONVERSION`. */
    fun clearAlert() {
        readReg(REG_CONVERSION)
    }

    // -- Interrupt API (Level 2) ---------------------------------------------

    /**
     * Route one source to `ALERT/RDY`; the other is disabled.
     *
     * [SOURCE_CONVERSION_READY] writes `HI_THRESH` = 0x8000 and `LO_THRESH` =
     * 0x0000 and sets `COMP_QUE` = 00. [SOURCE_COMPARATOR] restores the
     * thresholds last set through the driver and the configured queue.
     *
     * @throws IllegalArgumentException for an unknown source, or for
     *   [SOURCE_COMPARATOR] when the thresholds set through the driver have
     *   `high <= low`
     */
    fun enableInterrupt(source: Int) {
        when (source) {
            SOURCE_CONVERSION_READY -> {
                writeReg(REG_HI_THRESH, 0x8000)
                writeReg(REG_LO_THRESH, 0x0000)
            }
            SOURCE_COMPARATOR -> {
                require(hiRaw > loRaw) { "set thresholds (high > low) before enabling the comparator" }
                writeReg(REG_LO_THRESH, (loRaw and 0x0FFF) shl 4)
                writeReg(REG_HI_THRESH, (hiRaw and 0x0FFF) shl 4)
            }
            else -> throw IllegalArgumentException("unknown interrupt source")
        }
        this.source = source
        writeConfig()
    }

    /**
     * Release `ALERT/RDY` (`COMP_QUE` = 11). The two sources share the pin, so
     * it is released whichever [source] is named.
     */
    @Suppress("UNUSED_PARAMETER")
    fun disableInterrupt(source: Int) {
        this.source = 0
        writeConfig()
    }

    /**
     * Report whether the enabled source is asserted. The chip has no
     * interrupt-status register: with an [InputPin] on the connection the
     * `ALERT/RDY` level is sampled against `COMP_POL` (and a latched
     * comparator alert is cleared by reading `CONVERSION`). Without a pin,
     * [SOURCE_CONVERSION_READY] falls back to `CONFIG.OS` (single-shot only)
     * and [SOURCE_COMPARATOR] returns 0.
     *
     * @return the enabled `SOURCE_*` constant if asserted, else 0
     */
    fun pollInterrupt(): Int {
        if (source == 0) return 0
        val pin = intPin ?: connection.intPin()
        if (pin != null) {
            if (pin.isHigh != (compPol == ACTIVE_HIGH)) return 0
            if (source == SOURCE_COMPARATOR && compLat) clearAlert()
            return source
        }
        if (source == SOURCE_CONVERSION_READY && !continuous && (readReg(REG_CONFIG) and CFG_OS) != 0) {
            return SOURCE_CONVERSION_READY
        }
        return 0
    }

    /**
     * Subscribe to `ALERT/RDY` events. With a pin, the callback runs on every
     * asserting edge — falling for [ACTIVE_LOW], rising for [ACTIVE_HIGH].
     * Without one (default: `connection.intPin()`), a 5 ms polling thread
     * calls it whenever [pollInterrupt] changes. Call [enableInterrupt] first.
     *
     * @param callback called with the [pollInterrupt] value
     * @param intPin `ALERT/RDY` pin to arm, or `null` to force the polling fallback
     */
    @JvmOverloads
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, if (compPol == ACTIVE_HIGH) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            startPolling(pollInterrupt())
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun startPolling(initialStatus: Int) {
        polling = true
        pollThread = Thread({
            var last = initialStatus
            while (polling) {
                try {
                    val status = pollInterrupt()
                    val cb = callback
                    if (status != last && cb != null) cb(status)
                    last = status
                } catch (_: IOException) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "ads1014-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status != 0) callback?.invoke(status)
        } catch (_: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
