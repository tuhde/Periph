package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.roundToLong

/**
 * Si1133 full interface — extends [Si1133Minimal] with per-channel configuration,
 * single-photodiode reads, autonomous and burst modes, thresholds, direct parameter access, the
 * I²C address and the INT interrupt (active low, open-drain, released when IRQ_STATUS is read).
 *
 * @param connection register-addressed I²C connection bound to 0x55 or 0x52
 */
class Si1133Full(connection: RegisterConnection) : Si1133Minimal(connection) {
    companion object {
        /** Interrupt source: channel 0 result ready. */
        const val SOURCE_CHANNEL0 = 0x01
        /** Interrupt source: channel 1 result ready. */
        const val SOURCE_CHANNEL1 = 0x02
        /** Interrupt source: channel 2 result ready. */
        const val SOURCE_CHANNEL2 = 0x04
        /** Interrupt source: channel 3 result ready. */
        const val SOURCE_CHANNEL3 = 0x08
        /** Interrupt source: channel 4 result ready. */
        const val SOURCE_CHANNEL4 = 0x10
        /** Interrupt source: channel 5 result ready. */
        const val SOURCE_CHANNEL5 = 0x20

        private const val SCRATCH = 5

        private fun check(name: String, value: Long, lo: Long, hi: Long) {
            require(value in lo..hi) { "$name must be $lo..$hi" }
        }
    }

    /** Identification registers: PART_ID (0x33), HW_ID (0x03 = Si1133-AA00 in bits 4:0), REV_ID. */
    data class Id(val partId: Int, val hwId: Int, val revId: Int)

    /** Decoded RESPONSE0: state bits, CMD_ERR flag and command counter (error code when [error]). */
    data class Response(val state: Int, val error: Boolean, val counter: Int)

