package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * Si1132 full driver — extends [Si1132Minimal] with single channels, auxiliary channels, gain and
 * range, alignment, UV coefficients, calibration, autonomous mode, command response, address
 * change, reset and the INT pin.
 *
 * @param conn I²C register connection bound to 0x60
 */
class Si1132Full(conn: RegisterConnection) : Si1132Minimal(conn) {
    companion object {
        /** `IRQ_STATUS` flag: ALS/UV measurement complete. */
        const val STATUS_ALS_INT = 0x01
    }

    @Volatile private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Forced UV measurement.
     *
     * @return UV index (`AUX_DATA / 100`)
     */
    fun readUvIndex(): Double = measure()[2] / 100.0

    /**
     * Forced measurement of the visible channel.
     *
     * @return raw 16-bit visible count
     */
    fun readVisible(): Int = measure()[0]

    /**
     * Forced measurement of the infrared channel.
     *
     * @return raw 16-bit infrared count
     */
    fun readInfrared(): Int = measure()[1]

    /**
     * Read the latest data registers without starting a measurement.
     *
     * @return {visible, infrared, aux}
     */
    fun readRaw(): IntArray = readData()

    private fun readAux(mux: Int): Int {
        paramSet(PARAM_AUX_ADCMUX, mux)
        paramSet(PARAM_CHLIST, CHLIST_AUX)
        try {
            return measure()[2]
        } finally {
            paramSet(PARAM_CHLIST, chlist)
        }
    }

    /**
     * Measure the auxiliary channel as die temperature (`AUX_ADCMUX = 0x65`, relative only).
     *
     * @return raw 16-bit value
     */
    fun readTemperatureRaw(): Int = readAux(0x65)

    /**
     * Measure the auxiliary channel as supply voltage (`AUX_ADCMUX = 0x75`).
     *
     * @return raw 16-bit value
     */
    fun readVddRaw(): Int = readAux(0x75)

    /**
     * Select the measured channels (`CHLIST`).
     *
     * @param uv enable the UV channel (cannot be combined with [aux])
     * @param visible enable the visible channel
     * @param infrared enable the infrared channel
     * @param aux enable the auxiliary channel
     * @throws IllegalArgumentException if no channel is selected or both [uv] and [aux] are set
     */
    fun setChannels(uv: Boolean, visible: Boolean, infrared: Boolean, aux: Boolean = false) {
        require(uv || visible || infrared || aux) { "at least one channel must be enabled" }
        require(!(uv && aux)) { "uv and aux share the data registers" }
        val v = (if (uv) 0x80 else 0) or (if (aux) 0x40 else 0) or
            (if (infrared) 0x20 else 0) or (if (visible) 0x10 else 0)
        paramSet(PARAM_CHLIST, v)
        chlist = v
    }

    /**
     * Set the ADC gain (integration time x 2^n) and the recommended recovery count.
     *
     * @param visible visible gain 0–7
     * @param infrared infrared gain 0–7
     * @throws IllegalArgumentException if a gain is outside 0–7
     */
    fun setGain(visible: Int, infrared: Int) {
        require(visible in 0..7 && infrared in 0..7) { "gain must be 0-7" }
        paramSet(PARAM_VIS_GAIN, visible)
        paramSet(PARAM_VIS_COUNTER, (visible.inv() and 0x07) shl 4)
        paramSet(PARAM_IR_GAIN, infrared)
        paramSet(PARAM_IR_COUNTER, (infrared.inv() and 0x07) shl 4)
    }

    /**
     * Select the high signal range (sensitivity ÷ 14.5, for direct sunlight).
     *
     * @param visible high range for the visible channel
     * @param infrared high range for the infrared channel
     */
    fun setHighRange(visible: Boolean, infrared: Boolean) {
        paramSet(PARAM_VIS_MISC, if (visible) 0x20 else 0x00)
        paramSet(PARAM_IR_MISC, if (infrared) 0x20 else 0x00)
    }

    /**
     * Select which 16 bits of the 17-bit ADC value are reported.
     *
     * @param visibleLow true = least significant 16 bits, false = the 16 MSBs
     * @param infraredLow same for the infrared channel
     */
    fun setAlignment(visibleLow: Boolean, infraredLow: Boolean) {
        paramSet(PARAM_ALS_ENCODING, (if (visibleLow) 0x10 else 0) or (if (infraredLow) 0x20 else 0))
    }

    /**
     * Write the four UV index coefficients `UCOEF0..3`.
     *
     * @param ucoef four byte values
     * @throws IllegalArgumentException if [ucoef] does not have four elements
     */
    fun setUvCoefficients(ucoef: IntArray) {
        require(ucoef.size == 4) { "four coefficients required" }
        conn.write(REG_UCOEF, ByteArray(4) { ucoef[it].toByte() })
    }

