package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import kotlin.math.abs

/**
 * MCP9808 full interface — extends [MCP9808Minimal] with resolution control,
 * Shutdown mode, the `TUPPER`/`TLOWER`/`TCRIT` boundaries, hysteresis, the
 * one-way register locks, and the Level-2 Alert/interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (edge direction follows
 * the configured `ALERT_POL`), otherwise a 5 ms polling thread that reports
 * each change of the boundary-status mask.
 *
 * @param connection configured I²C connection bound to the device (0x18–0x1F)
 * @throws IOException on bus error or identity mismatch
 */
class MCP9808Full(connection: Connection) : MCP9808Minimal(connection) {

    /** Which boundaries drive the Alert output (`ALERT_SEL`). */
    enum class AlertMode {
        /** `TUPPER`, `TLOWER` and `TCRIT`. */
        ALL,
        /** `TCRIT` only. */
        CRITICAL_ONLY,
    }

    /** Alert output behavior (`ALERT_MOD`). */
    enum class AlertOutput {
        /** Follows the boundary state. */
        COMPARATOR,
        /** Latches until [clearInterrupt]. */
        INTERRUPT,
    }

    /** Alert output polarity (`ALERT_POL`). */
    enum class AlertPolarity {
        /** Needs an external pull-up (POR default). */
        ACTIVE_LOW,
        /** Driven high when asserted. */
        ACTIVE_HIGH,
    }

    companion object {
        /** Interrupt source: `TA` < `TLOWER`. */
        const val SOURCE_LOWER = 0x01
        /** Interrupt source: `TA` > `TUPPER`. */
        const val SOURCE_UPPER = 0x02
        /** Interrupt source: `TA` ≥ `TCRIT`. */
        const val SOURCE_CRITICAL = 0x04

        // CONFIG (0x01) bits.
        private const val CFG_THYST_SHIFT = 9
        private const val CFG_THYST_MASK = 0x0600
        private const val CFG_SHDN = 0x0100
        private const val CFG_CRIT_LOCK = 0x0080
        private const val CFG_WIN_LOCK = 0x0040
        private const val CFG_INT_CLEAR = 0x0020
        private const val CFG_ALERT_STAT = 0x0010
        private const val CFG_ALERT_CNT = 0x0008
        private const val CFG_ALERT_SEL = 0x0004
        private const val CFG_ALERT_POL = 0x0002
        private const val CFG_ALERT_MOD = 0x0001
        private const val CFG_LOCKS = 0x00C0
        // Writable bits: all but the unimplemented 15:11, the read-only
        // ALERT_STAT, and the self-clearing INT_CLEAR (set only on purpose).
        private const val CFG_WRITE_MASK = 0x07CF

        private val RESOLUTIONS = doubleArrayOf(0.5, 0.25, 0.125, 0.0625)
        private val HYSTERESES = doubleArrayOf(0.0, 1.5, 3.0, 6.0)

        internal fun decodeLimit(raw: Int): Double {
            var value = (raw shr 2) and 0x3FF
            if (raw and 0x1000 != 0) value -= 1024
            return value / 4.0
        }

        /** Round half away from zero, clamp to the 11-bit two's-complement range, place in bits 12:2. */
        internal fun encodeLimit(celsius: Double): Int {
            val quarters = if (celsius >= 0) (celsius * 4 + 0.5).toInt() else -((-celsius * 4 + 0.5).toInt())
            return (quarters.coerceIn(-1024, 1023) and 0x7FF) shl 2
        }

        private fun indexOf(table: DoubleArray, value: Double): Int =
            table.indexOfFirst { abs(value - it) < 1e-6 }
    }

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling: Boolean = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun readConfig(): Int = readReg(REG_CONFIG) and CFG_WRITE_MASK

    private fun writeConfig(value: Int) = writeReg(REG_CONFIG, value and CFG_WRITE_MASK)

    // -- Resolution ----------------------------------------------------------

