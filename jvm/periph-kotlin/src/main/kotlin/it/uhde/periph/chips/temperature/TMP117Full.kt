package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import kotlin.math.abs

/**
 * TMP117 full interface — extends [TMP117Minimal] with conversion mode,
 * averaging and cycle-time control, one-shot triggering, both temperature
 * limits, the calibration offset, soft reset, EEPROM persistence and scratch
 * storage, and the Level-2 Alert/interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (edge direction follows
 * the configured `POL`), otherwise a 5 ms polling thread that reports each
 * change of the alert-flag mask.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 * @throws IOException on bus error or identity mismatch
 */
class TMP117Full(connection: Connection) : TMP117Minimal(connection) {

    /** Conversion mode (`MOD[1:0]`). */
    enum class Mode(internal val bits: Int) {
        /** Continuous conversion (POR default). */
        CONTINUOUS(0),
        /** No conversions; `TEMP_RESULT` holds its last value. */
        SHUTDOWN(1),
        /** One conversion, then Shutdown. */
        ONE_SHOT(3),
    }

    /** `ALERT` behavior (`T/nA`). */
    enum class AlertMode {
        /** Window alert: `HIGH_Alert` and `LOW_Alert` (POR default). */
        ALERT,
        /** Latching thermostat; `TLOW_LIMIT` is the reset threshold. */
        THERM,
    }

    /** `ALERT` pin polarity (`POL`). */
    enum class AlertPolarity {
        /** Needs an external pull-up (POR default). */
        ACTIVE_LOW,
        /** Driven high when asserted. */
        ACTIVE_HIGH,
    }

    /** `ALERT` pin function (`DR/Alert`). */
    enum class AlertPinFunction {
        /** Reflects the alert/Therm status (POR default). */
        ALERT,
        /** Reflects `Data_Ready`. */
        DATA_READY,
    }

    /**
     * Decoded conversion configuration, as returned by [getConfig].
     *
     * @property mode conversion mode
     * @property averaging conversions averaged per result: 0, 8, 32 or 64
     * @property cycleSeconds `CONV[2:0]` cycle time in s (no-averaging column)
     */
    data class Config(val mode: Mode, val averaging: Int, val cycleSeconds: Double)

    companion object {
        /** Interrupt source: result > `THIGH_LIMIT` (`HIGH_Alert`). */
        const val SOURCE_HIGH = 0x01
        /** Interrupt source: result < `TLOW_LIMIT` (`LOW_Alert`; Alert mode only). */
        const val SOURCE_LOW = 0x02

        // CONFIGURATION (0x01) bits.
        private const val CFG_HIGH_ALERT = 0x8000
        private const val CFG_LOW_ALERT = 0x4000
        private const val CFG_DATA_READY = 0x2000
        private const val CFG_MOD_SHIFT = 10
        private const val CFG_MOD_MASK = 0x0C00
        private const val CFG_CONV_SHIFT = 7
        private const val CFG_CONV_MASK = 0x0380
        private const val CFG_AVG_SHIFT = 5
        private const val CFG_AVG_MASK = 0x0060
        private const val CFG_TNA = 0x0010
        private const val CFG_POL = 0x0008
        private const val CFG_DR_ALERT = 0x0004
        private const val CFG_SOFT_RESET = 0x0002
        // Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
        private const val CFG_WRITE_MASK = 0x0FFC

        // EEPROM_UL (0x04) bits.
        private const val EUN = 0x8000
        private const val EEPROM_BUSY = 0x4000

        // Conversion cycle times in s, indexed by CONV[2:0] (no-averaging column).
        private val CYCLES = doubleArrayOf(0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0)
        // Averaging counts, indexed by AVG[1:0].
        private val AVERAGINGS = intArrayOf(0, 8, 32, 64)

        /** Round half away from zero, clamp to the 16-bit two's-complement range. */
        internal fun encodeTemperature(celsius: Double): Int {
            val steps = celsius / LSB_C
            val value = if (steps >= 0) (steps + 0.5).toLong() else -((-steps + 0.5).toLong())
            return value.coerceIn(-32768, 32767).toInt() and 0xFFFF
        }
    }

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling: Boolean = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun readConfig(): Int = readReg(REG_CONFIG) and CFG_WRITE_MASK

    private fun writeConfig(value: Int) = writeReg(REG_CONFIG, value and CFG_WRITE_MASK)

    // -- Conversion -----------------------------------------------------------