    /**
     * Read the factory calibration (`GET_CAL`, then registers `0x22`–`0x2D`).
     *
     * @return the 12 calibration bytes
     */
    fun readCalibration(): IntArray {
        command(CMD_GET_CAL)
        val d = conn.read(REG_DATA, 12)
        return IntArray(12) { d[it].toInt() and 0xFF }
    }

    /**
     * Start autonomous measurements.
     *
     * @param interval wake-up interval in seconds (multiple of 31.25 µs, at most about 2.05 s); 0 returns to forced mode
     * @throws IllegalArgumentException if [interval] is negative or too long
     */
    fun startAutonomous(interval: Double) {
        val counts = Math.round(interval / 31.25e-6)
        require(interval >= 0 && counts <= 0xFFFF) { "interval out of range" }
        if (seqId == 0x01) {   // erratum: MEAS_RATE bytes swapped
            writeReg(REG_MEAS_RATE_SWAPPED, (counts and 0xFF).toInt())
            writeReg(REG_MEAS_RATE0, (counts shr 8).toInt())
        } else {
            writeReg(REG_MEAS_RATE0, (counts and 0xFF).toInt())
            writeReg(REG_MEAS_RATE1, (counts shr 8).toInt())
        }
        command(if (counts != 0L) CMD_ALS_AUTO else CMD_ALS_PAUSE)
    }

    /** Pause autonomous measurements (`ALS_PAUSE`). */
    fun pause() = command(CMD_ALS_PAUSE)

    /** Start one forced measurement without waiting for it (`ALS_FORCE`). */
    fun force() = startMeasurement()

    /**
     * Check whether a measurement is complete.
     *
     * @return true when `IRQ_STATUS.ALS_INT` is set
     */
    fun measurementReady(): Boolean = (readReg(REG_IRQ_STATUS) and IRQ_ALS_INT) != 0

    /**
     * Decode the `RESPONSE` register.
     *
     * @return {counter, error}; error 0 = none, otherwise the raw 0x8x code
     */
    fun readResponse(): IntArray {
        val resp = readReg(REG_RESPONSE)
        return if ((resp and RESP_ERROR) != 0) intArrayOf(0, resp) else intArrayOf(resp and 0x0F, 0)
    }

    /**
     * Change the I²C address (volatile; lost at reset).
     *
     * @param address new 7-bit address written to the `I2C_ADDR` parameter
     */
    fun setI2cAddress(address: Int) {
        paramSet(PARAM_I2C_ADDR, address)
        command(CMD_BUSADDR)
    }

    /** Software reset: `RESET` command, 2 ms wait, `HW_KEY` again. */
    fun reset() {
        writeReg(REG_COMMAND, CMD_RESET)
        sleepMs(2)
        writeReg(REG_HW_KEY, HW_KEY_VALUE)
    }

    /** Drive INT low on completed measurements (`INT_OE` and `ALS_IE`). */
    fun enableInterrupt() {
        writeReg(REG_IRQ_ENABLE, IRQ_ALS_INT)
        writeReg(REG_INT_CFG, 0x01)
    }

    /** Release INT (`INT_OE = 0`); `ALS_IE` stays set because [read] polls `ALS_INT`. */
    fun disableInterrupt() = writeReg(REG_INT_CFG, 0x00)

    /**
     * Read `IRQ_STATUS` and write it back to clear the flags.
     *
     * @return the status value (0 = nothing pending)
     */
    fun pollInterrupt(): Int {
        val status = readReg(REG_IRQ_STATUS)
        if (status != 0) writeReg(REG_IRQ_STATUS, status)
        return status
    }

    /**
     * Subscribe to measurement-complete events.
     *
     * Enables the INT output. Delivery uses the connection's `intPin()` (falling edge) when one is
     * wired and falls back to a polling thread (10 ms) otherwise. The callback receives `IRQ_STATUS`.
     *
     * @param callback called with the status
     */
    fun onInterrupt(callback: IntConsumer) {
        offInterrupt()
        enableInterrupt()
        this.callback = callback
        val pin = conn.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe from measurement events and release the INT output. */
    fun offInterrupt() {
        val had = callback != null
        callback = null
        intPin?.let {
            it.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
        if (had) disableInterrupt()
    }

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                try {
                    handleEdge()
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "si1132-poll")
        pollThread!!.isDaemon = true
        pollThread!!.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status != 0) callback?.accept(status)
        } catch (e: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