    /**
     * Set the measurement resolution. Finer steps take longer to convert:
     * 0.5 °C = 30 ms, 0.25 °C = 65 ms, 0.125 °C = 130 ms, 0.0625 °C = 250 ms.
     *
     * @param celsius one of 0.5, 0.25, 0.125, 0.0625
     * @throws IllegalArgumentException if [celsius] is not a supported step
     */
    fun setResolution(celsius: Double) {
        val code = indexOf(RESOLUTIONS, celsius)
        require(code >= 0) { "resolution must be one of 0.5, 0.25, 0.125, 0.0625" }
        connection.write(byteArrayOf(REG_RESOLUTION.toByte(), code.toByte()))
    }

    /**
     * Read the measurement resolution.
     *
     * @return resolution step in °C
     */
    fun getResolution(): Double =
        RESOLUTIONS[connection.writeRead(byteArrayOf(REG_RESOLUTION.toByte()), 1)[0].toInt() and 0x03]

    // -- Shutdown ------------------------------------------------------------

    /**
     * Enter Shutdown (low-power) mode; `TA` holds its last value. No-op while
     * either lock bit is set (the chip ignores `SHDN`=1 then).
     */
    fun shutdown() {
        val config = readConfig()
        if (config and CFG_LOCKS != 0) return
        writeConfig(config or CFG_SHDN)
    }

    /** Leave Shutdown mode and resume continuous conversion. */
    fun wake() = writeConfig(readConfig() and CFG_SHDN.inv())

    /**
     * Report whether the sensor is in Shutdown mode.
     *
     * @return `true` if `SHDN` is set
     */
    fun isShutdown(): Boolean = readReg(REG_CONFIG) and CFG_SHDN != 0

    // -- Boundaries ----------------------------------------------------------

    /**
     * Read the `TUPPER` boundary.
     *
     * @return upper boundary in °C (0.25 °C steps)
     */
    fun getUpperLimit(): Double = decodeLimit(readReg(REG_TUPPER))

    /**
     * Write the `TUPPER` boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while `WIN_LOCK` is set).
     *
     * @param celsius upper boundary in °C (−256.0 to 255.75, clamped)
     */
    fun setUpperLimit(celsius: Double) = writeReg(REG_TUPPER, encodeLimit(celsius))

    /**
     * Read the `TLOWER` boundary.
     *
     * @return lower boundary in °C (0.25 °C steps)
     */
    fun getLowerLimit(): Double = decodeLimit(readReg(REG_TLOWER))

    /**
     * Write the `TLOWER` boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while `WIN_LOCK` is set).
     *
     * @param celsius lower boundary in °C (−256.0 to 255.75, clamped)
     */
    fun setLowerLimit(celsius: Double) = writeReg(REG_TLOWER, encodeLimit(celsius))

    /**
     * Read the `TCRIT` boundary.
     *
     * @return critical boundary in °C (0.25 °C steps)
     */
    fun getCriticalLimit(): Double = decodeLimit(readReg(REG_TCRIT))

    /**
     * Write the `TCRIT` boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while `CRIT_LOCK` is set).
     *
     * @param celsius critical boundary in °C (−256.0 to 255.75, clamped)
     */
    fun setCriticalLimit(celsius: Double) = writeReg(REG_TCRIT, encodeLimit(celsius))

    // -- Hysteresis ----------------------------------------------------------

    /**
     * Set the boundary hysteresis (applies to the cooling edge only; ignored by
     * the chip while either lock bit is set).
     *
     * @param celsius one of 0, 1.5, 3.0, 6.0
     * @throws IllegalArgumentException if [celsius] is not a supported value
     */
    fun setHysteresis(celsius: Double) {
        val code = indexOf(HYSTERESES, celsius)
        require(code >= 0) { "hysteresis must be one of 0, 1.5, 3.0, 6.0" }
        writeConfig((readConfig() and CFG_THYST_MASK.inv()) or (code shl CFG_THYST_SHIFT))
    }

    /**
     * Read the boundary hysteresis.
     *
     * @return hysteresis in °C
     */
    fun getHysteresis(): Double = HYSTERESES[(readReg(REG_CONFIG) and CFG_THYST_MASK) shr CFG_THYST_SHIFT]

    // -- Locks ---------------------------------------------------------------

    /** Lock `TCRIT` (and `ALERT_SEL`/`POL`/`MOD`). Irreversible except by power-on reset. */
    fun lockCriticalLimit() = writeConfig(readConfig() or CFG_CRIT_LOCK)