    /**
     * Set conversion mode, averaging and cycle time. The cycle time is matched
     * to the nearest `CONV[2:0]` step from the no-averaging column (15.5 ms,
     * 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s, 16 s); at higher averaging the
     * hardware lengthens short cycles automatically. The Alert configuration
     * bits are preserved.
     *
     * @param mode conversion mode
     * @param averaging conversions averaged per result: 0, 8, 32 or 64
     * @param cycleSeconds desired conversion cycle time in s
     * @throws IllegalArgumentException if [averaging] is not 0, 8, 32 or 64
     */
    fun configure(mode: Mode = Mode.CONTINUOUS, averaging: Int = 8, cycleSeconds: Double = 1.0) {
        val avg = AVERAGINGS.indexOf(averaging)
        require(avg >= 0) { "averaging must be one of 0, 8, 32, 64" }
        var conv = 0
        for (code in CYCLES.indices) {
            if (abs(CYCLES[code] - cycleSeconds) < abs(CYCLES[conv] - cycleSeconds)) conv = code
        }
        var config = readConfig() and (CFG_MOD_MASK or CFG_CONV_MASK or CFG_AVG_MASK).inv()
        config = config or (mode.bits shl CFG_MOD_SHIFT) or (conv shl CFG_CONV_SHIFT) or (avg shl CFG_AVG_SHIFT)
        writeConfig(config)
    }

    /**
     * Read the conversion mode, averaging and cycle time.
     *
     * @return decoded configuration; `cycleSeconds` is the no-averaging `CONV` step
     */
    fun getConfig(): Config {
        val config = readReg(REG_CONFIG)
        // MOD = 10 reads back as continuous conversion.
        val mode = when ((config and CFG_MOD_MASK) shr CFG_MOD_SHIFT) {
            1 -> Mode.SHUTDOWN
            3 -> Mode.ONE_SHOT
            else -> Mode.CONTINUOUS
        }
        return Config(mode, AVERAGINGS[(config and CFG_AVG_MASK) shr CFG_AVG_SHIFT],
            CYCLES[(config and CFG_CONV_MASK) shr CFG_CONV_SHIFT])
    }

    /**
     * Report whether the sensor is in Shutdown mode.
     *
     * @return `true` if `MOD[1:0]` is Shutdown
     */
    fun isShutdown(): Boolean = (readReg(REG_CONFIG) and CFG_MOD_MASK) shr CFG_MOD_SHIFT == 1

    /**
     * Start a single conversion (`MOD[1:0]` = One-Shot); the sensor returns to
     * Shutdown once the conversion (including averaging) completes.
     */
    fun triggerOneShot() {
        writeConfig((readConfig() and CFG_MOD_MASK.inv()) or (Mode.ONE_SHOT.bits shl CFG_MOD_SHIFT))
    }

    /**
     * Report whether a fresh conversion result is available. Reading this flag
     * clears it (as does reading `TEMP_RESULT`).
     *
     * @return `true` if `Data_Ready` is set
     */
    fun isDataReady(): Boolean = readReg(REG_CONFIG) and CFG_DATA_READY != 0

    // -- Limits and offset ----------------------------------------------------

    /**
     * Read `THIGH_LIMIT`.
     *
     * @return high limit in °C
     */
    fun getHighLimit(): Double = decodeTemperature(readReg(REG_THIGH))

    /**
     * Write `THIGH_LIMIT`, rounded to the nearest 0.0078125 °C.
     *
     * @param celsius high limit in °C (−256.0 to 255.9921875, clamped)
     */
    fun setHighLimit(celsius: Double) = writeReg(REG_THIGH, encodeTemperature(celsius))

    /**
     * Read `TLOW_LIMIT`.
     *
     * @return low limit in °C
     */
    fun getLowLimit(): Double = decodeTemperature(readReg(REG_TLOW))

    /**
     * Write `TLOW_LIMIT`, rounded to the nearest 0.0078125 °C. In Therm mode
     * this is `HIGH_Alert`'s reset threshold (hysteresis).
     *
     * @param celsius low limit in °C (−256.0 to 255.9921875, clamped)
     */
    fun setLowLimit(celsius: Double) = writeReg(REG_TLOW, encodeTemperature(celsius))

    /**
     * Read `TEMP_OFFSET`.
     *
     * @return calibration offset in °C
     */
    fun getTemperatureOffset(): Double = decodeTemperature(readReg(REG_TEMP_OFFSET))

    /**
     * Write `TEMP_OFFSET`, added to every result after linearization.
     *
     * @param celsius calibration offset in °C (−256.0 to 255.9921875, clamped)
     */
    fun setTemperatureOffset(celsius: Double) = writeReg(REG_TEMP_OFFSET, encodeTemperature(celsius))

    // -- Reset ----------------------------------------------------------------

