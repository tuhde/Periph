package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.roundToLong

/**
 * Si1145 full interface — extends [Si1145Minimal] with per-channel reads, the channel list, LED
 * and photodiode selection, gains, ranges, alignment, UV coefficients, calibration, autonomous
 * mode, the I²C address, reset and the INT interrupt (active low, driven after [enableInterrupt],
 * released when the IRQ_STATUS bits are written back).
 *
 * @param connection register-addressed I²C connection bound to 0x60
 */
class Si1145Full(connection: RegisterConnection) : Si1145Minimal(connection) {
    companion object {
        /** AUX_ADCMUX: die temperature. */
        const val AUX_TEMPERATURE = 0x65
        /** AUX_ADCMUX: supply voltage. */
        const val AUX_VDD = 0x75

        private fun check(name: String, value: Long, lo: Long, hi: Long) {
            require(value in lo..hi) { "$name must be $lo..$hi" }
        }
    }

    /** Identification registers: PART_ID (0x45/0x46/0x47), REV_ID and SEQ_ID (0x01 swaps MEAS_RATE). */
    data class Id(val partId: Int, val revId: Int, val seqId: Int)

    /** Raw data registers 0x22–0x2D (`aux` is the UV index × 100 while UV is enabled). */
    data class Raw(val visible: Int, val infrared: Int, val ps1: Int, val ps2: Int, val ps3: Int, val aux: Int)

    /** Decoded RESPONSE: 4-bit counter (0 on error) and error code (0 = none, 0x80, 0x88–0x8E). */
    data class Response(val counter: Int, val error: Int)

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun ledCount(): Int = partId - 0x44

