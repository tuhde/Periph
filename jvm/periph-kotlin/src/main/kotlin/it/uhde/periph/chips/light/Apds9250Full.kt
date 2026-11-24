package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * APDS-9250 full interface — extends [Apds9250Minimal] with single-channel
 * reads, mode, resolution, rate and gain configuration, thresholds, variation
 * and persistence, and interrupt delivery.
 *
 * Setters with a restricted value throw [IllegalArgumentException] and write
 * nothing for an unsupported argument.
 *
 * @param connection I²C register connection bound to the device (0x52)
 */
class Apds9250Full(connection: RegisterConnection) : Apds9250Minimal(connection) {

    companion object {
        /** Interrupt source: selected channel outside the threshold window. */
        const val SOURCE_THRESHOLD = 0x01

        /** Interrupt source: selected channel changed by more than the variance threshold. */
        const val SOURCE_VARIATION = 0x02

        /** Interrupt channel: infrared. */
        const val CHANNEL_IR = 0

        /** Interrupt channel: green / ambient light. */
        const val CHANNEL_GREEN = 1

        /** Interrupt channel: red. */
        const val CHANNEL_RED = 2

        /** Interrupt channel: blue. */
        const val CHANNEL_BLUE = 3

        private val RESOLUTION_BITS = intArrayOf(20, 19, 18, 17, 16, 13)
        private val INTEGRATION_S = doubleArrayOf(0.4, 0.2, 0.1, 0.05, 0.025, 0.003125)
        private val RATE_MS = intArrayOf(25, 50, 100, 200, 500, 1000, 2000, 2000)
        private val GAINS = intArrayOf(1, 3, 6, 9, 18)
    }

    @Volatile
    private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null

    @Volatile
    private var polling = false

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** @return red count (3-byte read) */
    fun readRed(): Int = readChannel(REG_DATA_RED)

    /** @return green / ambient-light count */
    fun readGreen(): Int = readChannel(REG_DATA_GREEN)

    /** @return blue count */
    fun readBlue(): Int = readChannel(REG_DATA_BLUE)

    /** @return infrared count */
    fun readIr(): Int = readChannel(REG_DATA_IR)

    /** Block-read all four channels without waiting (call after [dataReady]). */
    fun readChannels(): Counts = readBlock()

    /**
     * Report whether a new result is available. Reads MAIN_STATUS, which clears
     * its flags; the interrupt and power-on bits are kept for [pollInterrupt]
     * and [status].
     *
     * @return true if LS_DATA was set
     */
    fun dataReady(): Boolean = (readStatus() and STATUS_DATA_READY) != 0

    /**
     * Select colour-sensing or ALS/IR mode.
     *
     * @param colour true: CS mode (red, green, blue, IR); false: ALS/IR (green and IR only)
     */
    fun setMode(colour: Boolean) = updateReg(REG_MAIN_CTRL, if (colour) CTRL_CS_MODE else 0, CTRL_CS_MODE)

    /** Activate the light sensor (LS_EN = 1). */
    fun enable() = updateReg(REG_MAIN_CTRL, CTRL_LS_EN, 0)

    /** Put the light sensor in standby (LS_EN = 0). */
    fun disable() = updateReg(REG_MAIN_CTRL, 0, CTRL_LS_EN)

    /**
     * Set the ADC resolution (and integration time); restarts the measurement.
     *
     * @param bits 13, 16, 17, 18, 19 or 20
     * @throws IllegalArgumentException for an unsupported value
     */
    fun setResolution(bits: Int) {
        val code = RESOLUTION_BITS.indexOf(bits)
        require(code >= 0) { "resolution must be 13, 16, 17, 18, 19 or 20" }
        updateReg(REG_LS_MEAS_RATE, code shl 4, 0x70)
        integrationS = INTEGRATION_S[code]
    }

    /**
     * Set the measurement rate.
     *
     * @param milliseconds 25, 50, 100, 200, 500, 1000 or 2000
     * @throws IllegalArgumentException for an unsupported value
     */
    fun setMeasurementRate(milliseconds: Int) {
        val code = RATE_MS.take(7).indexOf(milliseconds)
        require(code >= 0) { "rate must be 25, 50, 100, 200, 500, 1000 or 2000 ms" }
        updateReg(REG_LS_MEAS_RATE, code, 0x07)
    }

    /**
     * Set the analog gain for all channels; restarts the measurement.
     *
     * @param gain 1, 3, 6, 9 or 18
     * @throws IllegalArgumentException for an unsupported value
     */
    fun setGain(gain: Int) {
        val code = GAINS.indexOf(gain)
        require(code >= 0) { "gain must be 1, 3, 6, 9 or 18" }
        writeReg(REG_LS_GAIN, code)
    }

    /** @return the ADC resolution in bits */
    fun getResolution(): Int {
        val code = (readReg(REG_LS_MEAS_RATE) shr 4) and 0x07
        return if (code < 6) RESOLUTION_BITS[code] else 18
    }

    /** @return the measurement rate in milliseconds */
    fun getMeasurementRate(): Int = RATE_MS[readReg(REG_LS_MEAS_RATE) and 0x07]