    private val counterIndex = IntArray(6)
    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** Read PART_ID, HW_ID and REV_ID. */
    fun readId(): Id {
        val b = connection.read(REG_PART_ID, 3)
        return Id(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    }

    /**
     * Configure one channel (ADCCONFIG, ADCSENS, ADCPOST); clears its threshold select.
     *
     * @param channel channel 0–5
     * @param adcmux photodiode (MUX_* constant)
     * @param hwGain integration time code 0–11 (48.8 µs × 2^n at decimation 0)
     * @param swGain accumulate 2^n samples, n = 0–7
     * @param decimRate decimation rate 0–3
     * @param highSignal divide the sensitivity by 14.5 (direct sunlight)
     * @param bits24Out 24-bit signed output, otherwise 16-bit unsigned
     * @param postshift right shift 0–7 of the accumulated result
     * @throws IllegalArgumentException if a value is out of range
     */
    fun configureChannel(
        channel: Int, adcmux: Int, hwGain: Int = 0, swGain: Int = 0, decimRate: Int = 0,
        highSignal: Boolean = false, bits24Out: Boolean = true, postshift: Int = 0,
    ) {
        check("channel", channel.toLong(), 0, 5)
        check("adcmux", adcmux.toLong(), 0, 0x1F)
        check("hwGain", hwGain.toLong(), 0, 11)
        check("swGain", swGain.toLong(), 0, 7)
        check("decimRate", decimRate.toLong(), 0, 3)
        check("postshift", postshift.toLong(), 0, 7)
        setChannel(channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24Out, postshift)
    }

    /** Select the channels that run (CHAN_LIST, bit c = channel c, 0x00–0x3F). */
    fun enableChannels(mask: Int) {
        check("mask", mask.toLong(), 0, 0x3F)
        paramSet(PARAM_CHAN_LIST, mask)
        chanList = mask
    }

    /**
     * Return the results of all enabled channels in channel order.
     *
     * @param force run a forced measurement first; otherwise only decode HOSTOUT (autonomous mode)
     * @return one value per enabled channel (24-bit signed or 16-bit unsigned)
     */
    fun readChannels(force: Boolean = true): IntArray = if (force) measure() else readOutputs()

    private fun scratchRead(adcmux: Int, hwGain: Int): Int {
        val saved = chanList
        setChannel(SCRATCH, adcmux, hwGain, 0, 0, false, true, 0)
        if (counterIndex[SCRATCH] != 0) {
            paramSet(PARAM_MEASCONFIG0 + 4 * SCRATCH, 0)
            counterIndex[SCRATCH] = 0
        }
        paramSet(PARAM_CHAN_LIST, 1 shl SCRATCH)
        chanList = 1 shl SCRATCH
        try {
            return measure()[0]
        } finally {
            paramSet(PARAM_CHAN_LIST, saved)
            chanList = saved
        }
    }

    /** Measure the UV photodiode once (channel 5 used as scratch, HW_GAIN 9); signed 24-bit count. */
    fun readUv(): Int = scratchRead(MUX_UV, 9)

    /** Measure the white photodiode once (channel 5 used as scratch, HW_GAIN 7); signed 24-bit count. */
    fun readWhite(): Int = scratchRead(MUX_WHITE, 7)

    /**
     * Measure an IR photodiode once (channel 5 used as scratch, HW_GAIN 7).
     *
     * @param photodiode MUX_SMALL_IR, MUX_MEDIUM_IR or MUX_LARGE_IR
     * @return signed 24-bit raw count
     */
    fun readIr(photodiode: Int = MUX_SMALL_IR): Int {
        check("photodiode", photodiode.toLong(), 0, 2)
        return scratchRead(photodiode, 7)
    }

    /** Send FORCE without waiting for the results. */
    fun force() = command(CMD_FORCE)

    /** Assign a rate counter to a channel (MEASCONFIG.COUNTER_INDEX: 0 = none, 1–3 = MEASCOUNT0..2). */
    fun setChannelCounter(channel: Int, index: Int) {
        check("channel", channel.toLong(), 0, 5)
        check("index", index.toLong(), 0, 3)
        paramSet(PARAM_MEASCONFIG0 + 4 * channel, index shl 6)
        counterIndex[channel] = index
    }

    /**
     * Start autonomous measurements: MEASRATE (800 µs units), MEASCOUNT0..2, counter 1 for every
     * enabled channel without one, then START. Channel period = rate × MEASCOUNTk.
     *
     * @param rateS main period in seconds (800 µs … 52.4 s)
     * @param counters MEASCOUNT0..2, 1–255 each
     */
    fun startAutonomous(rateS: Double, counters: IntArray = intArrayOf(1, 1, 1)) {
        val ticks = (rateS / 800e-6).roundToLong()
        check("rate ticks", ticks, 1, 0xFFFF)
        require(counters.size == 3) { "counters must have three entries" }
        counters.forEach { check("counter", it.toLong(), 1, 0xFF) }
        paramSet(PARAM_MEASRATE_H, (ticks shr 8).toInt())
        paramSet(PARAM_MEASRATE_L, (ticks and 0xFF).toInt())
        for (i in 0 until 3) paramSet(PARAM_MEASCOUNT0 + i, counters[i])
        for (ch in 0 until 6) {
            if (chanList and (1 shl ch) != 0 && counterIndex[ch] == 0) setChannelCounter(ch, 1)
        }
        command(CMD_START)
    }

    /** Pause autonomous measurements (PAUSE). */
    fun pause() = command(CMD_PAUSE)

    /** Configure burst mode (BURST): 1–127 readings, 0 disables. */
    fun setBurst(count: Int) {
        check("count", count.toLong(), 0, 127)
        paramSet(PARAM_BURST, if (count != 0) 0x80 or count else 0)
    }

    /** Set one of the three 16-bit thresholds (index 0–2, value 0–65535). */
    fun setThreshold(index: Int, value: Int) {
        check("index", index.toLong(), 0, 2)
        check("value", value.toLong(), 0, 0xFFFF)
        paramSet(PARAM_THRESHOLD0_H + 2 * index, value shr 8)
        paramSet(PARAM_THRESHOLD0_H + 2 * index + 1, value and 0xFF)
    }

    /** Select the threshold a channel's interrupt compares against (ADCPOST.THRESH_SEL: 0 = off, 1–3). */
    fun setChannelThreshold(channel: Int, index: Int) {
        check("channel", channel.toLong(), 0, 5)
        check("index", index.toLong(), 0, 3)
        val address = PARAM_ADCPOST0 + 4 * channel
        val v = paramQuery(address)
        paramSet(address, (v and 0xFC) or index)
    }

    /** Read a parameter-table byte (PARAM_QUERY, address 0x00–0x3F). */
    fun readParameter(address: Int): Int {
        check("address", address.toLong(), 0, 0x3F)
        return paramQuery(address)
    }

    /**
     * Write a parameter-table byte (PARAM_SET, address 0x00–0x3F). Writing channel parameters
     * directly bypasses the driver's record of the channel layout; prefer [configureChannel].
     */
    fun writeParameter(address: Int, value: Int) {
        check("address", address.toLong(), 0, 0x3F)
        check("value", value.toLong(), 0, 0xFF)
        paramSet(address, value)
    }

    /** Decode RESPONSE0. */
    fun readResponse(): Response {
        val r = readByte(REG_RESPONSE0)
        return Response(r and 0xE0, r and RESP_CMD_ERR != 0, r and 0x0F)
    }

    /** Read IRQ_STATUS (clears it): bit c set when channel c has a new result. */
    fun dataReady(): Int = readByte(REG_IRQ_STATUS) and 0x3F

    /**
     * Change the I²C address (volatile, 0x08–0x77). The chip answers on the new address at once,
     * so no completion check is made; open a new connection afterwards.
     */
    fun setI2cAddress(address: Int) {
        check("address", address.toLong(), 0x08, 0x77)
        readByte(REG_RESPONSE0)
        connection.write(REG_HOSTIN0, byteArrayOf(address.toByte(), (CMD_PARAM_SET or PARAM_I2C_ADDR).toByte()))
    }

    /** Software reset (RESET_SW), wait for SLEEP, re-apply the Minimal defaults. */
    fun reset() {
        softwareReset()
        counterIndex.fill(0)
        applyDefaults()
    }

    /** Enable INT for channels (IRQ_ENABLE, SOURCE_CHANNEL* mask). */
    fun enableInterrupt(source: Int) {
        irqEnable = (irqEnable or source) and 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /**
     * Disable INT for channels. Forced reads only wait for channels whose interrupt is enabled
     * (none: a fixed 100 ms wait).
     */
    fun disableInterrupt(source: Int) {
        irqEnable = irqEnable and source.inv() and 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /** Read IRQ_STATUS (clears it, releases INT); channel mask, 0 = nothing pending. */
    fun pollInterrupt(): Int = dataReady()

    /**
     * Subscribe to channel-ready events (falling edge on [intPin], or a 10 ms polling thread when
     * no pin is available).
     *
     * @param callback receives the IRQ_STATUS channel mask
     * @param intPin pin wired to INT, or null for the polling fallback
     */
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
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "si1133-poll").apply {
                isDaemon = true
                start()
            }
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
            // a failed poll is retried on the next edge / tick
        }
    }
}