    /**
     * Software reset (`Soft_Reset` = 1), then wait the 2 ms reset time.
     * Reloads `CONFIGURATION`, `THIGH_LIMIT`, `TLOW_LIMIT` and `TEMP_OFFSET`
     * from EEPROM.
     */
    fun reset() {
        writeReg(REG_CONFIG, CFG_SOFT_RESET)
        try {
            Thread.sleep(2)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // -- EEPROM ---------------------------------------------------------------

    /**
     * Unlock the EEPROM (`EUN` = 1). While unlocked, writes to
     * `CONFIGURATION`, `THIGH_LIMIT`, `TLOW_LIMIT`, `TEMP_OFFSET` and
     * `EEPROM2` also program the EEPROM as the new power-on default. Poll
     * [isEepromBusy] after each such write.
     */
    fun unlockEeprom() = writeReg(REG_EEPROM_UL, EUN)

    /** Lock the EEPROM (`EUN` = 0); register writes become volatile only. */
    fun lockEeprom() = writeReg(REG_EEPROM_UL, 0x0000)

    /**
     * Report whether an EEPROM programming operation is in progress.
     *
     * @return `true` if `EEPROM_Busy` is set
     */
    fun isEepromBusy(): Boolean = readReg(REG_EEPROM_UL) and EEPROM_BUSY != 0

    /**
     * Read a general-purpose EEPROM scratch register.
     *
     * @param slot 1 (`EEPROM1`), 2 (`EEPROM2`) or 3 (`EEPROM3`); slots 1 and 3
     *   hold factory NIST-traceability data
     * @return 16-bit register value
     * @throws IllegalArgumentException if [slot] is not 1, 2 or 3
     */
    fun readEepromScratch(slot: Int): Int {
        val reg = when (slot) {
            1 -> REG_EEPROM1
            2 -> REG_EEPROM2
            3 -> REG_EEPROM3
            else -> throw IllegalArgumentException("slot must be 1, 2 or 3")
        }
        return readReg(reg)
    }

    /**
     * Write the general-purpose `EEPROM2` scratch register. Only slot 2 is
     * writable — `EEPROM1`/`EEPROM3` hold factory NIST-traceability data.
     * Persists across power cycles only while the EEPROM is unlocked.
     *
     * @param slot must be 2
     * @param value 16-bit value
     * @throws IllegalArgumentException if [slot] is not 2
     */
    fun writeEepromScratch(slot: Int, value: Int) {
        require(slot == 2) { "only EEPROM scratch slot 2 is writable" }
        writeReg(REG_EEPROM2, value and 0xFFFF)
    }

    // -- Alert output ---------------------------------------------------------

    /**
     * Configure the `ALERT` output's mode, polarity and pin function together.
     *
     * @param mode window alert or latching Therm
     * @param polarity active-low (POR default) or active-high
     * @param pinFunction alert/Therm status or Data-Ready
     */
    fun configureAlert(
        mode: AlertMode = AlertMode.ALERT,
        polarity: AlertPolarity = AlertPolarity.ACTIVE_LOW,
        pinFunction: AlertPinFunction = AlertPinFunction.ALERT,
    ) {
        var config = readConfig() and (CFG_TNA or CFG_POL or CFG_DR_ALERT).inv()
        if (mode == AlertMode.THERM) config = config or CFG_TNA
        if (polarity == AlertPolarity.ACTIVE_HIGH) config = config or CFG_POL
        if (pinFunction == AlertPinFunction.DATA_READY) config = config or CFG_DR_ALERT
        writeConfig(config)
    }

    // -- Interrupt API (Level 2) ----------------------------------------------

    /**
     * Read `CONFIGURATION`'s `HIGH_Alert` / `LOW_Alert` flags. In Alert mode
     * this read also clears both flags (a hardware side effect); in Therm
     * mode `HIGH_Alert` clears only once the result drops below `TLOW_LIMIT`.
     *
     * @return mask of [SOURCE_HIGH] / [SOURCE_LOW]
     */
    fun pollInterrupt(): Int {
        val config = readReg(REG_CONFIG)
        var status = 0
        if (config and CFG_HIGH_ALERT != 0) status = status or SOURCE_HIGH
        if (config and CFG_LOW_ALERT != 0) status = status or SOURCE_LOW
        return status
    }

    /**
     * Subscribe to `ALERT` events. With a pin, the callback runs on every
     * `ALERT` edge — falling for active-low, rising for active-high, following
     * the configured `POL`. Without one, a 5 ms polling thread calls it
     * whenever the status mask changes.
     *
     * @param callback called with the [pollInterrupt] mask
     * @param intPin ALERT pin to arm (defaults to `connection.intPin()`), or
     *   `null` to force the polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            val activeHigh = readReg(REG_CONFIG) and CFG_POL != 0
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
        }, "tmp117-poll").apply {
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