    /** @return the analog gain (1, 3, 6, 9 or 18) */
    fun getGain(): Int {
        val code = readReg(REG_LS_GAIN) and 0x07
        return if (code < 5) GAINS[code] else 18
    }

    /** @return the integration time of the current resolution in seconds */
    fun integrationTime(): Double {
        val code = (readReg(REG_LS_MEAS_RATE) shr 4) and 0x07
        return if (code < 6) INTEGRATION_S[code] else 0.1
    }

    /**
     * Convert a count to counts per gain-second: `counts / (gain · integrationTime)`.
     *
     * @param counts raw channel count
     * @return the normalised count
     */
    fun normalized(counts: Int): Double = counts / (getGain().toDouble() * integrationTime())

    /** @return the PART_ID nibbles as (part, revision), (0xB, 0x5) for the APDS-9250 */
    fun partId(): Pair<Int, Int> {
        val value = readReg(REG_PART_ID)
        return Pair(value shr 4, value and 0x0F)
    }

    /**
     * Read MAIN_STATUS (clearing the flags on the chip).
     *
     * @return the raw status byte with interrupt / power-on bits seen since the last call merged in
     */
    @Synchronized
    fun status(): Int {
        val value = readStatus() or pending
        pending = 0
        return value
    }

    /** Trigger a software reset (all registers to defaults, sensor in standby). */
    fun softReset() {
        writeReg(REG_MAIN_CTRL, CTRL_SW_RESET)
        sleepMs(if (startWaitMs > 0) 10 else 0)
        pending = 0
        integrationS = 0.1
    }

    /**
     * Set the 20-bit threshold window.
     *
     * @param low lower threshold (0–1048575)
     * @param high upper threshold (0–1048575)
     * @throws IllegalArgumentException if a value exceeds 20 bit or `low > high`
     */
    fun setThresholds(low: Int, high: Int) {
        require(low in 0..0xFFFFF && high in 0..0xFFFFF && low <= high) {
            "thresholds must satisfy 0 <= low <= high <= 1048575"
        }
        connection.write(REG_THRES_LOW, byteArrayOf(low.toByte(), (low shr 8).toByte(), ((low shr 16) and 0x0F).toByte()))
        connection.write(REG_THRES_UP, byteArrayOf(high.toByte(), (high shr 8).toByte(), ((high shr 16) and 0x0F).toByte()))
    }

    /** @return the threshold window as (low, high) counts */
    fun getThresholds(): Pair<Int, Int> =
        Pair(count(connection.read(REG_THRES_LOW, 3), 0), count(connection.read(REG_THRES_UP, 3), 0))

    /**
     * Select the channel the interrupt monitors.
     *
     * @param channel [CHANNEL_IR], [CHANNEL_GREEN], [CHANNEL_RED] or [CHANNEL_BLUE]
     * @throws IllegalArgumentException for an unknown channel
     */
    fun setInterruptChannel(channel: Int) {
        require(channel in CHANNEL_IR..CHANNEL_BLUE) { "unknown channel" }
        updateReg(REG_INT_CFG, channel shl 4, INT_SEL_MASK)
    }

    /**
     * Set the number of consecutive events before the interrupt asserts.
     *
     * @param count 1–16
     * @throws IllegalArgumentException if out of range
     */
    fun setPersistence(count: Int) {
        require(count in 1..16) { "count must be 1-16" }
        writeReg(REG_INT_PERSISTENCE, (count - 1) shl 4)
    }

    /**
     * Set the variation-interrupt threshold.
     *
     * @param counts 8, 16, 32, … 1024
     * @throws IllegalArgumentException if not `8 << n` (n = 0–7)
     */
    fun setVariance(counts: Int) {
        val n = (0..7).firstOrNull { counts == (8 shl it) }
        require(n != null) { "counts must be 8, 16, 32, ... 1024" }
        writeReg(REG_THRES_VAR, n)
    }

    /**
     * Select threshold or variation mode and enable the interrupt.
     *
     * @param source [SOURCE_THRESHOLD] or [SOURCE_VARIATION]
     */
    fun enableInterrupt(source: Int = SOURCE_THRESHOLD) {
        val mode = if (source == SOURCE_VARIATION) INT_VAR_MODE else 0
        updateReg(REG_INT_CFG, INT_EN or mode, INT_EN or INT_VAR_MODE)
    }

    /** Disable the interrupt (clears LS_INT_EN; both sources share the bit). */
    fun disableInterrupt() = updateReg(REG_INT_CFG, 0, INT_EN)

    /**
     * Read MAIN_STATUS (clearing the flags) and return it; same as [status].
     *
     * @return status byte; [STATUS_INTERRUPT] is set if the condition was fulfilled
     */
    fun pollInterrupt(): Int = status()

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 50 ms polling
     * thread if no [InputPin] is available.
     *
     * @param intPin overrides `connection.intPin()`, or null
     * @param callback called with the MAIN_STATUS byte after it has been read and cleared
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
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "apds9250-poll").also {
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
            val status = pollInterrupt()
            if ((status and STATUS_INTERRUPT) != 0) callback?.invoke(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