    /**
     * Reads PART_ID, REV_ID and SEQ_ID.
     *
     * @throws IOException on a bus error
     */
    fun readId(): Id {
        val b = connection.read(REG_PART_ID, 3)
        return Id(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    }

    /**
     * Forced PS measurement (PS_FORCE) and one proximity channel.
     *
     * @param channel PS channel 1–3 (Si1145: 1); must be enabled in CHLIST
     * @return raw proximity count (rises as an object approaches)
     * @throws IllegalArgumentException if the channel is not supported or not enabled
     */
    fun readProximity(channel: Int = 1): Int {
        check("channel", channel.toLong(), 1, ledCount().toLong())
        require(chList and (1 shl (channel - 1)) != 0) { "PS$channel is not enabled (setChannels)" }
        measure(true, false)
        return readData()[1 + channel]
    }

    private fun alsValue(ch: Int, index: Int, name: String): Int {
        require(chList and ch != 0) { "$name is not enabled (setChannels)" }
        measure(false, true)
        return readData()[index]
    }

    /** Forced ALS measurement and the UV index (AUX_DATA / 100); requires UV in CHLIST. */
    fun readUvIndex(): Double = alsValue(CH_UV, 5, "UV") / 100.0

    /** Forced ALS measurement and the raw visible count (ALS_VIS_DATA). */
    fun readVisible(): Int = alsValue(CH_ALS_VIS, 0, "visible")

    /** Forced ALS measurement and the raw infrared count (ALS_IR_DATA). */
    fun readInfrared(): Int = alsValue(CH_ALS_IR, 1, "infrared")

    /** Reads the latest data registers without measuring. */
    fun readRaw(): Raw {
        val v = readData()
        return Raw(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    private fun readAux(mux: Int): Int {
        val saved = chList
        paramSet(PARAM_AUX_ADCMUX, mux)
        paramSet(PARAM_CHLIST, CH_AUX)
        chList = CH_AUX
        try {
            measure(false, true)
            return readData()[5]
        } finally {
            paramSet(PARAM_CHLIST, saved)
            chList = saved
        }
    }

    /** Die temperature on the auxiliary channel (AUX_ADCMUX 0x65, relative); CHLIST restored. */
    fun readTemperatureRaw(): Int = readAux(AUX_TEMPERATURE)

    /** Supply voltage on the auxiliary channel (AUX_ADCMUX 0x75); CHLIST restored. */
    fun readVddRaw(): Int = readAux(AUX_VDD)

    /**
     * Selects the measured channels (CHLIST).
     *
     * @param uv UV index (in AUX_DATA); excludes [aux]
     * @param visible visible ambient light
     * @param infrared infrared ambient light
     * @param ps1 proximity channel 1
     * @param ps2 proximity channel 2 (Si1146/47)
     * @param ps3 proximity channel 3 (Si1147)
     * @param aux auxiliary channel (AUX_ADCMUX)
     * @throws IllegalArgumentException for uv together with aux or an unsupported PS channel
     */
    fun setChannels(uv: Boolean, visible: Boolean, infrared: Boolean, ps1: Boolean,
                    ps2: Boolean = false, ps3: Boolean = false, aux: Boolean = false) {
        require(!(uv && aux)) { "uv and aux share AUX_DATA; enable only one" }
        require(!(ps2 && ledCount() < 2) && !(ps3 && ledCount() < 3)) { "PS channel not available on this part" }
        var mask = 0
        if (uv) mask = mask or CH_UV
        if (aux) mask = mask or CH_AUX
        if (infrared) mask = mask or CH_ALS_IR
        if (visible) mask = mask or CH_ALS_VIS
        if (ps3) mask = mask or CH_PS3
        if (ps2) mask = mask or CH_PS2
        if (ps1) mask = mask or CH_PS1
        paramSet(PARAM_CHLIST, mask)
        chList = mask
    }

    /**
     * Sets an LED drive current (PS_LED21 / PS_LED3).
     *
     * @param led LED 1–3 (Si1145: 1 only)
     * @param level current level 0 (off) … 15 (maximum, ~359 mA)
     */
    fun setLedCurrent(led: Int, level: Int) {
        check("led", led.toLong(), 1, ledCount().toLong())
        check("level", level.toLong(), 0, 15)
        if (led == 3) {
            writeByte(REG_PS_LED3, level)
            return
        }
        val cur = readByte(REG_PS_LED21)
        writeByte(REG_PS_LED21, if (led == 1) (cur and 0xF0) or level else (cur and 0x0F) or (level shl 4))
    }

    /**
     * Selects the LEDs a PS channel drives (PSLED12_SELECT / PSLED3_SELECT).
     *
     * @param channel PS channel 1–3
     * @param leds LED mask (bit 0 LED1, bit 1 LED2, bit 2 LED3), only the part's LEDs
     */
    fun setPsLed(channel: Int, leds: Int) {
        check("channel", channel.toLong(), 1, 3)
        check("leds", leds.toLong(), 0, ((1 shl ledCount()) - 1).toLong())
        if (channel == 3) {
            paramSet(PARAM_PSLED3_SELECT, leds)
            return
        }
        val cur = paramQuery(PARAM_PSLED12_SELECT)
        paramSet(PARAM_PSLED12_SELECT, if (channel == 1) (cur and 0x70) or leds else (cur and 0x07) or (leds shl 4))
    }

    /**
     * Selects the photodiode of a PS channel (PSn_ADCMUX).
     *
     * @param channel PS channel 1–3
     * @param large large IR photodiode (0x03) or small (0x00)
     */
    fun setPsPhotodiode(channel: Int, large: Boolean) {
        check("channel", channel.toLong(), 1, 3)
        paramSet(PARAM_PS1_ADCMUX + channel - 1, if (large) 0x03 else 0x00)
    }

    /**
     * Sets the ADC gains 0–7 (integration time × 2^n) and the recommended recovery counts (one's
     * complement of the gain). Do not combine a high PS gain with a high LED current.
     */
    fun setGain(visible: Int = 0, infrared: Int = 0, proximity: Int = 0) {
        check("visible", visible.toLong(), 0, 7)
        check("infrared", infrared.toLong(), 0, 7)
        check("proximity", proximity.toLong(), 0, 7)
        for ((gain, gOff, cOff) in listOf(
            Triple(visible, PARAM_ALS_VIS_ADC_GAIN, PARAM_ALS_VIS_ADC_COUNTER),
            Triple(infrared, PARAM_ALS_IR_ADC_GAIN, PARAM_ALS_IR_ADC_COUNTER),
            Triple(proximity, PARAM_PS_ADC_GAIN, PARAM_PS_ADC_COUNTER),
        )) {
            paramSet(gOff, gain)
            paramSet(cOff, (gain.inv() and 0x07) shl 4)
        }
    }

    /** Selects the high signal range (sensitivity ÷ 14.5): VIS_RANGE, IR_RANGE, PS_RANGE. */
    fun setHighRange(visible: Boolean, infrared: Boolean, proximity: Boolean) {
        paramSet(PARAM_ALS_VIS_ADC_MISC, if (visible) 0x20 else 0)
        paramSet(PARAM_ALS_IR_ADC_MISC, if (infrared) 0x20 else 0)
        paramSet(PARAM_PS_ADC_MISC, if (proximity) 0x24 else 0x04)
    }

    /** Reports the 16 LSBs instead of the 16 MSBs of the ADC result (ALS/PS_ENCODING align bits). */
    fun setAlignment(visible: Boolean = false, infrared: Boolean = false, ps1: Boolean = false,
                     ps2: Boolean = false, ps3: Boolean = false) {
        paramSet(PARAM_ALS_ENCODING, (if (infrared) 0x20 else 0) or (if (visible) 0x10 else 0))
        paramSet(PARAM_PS_ENCODING, (if (ps3) 0x40 else 0) or (if (ps2) 0x20 else 0) or (if (ps1) 0x10 else 0))
    }

    /** Writes UCOEF0..3 ([UCOEF_DEFAULT] gives the UV index × 100). */
    fun setUvCoefficients(ucoef: IntArray) {
        require(ucoef.size == 4) { "ucoef must have four entries" }
        ucoef.forEach { check("ucoef", it.toLong(), 0, 0xFF) }
        connection.write(REG_UCOEF0, toBytes(ucoef))
    }

    /** Factory calibration (GET_CAL, 12 bytes); overwrites the data registers. */
    fun readCalibration(): IntArray {
        command(CMD_GET_CAL)
        val b = connection.read(REG_DATA, 12)
        return IntArray(12) { b[it].toInt() and 0xFF }
    }

    /**
     * Starts autonomous measurements: MEAS_RATE = round(interval / 31.25 µs) (bytes swapped when
     * SEQ_ID = 0x01), then PS_AUTO / ALS_AUTO / PSALS_AUTO.
     *
     * @param intervalS interval in seconds (31.25 µs … 2.048 s)
     * @param proximity run the PS channels
     * @param als run the ALS/UV channels
     */
    fun startAutonomous(intervalS: Double, proximity: Boolean = true, als: Boolean = true) {
        val rate = (intervalS / 31.25e-6).roundToLong()
        check("rate ticks", rate, 1, 0xFFFF)
        require(proximity || als) { "select proximity and/or als" }
        if (seqId == 0x01) {
            writeByte(REG_PS_RATE, (rate and 0xFF).toInt())
            writeByte(REG_MEAS_RATE0, (rate shr 8).toInt())
        } else {
            connection.write(REG_MEAS_RATE0, byteArrayOf(rate.toByte(), (rate shr 8).toByte()))
        }
        command(if (proximity && als) CMD_PSALS_AUTO else if (proximity) CMD_PS_AUTO else CMD_ALS_AUTO)
    }

    /** Pauses the autonomous loop (PSALS_PAUSE). */
    fun pause() = command(CMD_PSALS_PAUSE)

    /** Starts a forced measurement without waiting (PS/ALS/PSALS_FORCE). */
    fun force(proximity: Boolean = true, als: Boolean = true) {
        require(proximity || als) { "select proximity and/or als" }
        command(forceCommand(proximity, als))
    }

    /** Decodes RESPONSE. */
    fun readResponse(): Response {
        val r = readByte(REG_RESPONSE)
        return if (r and 0x80 != 0) Response(0, r) else Response(r and 0x0F, 0)
    }

    /**
     * Changes the I²C address (I2C_ADDR, then BUSADDR; volatile, 0x08–0x77). The chip answers on
     * the new address at once; open a new connection afterwards.
     */
    fun setI2cAddress(address: Int) {
        check("address", address.toLong(), 0x08, 0x77)
        paramSet(PARAM_I2C_ADDR, address)
        writeByte(REG_COMMAND, CMD_BUSADDR)
    }

    /** Software reset, 10 ms wait, HW_KEY and the Minimal defaults again. */
    fun reset() {
        writeByte(REG_COMMAND, CMD_RESET)
        sleepMs(10)
        applyDefaults()
    }

    /** Drives INT for sources (INT_CFG.INT_OE and IRQ_ENABLE, SOURCE_* mask). */
    fun enableInterrupt(source: Int) {
        irqEnable = (irqEnable or source) and SOURCE_MASK
        writeByte(REG_INT_CFG, 0x01)
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /** Clears sources from IRQ_ENABLE; forced reads only wait for enabled sources (none: 100 ms). */
    fun disableInterrupt(source: Int) {
        irqEnable = irqEnable and source.inv() and SOURCE_MASK
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /** Reads IRQ_STATUS and writes it back (releases INT); returns the pending enabled sources. */
    fun pollInterrupt(): Int = clearIrq() and irqEnable

    /**
     * Subscribes to measurement events (falling edge on [intPin], or a 10 ms polling thread when
     * no pin is wired).
     *
     * @param callback receives the SOURCE_* mask
     * @param intPin pin wired to INT, defaults to the connection's pin
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
            }, "si1145-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Unsubscribes and stops delivery. */
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