    /** Lock `TUPPER`/`TLOWER` (and `ALERT_SEL`/`POL`/`MOD`). Irreversible except by power-on reset. */
    fun lockWindowLimits() = writeConfig(readConfig() or CFG_WIN_LOCK)

    /**
     * Report whether `CRIT_LOCK` is set.
     *
     * @return `true` if `TCRIT` is locked
     */
    fun isCriticalLimitLocked(): Boolean = readReg(REG_CONFIG) and CFG_CRIT_LOCK != 0

    /**
     * Report whether `WIN_LOCK` is set.
     *
     * @return `true` if `TUPPER`/`TLOWER` are locked
     */
    fun isWindowLimitsLocked(): Boolean = readReg(REG_CONFIG) and CFG_WIN_LOCK != 0

    // -- Alert output --------------------------------------------------------

    /**
     * Configure the Alert output's source, mode and polarity together.
     *
     * @param mode boundaries that drive the Alert output
     * @param output comparator or latching interrupt output
     * @param polarity active-low or active-high
     * @throws IllegalStateException if either lock bit is set (the bits are frozen until power-on reset)
     */
    @JvmOverloads
    fun configureAlert(
        mode: AlertMode = AlertMode.ALL,
        output: AlertOutput = AlertOutput.COMPARATOR,
        polarity: AlertPolarity = AlertPolarity.ACTIVE_LOW,
    ) {
        var config = readConfig()
        check(config and CFG_LOCKS == 0) { "MCP9808: Alert configuration is locked until power-on reset" }
        config = config and (CFG_ALERT_SEL or CFG_ALERT_POL or CFG_ALERT_MOD).inv()
        if (mode == AlertMode.CRITICAL_ONLY) config = config or CFG_ALERT_SEL
        if (polarity == AlertPolarity.ACTIVE_HIGH) config = config or CFG_ALERT_POL
        if (output == AlertOutput.INTERRUPT) config = config or CFG_ALERT_MOD
        writeConfig(config)
    }

    /** Enable the Alert output (`ALERT_CNT` = 1). */
    fun enableAlert() = writeConfig(readConfig() or CFG_ALERT_CNT)

    /** Disable the Alert output (`ALERT_CNT` = 0). */
    fun disableAlert() = writeConfig(readConfig() and CFG_ALERT_CNT.inv())

    /**
     * Report whether the Alert output is currently asserted.
     *
     * @return `true` if `ALERT_STAT` is set
     */
    fun isAlertAsserted(): Boolean = readReg(REG_CONFIG) and CFG_ALERT_STAT != 0

    /** Clear an asserted interrupt-mode Alert output (`INT_CLEAR` = 1). No effect in comparator mode. */
    fun clearInterrupt() = writeReg(REG_CONFIG, readConfig() or CFG_INT_CLEAR)

    // -- Interrupt API (Level 2) --------------------------------------------

    /**
     * Read `TA`'s live boundary-status bits. Nothing is cleared — the bits are
     * a live comparison, always current.
     *
     * @return mask of [SOURCE_LOWER] / [SOURCE_UPPER] / [SOURCE_CRITICAL]
     */
    fun pollInterrupt(): Int = (readReg(REG_TA) shr 13) and 0x07

    /**
     * Subscribe to Alert events. With a pin, the callback runs on every Alert
     * edge — falling for active-low, rising for active-high, following the
     * configured `ALERT_POL`. Without one (default: `connection.intPin()`), a
     * 5 ms polling thread calls it whenever the status mask changes. The
     * Alert output must be enabled ([enableAlert]) for a pin to see edges.
     *
     * @param callback called with the [pollInterrupt] mask
     * @param intPin Alert pin to arm, or `null` to force the polling fallback
     */
    @JvmOverloads
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            val activeHigh = readReg(REG_CONFIG) and CFG_ALERT_POL != 0
            this.intPin = intPin
            intPin.onEdge(edgeHandler, if (activeHigh) EdgeTrigger.RISING else EdgeTrigger.FALLING)
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
        }, "mcp9808-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            callback?.invoke(status)
        } catch (_: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
