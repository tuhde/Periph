package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * TSL2561 full interface — extends [TSL2561Minimal] with raw counts, gain and
 * integration time, package coefficients, auto-ranging, power control, manual
 * integration, the interrupt window and interrupt delivery.
 *
 * @param connection I²C register connection bound to the device (0x29, 0x39 or 0x49)
 */
class TSL2561Full(connection: RegisterConnection) : TSL2561Minimal(connection) {

    /** Lux plus the channel counts of one sample. */
    data class Reading(val lux: Double, val ch0: Int, val ch1: Int)

    private companion object {
        // (gain, INTEG) from most to least sensitive, used by autoRange().
        val RANGES = arrayOf(intArrayOf(16, 2), intArrayOf(1, 2), intArrayOf(1, 1), intArrayOf(1, 0))
        const val AUTO_MIN_COUNTS = 100
    }

    @Volatile
    private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null

    @Volatile
    private var polling = false

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Read both ADC channels in one block read.
     *
     * @return `(ch0, ch1)` — channel 0 (visible + IR) and channel 1 (IR) counts
     */
    fun readRaw(): Pair<Int, Int> = readChannels()

    /**
     * Set the ADC gain (TIMING.GAIN); wait one integration before trusting the next reading.
     *
     * @param gain 1 or 16
     * @throws IllegalArgumentException on any other value
     */
    fun setGain(gain: Int) {
        require(gain == 1 || gain == 16) { "gain must be 1 or 16" }
        curGain = gain
        writeTiming(false)
    }

    /** Current ADC gain (1 or 16). */
    fun getGain(): Int = curGain

    /**
     * Set the integration time (TIMING.INTEG); wait one integration before trusting the next reading.
     *
     * @param ms 14 (13.7 ms), 101 or 402
     * @throws IllegalArgumentException on any other value
     */
    fun setIntegrationTime(ms: Int) {
        integ = when (ms) {
            14 -> 0
            101 -> 1
            402 -> 2
            else -> throw IllegalArgumentException("integration time must be 14, 101 or 402 ms")
        }
        writeTiming(false)
    }

    /** Current integration time in ms (14, 101 or 402; 0 in manual timing mode). */
    fun getIntegrationTime(): Int = integrationMs()

    /**
     * Select the lux coefficient set.
     *
     * @param pkg "T", "FN", "CL" (shared coefficients) or "CS"
     * @throws IllegalArgumentException on any other value
     */
    fun setPackage(pkg: String) {
        require(pkg in setOf("T", "FN", "CL", "CS")) { "package must be T, FN, CL or CS" }
        this.pkg = pkg
    }

    /**
     * Read lux and both channel counts from one sample.
     *
     * @throws TSL2561Minimal.SaturatedException if a channel is saturated
     */
    fun readLuxRaw(): Reading {
        val (ch0, ch1) = readChannels()
        return Reading(luxFrom(ch0, ch1), ch0, ch1)
    }

    private fun applyRange(index: Int) {
        val oldWait = settleMs()
        curGain = RANGES[index][0]
        integ = RANGES[index][1]
        writeTiming(false)
        sleepMs(oldWait + settleMs())
    }

    /**
     * Pick gain and integration time for the current light level and read lux.
     * Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until
     * neither channel saturates and channel 0 is above 100 counts, waiting for a
     * fresh integration after every change; the chosen setting stays active.
     *
     * @return lux
     * @throws TSL2561Minimal.SaturatedException if a channel still saturates at 1×/13.7 ms
     */
    fun autoRange(): Double {
        var index = RANGES.indexOfFirst { it[0] == curGain && it[1] == integ }
        if (index < 0) {
            index = 1
            applyRange(index)
        }
        repeat(2 * RANGES.size) {
            val (ch0, ch1) = readChannels()
            val limit = fullScale()
            val saturated = ch0 >= limit || ch1 >= limit
            when {
                saturated && index < RANGES.size - 1 -> index++
                !saturated && ch0 < AUTO_MIN_COUNTS && index > 0 -> index--
                else -> return luxFrom(ch0, ch1)
            }
            applyRange(index)
        }
        return readLux()
    }

