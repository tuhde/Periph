package it.uhde.periph.chips.color

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * AS7262 — full driver: raw counts, gain, integration time, bank modes, LEDs, interrupt, versions.
 *
 * Extends [As7262Minimal]. [onInterrupt] uses `connection.intPin()` if wired (falling edge),
 * otherwise a polling thread at the integration time; the callback receives `1` when data is ready.
 */
open class As7262Full(connection: RegisterConnection) : As7262Minimal(connection) {
    companion object {
        private val GAINS = doubleArrayOf(1.0, 3.7, 16.0, 64.0)
        private val DRV_CURRENTS = doubleArrayOf(12.5, 25.0, 50.0, 100.0)
        private val IND_CURRENTS = doubleArrayOf(1.0, 2.0, 4.0, 8.0)

        private fun nearest(options: DoubleArray, value: Double): Int {
            require(value <= options[3]) { "current above maximum ${options[3]} mA" }
            var best = 0
            for (i in 1 until 4) if (Math.abs(options[i] - value) < Math.abs(options[best] - value)) best = i
            return best
        }
    }

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun readRawRegs(): As7262RawChannels {
        val v = IntArray(6) { (readVirtual(REG_RAW + 2 * it) shl 8) or readVirtual(REG_RAW + 2 * it + 1) }
        return As7262RawChannels(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    /**
     * Run one one-shot conversion and return the raw 16-bit counts.
     *
     * @throws IOException on a bus error or a conversion time-out
     */
    fun readRaw(): As7262RawChannels {
        startOneShot()
        waitData()
        return readRawRegs()
    }

    /** Start a one-shot conversion without waiting for it (`BANK = 3`). */
    fun measure() = startOneShot()

    /** True if a conversion result is waiting (`CONTROL_SETUP.DATA_RDY`). */
    fun dataReady(): Boolean = (readVirtual(REG_CONTROL) and CTRL_DATA_RDY) != 0

    /** Read the last calibrated results without starting a conversion. */
    fun readCalibratedLast(): As7262Channels = readCalibrated()

    /** Read the last raw counts without starting a conversion. */
    fun readRawLast(): As7262RawChannels = readRawRegs()

    /**
     * Read one calibrated channel of the last conversion.
     *
     * @param index channel 0–5 (V, B, G, Y, O, R)
     * @throws IllegalArgumentException if index is outside 0–5
     */
    fun readChannel(index: Int): Float {
        require(index in 0..5) { "channel index must be 0-5" }
        return readFloat(REG_CAL + 4 * index)
    }

    /**
     * Read one raw channel of the last conversion.
     *
     * @param index channel 0–5
     * @throws IllegalArgumentException if index is outside 0–5
     */
    fun readChannelRaw(index: Int): Int {
        require(index in 0..5) { "channel index must be 0-5" }
        return (readVirtual(REG_RAW + 2 * index) shl 8) or readVirtual(REG_RAW + 2 * index + 1)
    }

    private fun updateControl(mask: Int, bits: Int) {
        val ctrl = readVirtual(REG_CONTROL)
        writeVirtual(REG_CONTROL, (ctrl and 0x7C and mask.inv()) or bits)
    }

    /**
     * Set the analog gain.
     *
     * @param gain 0–3 (index), or the gain 1, 3.7, 16 or 64 (1 means 1x)
     * @throws IllegalArgumentException for an unsupported gain
     */
    fun setGain(gain: Double) {
        var index = -1
        for (i in 0 until 4) if (GAINS[i] == gain) index = i
        if (index < 0 && (gain == 0.0 || gain == 2.0 || gain == 3.0)) index = gain.toInt()
        require(index >= 0) { "gain must be 0-3 or one of 1, 3.7, 16, 64" }
        updateControl(0x30, index shl 4)
    }

    /** The analog gain multiplier (1, 3.7, 16 or 64). */
    fun getGain(): Double = GAINS[(readVirtual(REG_CONTROL) shr 4) and 3]

    /**
     * Set the integration time per bank.
     *
     * @param ms 2.8–714 ms (rounded to a multiple of 2.8 ms)
     * @throws IllegalArgumentException if ms is outside 2.8–714
     */
    fun setIntegrationTime(ms: Double) {
        val n = Math.round(ms / STEP_MS)
        require(n in 1L..255L) { "integration time must be 2.8-714 ms" }
        writeVirtual(REG_INT_T, n.toInt())
    }

    /** The integration time per bank in milliseconds. */
    fun getIntegrationTime(): Double = readVirtual(REG_INT_T) * STEP_MS

    /**
     * Set the bank mode.
     *
     * @param mode 0 (V,B,G,Y continuous), 1 (G,Y,O,R continuous), 2 (all six continuous) or 3 (all six, one-shot)
     * @throws IllegalArgumentException if mode is outside 0–3
     */
    fun setMode(mode: Int) {
        require(mode in 0..3) { "mode must be 0-3" }
        updateControl(0x0C, mode shl 2)
    }

    /** The bank mode (0–3). */
    fun getMode(): Int = (readVirtual(REG_CONTROL) shr 2) and 3

    /**
     * Switch to continuous conversions.
     *
     * @param mode bank mode 0, 1 or 2
     * @throws IllegalArgumentException if mode is not 0, 1 or 2
     */
    fun setContinuous(mode: Int = 2) {
        require(mode in 0..2) { "continuous mode must be 0, 1 or 2" }
        setMode(mode)
    }

    /** Enable or disable the INT pin output (`CONTROL_SETUP.INT`). */
    fun setInterrupt(enabled: Boolean) = updateControl(CTRL_INT, if (enabled) CTRL_INT else 0)

    /**
     * Control the driver LED output.
     *
     * @param on true to enable the output
     * @param currentMa current limit; nearest of 12.5, 25, 50, 100 mA
     * @throws IllegalArgumentException if currentMa is above 100
     */
    fun setLed(on: Boolean, currentMa: Double = 12.5) {
        val index = nearest(DRV_CURRENTS, currentMa)
        val led = readVirtual(REG_LED) and 0x07
        writeVirtual(REG_LED, led or (index shl 4) or (if (on) 0x08 else 0))
    }

    /**
     * Control the indicator LED output.
     *
     * @param on true to enable the output
     * @param currentMa current limit; nearest of 1, 2, 4, 8 mA
     * @throws IllegalArgumentException if currentMa is above 8
     */
    fun setIndicatorLed(on: Boolean, currentMa: Double = 1.0) {
        val index = nearest(IND_CURRENTS, currentMa)
        val led = readVirtual(REG_LED) and 0x38
        writeVirtual(REG_LED, led or (index shl 1) or (if (on) 0x01 else 0))
    }

    /** Soft-reset the chip (all registers return to their defaults). */
    fun softReset() = reset()

    /** `[device_type, hardware_version]` — expect `[0x40, 0x3E]`. */
    fun hardwareVersion(): IntArray = intArrayOf(readVirtual(REG_HW_VERSION), readVirtual(REG_HW_VERSION + 1))

    /** The firmware version as `[major, minor, sub]`. */
    fun firmwareVersion(): IntArray {
        val r2 = readVirtual(REG_FW_VERSION)
        val r3 = readVirtual(REG_FW_VERSION + 1)
        return intArrayOf(r3 shr 4, ((r3 and 0x0F) shl 2) or (r2 shr 6), r2 and 0x3F)
    }

    /** The device type byte (virtual register 0x00, expect 0x40). */
    fun deviceType(): Int = readVirtual(REG_HW_VERSION)

    /** Read `CONTROL_SETUP` (releases INT) and return `DATA_RDY` (1 or 0). */
    fun pollInterrupt(): Int = if ((readVirtual(REG_CONTROL) and CTRL_DATA_RDY) != 0) 1 else 0

    /**
     * Enable the INT output and subscribe to the INT pin (falling edge), or poll every integration
     * time when no pin is wired.
     *
     * @param callback receives 1 when data is ready
     * @param intPin INT pin to arm, or null to force the polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        setInterrupt(true)
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            val periodMs = maxOf(getIntegrationTime().toLong(), 10L)
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(periodMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "as7262-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Unsubscribe, stop delivery and disable the INT output. */
    fun offInterrupt() {
        val was = callback != null
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
        if (was) setInterrupt(false)
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status != 0) cb(status)
        } catch (_: IOException) {
            // a failed poll delivers nothing
        } catch (_: IllegalStateException) {
        }
    }
}
