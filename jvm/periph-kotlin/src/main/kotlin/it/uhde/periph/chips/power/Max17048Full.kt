package it.uhde.periph.chips.power

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * MAX17048 / MAX17049 full driver — extends [Max17048Minimal] with alerts, RCOMP, hibernate,
 * sleep and reset.
 *
 * Interrupt sources: [SOURCE_RESET], [SOURCE_OVERVOLTAGE], [SOURCE_UNDERVOLTAGE], [SOURCE_VRESET],
 * [SOURCE_LOW_SOC], [SOURCE_SOC_CHANGE]. [onInterrupt] uses `connection.intPin()` if wired (falling
 * edge on ALRT), otherwise a 1 s polling thread.
 *
 * @param connection I²C connection bound to address 0x36
 * @param cells 1 for the MAX17048, 2 for the MAX17049
 */
class Max17048Full(
    connection: RegisterConnection,
    cells: Int = 1
) : Max17048Minimal(connection, cells) {

    companion object {
        /** Interrupt source: STATUS.RI, the chip has reset. */
        const val SOURCE_RESET = 0x0100
        /** Interrupt source: STATUS.VH, VCELL above VALRT.MAX. */
        const val SOURCE_OVERVOLTAGE = 0x0200
        /** Interrupt source: STATUS.VL, VCELL below VALRT.MIN. */
        const val SOURCE_UNDERVOLTAGE = 0x0400
        /** Interrupt source: STATUS.VR, voltage-reset alert (needs [enableVoltageResetAlert]). */
        const val SOURCE_VRESET = 0x0800
        /** Interrupt source: STATUS.HD, SOC crossed the empty-alert threshold. */
        const val SOURCE_LOW_SOC = 0x1000
        /** Interrupt source: STATUS.SC, SOC changed by at least 1 % (needs [setSocChangeAlert]). */
        const val SOURCE_SOC_CHANGE = 0x2000

        private const val SOURCES = 0x3F00
    }

    private var sleepOn = false
    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** @return the raw VCELL register */
    @Throws(IOException::class)
    fun rawVoltage(): Int = readWord(REG_VCELL)

    /** @return the raw SOC register */
    @Throws(IOException::class)
    fun rawSoc(): Int = readWord(REG_SOC)

    /** @return the charge/discharge rate in %/h (signed CRATE × 0.208), positive while charging */
    @Throws(IOException::class)
    fun chargeRate(): Double = readWord(REG_CRATE).toShort().toInt() * 0.208

    /** @return the VERSION register (0x001x) */
    @Throws(IOException::class)
    fun version(): Int = readWord(REG_VERSION)

    /** @return the factory-programmed ID (low byte of VRESET/ID) */
    @Throws(IOException::class)
    fun chipId(): Int = readWord(REG_VRESET) and 0xFF

    /** Restart the SOC estimate from the present voltage (MODE.QuickStart); only if the first SOC is clearly wrong. */
    @Throws(IOException::class)
    fun quickStart() = writeWord(REG_MODE, 0x4000 or (if (sleepOn) 0x2000 else 0))

    /** Power-on reset (CMD ← 0x5400). The chip does not ACK, so a bus error is ignored; waits 1 s. */
    fun reset() {
        try {
            writeWord(REG_CMD, 0x5400)
        } catch (e: IOException) {
            // ignored: the chip does not acknowledge the reset
        }
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Write RCOMP (CONFIG bits 15:8, read-modify-write); [value] 0–255. */
    @Throws(IOException::class)
    fun setRcomp(value: Int) = modify(REG_CONFIG, 0xFF00, (value and 0xFF) shl 8)

    /** @return RCOMP 0–255 */
    @Throws(IOException::class)
    fun getRcomp(): Int = readWord(REG_CONFIG) shr 8

    /** Set RCOMP from the datasheet formula with the default coefficients (RCOMP0 = 0x97); [celsius] is the cell temperature. */
    @Throws(IOException::class)
    fun compensateTemperature(celsius: Double) {
        val coefficient = if (celsius >= 20.0) -0.5 else -5.0
        setRcomp(Math.round(0x97 + (celsius - 20.0) * coefficient).toInt().coerceIn(0, 255))
    }

    /** Set the empty-alert threshold (CONFIG.ATHD = 32 − [percent]); [percent] 1–32. */
    @Throws(IOException::class)
    fun setAlertSoc(percent: Int) = modify(REG_CONFIG, 0x001F, 32 - percent.coerceIn(1, 32))

    /** @return the empty-alert threshold in % (1–32) */
    @Throws(IOException::class)
    fun getAlertSoc(): Int = 32 - (readWord(REG_CONFIG) and 0x1F)

    /** Enable or disable the alert on a SOC change of at least 1 % (CONFIG.ALSC). */
    @Throws(IOException::class)
    fun setSocChangeAlert(enabled: Boolean) = modify(REG_CONFIG, 0x0040, if (enabled) 0x0040 else 0)

    /**
     * Set the voltage alert window in 20 mV steps (VALRT). [minV] = 0 and [maxV] = 5.1 disable the limits.
     *
     * @throws IllegalArgumentException if [minV] > [maxV]
     */
    @Throws(IOException::class)
    fun setVoltageAlert(minV: Double, maxV: Double) {
        require(minV <= maxV) { "minV must not exceed maxV" }
        writeWord(REG_VALRT, (clampByte(minV / 0.020) shl 8) or clampByte(maxV / 0.020))
    }

    /** @return {minV, maxV} in volts */
    @Throws(IOException::class)
    fun getVoltageAlert(): DoubleArray {
        val raw = readWord(REG_VALRT)
        return doubleArrayOf((raw shr 8) * 0.020, (raw and 0xFF) * 0.020)
    }

    /** Set the reset voltage (VRESET bits 15:9, 40 mV steps); [volts] 0.0–5.08. */
    @Throws(IOException::class)
    fun setResetVoltage(volts: Double) =
        modify(REG_VRESET, 0xFE00, Math.round(volts / 0.040).toInt().coerceIn(0, 127) shl 9)

    /** @return the reset voltage in V */
    @Throws(IOException::class)
    fun getResetVoltage(): Double = (readWord(REG_VRESET) shr 9) * 0.040

    /** Let a voltage-reset event assert ALRT (STATUS.ENVR). */
    @Throws(IOException::class)
    fun enableVoltageResetAlert(enabled: Boolean) = modify(REG_STATUS, 0x4000, if (enabled) 0x4000 else 0)

    /** Disable the analog comparator in hibernate mode (VRESET/ID.DIS, saves about 0.5 µA). */
    @Throws(IOException::class)
    fun setComparatorDisabled(disabled: Boolean) = modify(REG_VRESET, 0x0100, if (disabled) 0x0100 else 0)

    /**
     * Program the hibernate thresholds (HIBRT): hibernate below [hibThrPctPerH] (0.208 %/h steps),
     * wake when |OCV − VCELL| exceeds [actThrV] (1.25 mV steps).
     */
    @Throws(IOException::class)
    fun setHibernateThresholds(hibThrPctPerH: Double, actThrV: Double) =
        writeWord(REG_HIBRT, (clampByte(hibThrPctPerH / 0.208) shl 8) or clampByte(actThrV / 0.00125))

    /** @return {hibThrPctPerH, actThrV} */
    @Throws(IOException::class)
    fun getHibernateThresholds(): DoubleArray {
        val raw = readWord(REG_HIBRT)
        return doubleArrayOf((raw shr 8) * 0.208, (raw and 0xFF) * 0.00125)
    }

    /** Never hibernate (HIBRT ← 0x0000). */
    @Throws(IOException::class)
    fun disableHibernate() = writeWord(REG_HIBRT, 0x0000)

    /** Always stay in hibernate (HIBRT ← 0xFFFF). */
    @Throws(IOException::class)
    fun forceHibernate() = writeWord(REG_HIBRT, 0xFFFF)

    /** @return MODE.HibStat; false if the write-only register cannot be read */
    fun isHibernating(): Boolean = try {
        (readWord(REG_MODE) and 0x1000) != 0
    } catch (e: IOException) {
        false
    }

    /** Allow or forbid sleep mode (MODE.EnSleep; MODE is written whole). */
    @Throws(IOException::class)
    fun enableSleep(enabled: Boolean) {
        sleepOn = enabled
        writeWord(REG_MODE, if (enabled) 0x2000 else 0x0000)
    }

    /** Enter sleep mode (CONFIG.SLEEP = 1); needs [enableSleep] first. */
    @Throws(IOException::class)
    fun sleep() = modify(REG_CONFIG, 0, 0x0080)

    /** Leave sleep mode (CONFIG.SLEEP = 0). */
    @Throws(IOException::class)
    fun wake() = modify(REG_CONFIG, 0x0080, 0)

    /** @return the STATUS register */
    @Throws(IOException::class)
    fun readStatus(): Int = readWord(REG_STATUS)

    /** Clear the STATUS bits in [mask] and CONFIG.ALRT (releases the ALRT pin). */
    @Throws(IOException::class)
    fun clearStatus(mask: Int) {
        modify(REG_STATUS, mask, 0)
        modify(REG_CONFIG, 0x0020, 0)
    }

    /** @return the raw CONFIG register */
    @Throws(IOException::class)
    fun readConfig(): Int = readWord(REG_CONFIG)

    /** Write the raw CONFIG register. */
    @Throws(IOException::class)
    fun writeConfig(value: Int) = writeWord(REG_CONFIG, value)

    /** Enable an alert source that has a control bit ([SOURCE_SOC_CHANGE], [SOURCE_VRESET]). */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int) {
        if (source == SOURCE_SOC_CHANGE) setSocChangeAlert(true)
        else if (source == SOURCE_VRESET) enableVoltageResetAlert(true)
    }

    /** Disable an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF). */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        when (source) {
            SOURCE_SOC_CHANGE -> setSocChangeAlert(false)
            SOURCE_VRESET -> enableVoltageResetAlert(false)
            SOURCE_UNDERVOLTAGE -> modify(REG_VALRT, 0xFF00, 0)
            SOURCE_OVERVOLTAGE -> modify(REG_VALRT, 0x00FF, 0x00FF)
        }
    }

    /**
     * Read STATUS, clear the reported flags and CONFIG.ALRT.
     *
     * @return the SOURCE_* bits (0 = no alert)
     */
    @Synchronized
    @Throws(IOException::class)
    fun pollInterrupt(): Int {
        val flags = readWord(REG_STATUS) and SOURCES
        if (flags != 0) clearStatus(flags)
        return flags
    }

    /**
     * Subscribe to ALRT on [intPin] (falling edge), by default `connection.intPin()`; with no pin a
     * 1 s polling thread is used.
     */
    @Throws(IOException::class)
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "max17048-poll").also { it.isDaemon = true; it.start() }
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // transient bus error: the next edge or tick retries
        }
    }

    private fun clampByte(v: Double): Int = Math.round(v).toInt().coerceIn(0, 255)
}