    /** Power up (CONTROL ← 0x03); integration restarts. */
    fun powerUp() = writeByte(REG_CONTROL, POWER_ON)

    /** Power down (CONTROL ← 0x00); the registers keep their values. */
    fun powerDown() = writeByte(REG_CONTROL, 0x00)

    /** True if CONTROL reads 0x03. */
    fun isPowered(): Boolean = readByte(REG_CONTROL) and 0x03 == POWER_ON

    /** Start a host-timed integration (INTEG = 11, MANUAL = 1); [readLux] then uses the 402 ms scale. */
    fun startManual() {
        integ = INTEG_MANUAL
        writeTiming(true)
    }

    /** Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next. */
    fun stopManual() {
        integ = INTEG_MANUAL
        writeTiming(false)
    }

    /**
     * Set the channel-0 interrupt window (word writes of 0x2–0x3 and 0x4–0x5).
     *
     * @param low low threshold, channel-0 counts 0–65535
     * @param high high threshold, channel-0 counts 0–65535
     * @throws IllegalArgumentException if a value is outside 0–65535
     */
    fun setThresholds(low: Int, high: Int) {
        require(low in 0..0xFFFF && high in 0..0xFFFF) { "threshold must be 0-65535" }
        writeWord(REG_THRESH_LOW, low)
        writeWord(REG_THRESH_HIGH, high)
    }

    /** Read the channel-0 interrupt window as `(low, high)` counts. */
    fun getThresholds(): Pair<Int, Int> = Pair(readWord(REG_THRESH_LOW), readWord(REG_THRESH_HIGH))

    /**
     * Configure the INTERRUPT register.
     *
     * @param mode 0 disabled, 1 level interrupt, 2 SMBAlert style
     * @param persist 0 every cycle, 1 any value outside, N (2–15) N consecutive cycles outside
     * @throws IllegalArgumentException if mode is not 0–2 or persist not 0–15
     */
    fun configureInterrupt(mode: Int, persist: Int = 0) {
        require(mode in 0..2) { "mode must be 0, 1 or 2" }
        require(persist in 0..15) { "persist must be 0-15" }
        writeByte(REG_INTERRUPT, (mode shl 4) or persist)
    }

    /** Read the ID register; `(id shr 4) == 1` for the TSL2561, bits 3:0 the revision. */
    fun readId(): Int = readByte(REG_ID)

    /**
     * Check channel 0 against the programmed window (the persistence filter is
     * not modelled and the chip's interrupt is not cleared).
     *
     * @return true if channel 0 is ≤ the low or > the high threshold
     */
    fun pollInterrupt(): Boolean {
        val ch0 = readChannels().first
        val (low, high) = getThresholds()
        return ch0 <= low || ch0 > high
    }

    /** Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt. */
    fun clearInterrupt() = connection.write(byteArrayOf(CMD_CLEAR.toByte()))

    /**
     * Subscribe to INT assertions (falling edge). With a pin the driver reads
     * channel 0, calls `callback(ch0)` and clears the level interrupt; without
     * one a polling thread calls [pollInterrupt] once per integration time.
     *
     * @param intPin overrides `connection.intPin()`, or null
     * @param callback called with the current channel-0 count
     */
    @Synchronized
    fun onInterrupt(intPin: InputPin? = null, callback: (Int) -> Unit) {
        this.callback = callback
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            val period = maxOf(14, integrationMs()).toLong()
            pollThread = Thread({
                while (polling) {
                    handlePoll()
                    try {
                        Thread.sleep(period)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "tsl2561-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopDelivery() {
        intPinUsed?.offEdge(edgeHandler)
        intPinUsed = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** Unsubscribe and stop delivery. */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val ch0 = readChannels().first
            callback?.invoke(ch0)
            clearInterrupt()
        } catch (ignored: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }

    private fun handlePoll() {
        try {
            if (!pollInterrupt()) return
            callback?.invoke(readChannels().first)
        } catch (ignored: IOException) {
            // bus error; wait for the next tick rather than propagating
        }
    }
}
