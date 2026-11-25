package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * Shared implementation of the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
 *
 * The three chips share one register map and one Config-register layout and differ only in which
 * Config fields the silicon honours (ADS1113: fixed ±2.048 V, no comparator, no mux; ADS1114: PGA
 * + comparator; ADS1115: PGA + comparator + 4-input mux). This base holds the whole-word Config
 * builder, the single-shot sequence and every feature as protected members; the per-chip classes
 * publish exactly the subset their silicon supports.
 *
 * Registers are 16-bit big-endian behind a write-only Address Pointer. Every Config write is a
 * whole word — never read-modify-write.
 *
 * @param connection I²C or SMBus register connection (0x48–0x4B); makes no bus traffic
 */
abstract class ADS111xBase(protected val connection: RegisterConnection) {

    companion object {
        /** Default 7-bit I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        /** Interrupt source: a conversion finished (`ALERT/RDY` in conversion-ready mode). */
        const val SOURCE_CONVERSION_READY = 0x01

        /** Interrupt source: result beyond the comparator thresholds. */
        const val SOURCE_COMPARATOR = 0x02

        internal const val REG_CONVERSION = 0x00
        internal const val REG_CONFIG = 0x01
        internal const val REG_LO_THRESH = 0x02
        internal const val REG_HI_THRESH = 0x03
        internal const val CONFIG_RESET = 0x8583
        internal const val LO_RESET = 0x8000
        internal const val HI_RESET = 0x7FFF
        internal const val CFG_OS = 0x8000
        internal const val CFG_MODE_SHOT = 0x0100
        internal const val QUE_DISABLED = 3

        /** Full-scale range in volts, indexed by `PGA[2:0]`. */
        internal val FSR = doubleArrayOf(6.144, 4.096, 2.048, 1.024, 0.512, 0.256)

        /** Samples per second, indexed by `DR[2:0]`. */
        internal val RATES = intArrayOf(8, 16, 32, 64, 128, 250, 475, 860)
    }

    protected var mux = 0
    protected var pga = 2
    protected var dr = 4
    protected var compMode = 0
    protected var compPol = 0
    protected var compLat = 0
    protected var compQue = QUE_DISABLED
    protected var queue = 0
    protected var continuous = false
    protected var loRaw = -32768
    protected var hiRaw = 32767
    protected var source = 0

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** Read a 16-bit big-endian register. */
    protected fun readReg(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf((value shr 8).toByte(), value.toByte()))
    }

    private fun configWord(osBit: Boolean, muxCode: Int): Int =
        (if (osBit) CFG_OS else 0) or (muxCode shl 12) or (pga shl 9) or
            (if (continuous) 0 else CFG_MODE_SHOT) or (dr shl 5) or (compMode shl 4) or
            (compPol shl 3) or (compLat shl 2) or compQue

    /** Write the whole Config word with the cached fields. */
    protected fun writeConfig(osBit: Boolean = false) {
        writeReg(REG_CONFIG, configWord(osBit, mux))
    }

    /** Single-shot conversion on [muxCode]; returns the signed 16-bit code. */
    protected fun convert(muxCode: Int): Int {
        continuous = false
        writeReg(REG_CONFIG, configWord(true, muxCode))
        val wait = 1000L / RATES[dr] + 1
        val deadline = System.currentTimeMillis() + 2 * wait + 50
        sleepMs(wait)
        while ((readReg(REG_CONFIG) and CFG_OS) == 0) {
            if (System.currentTimeMillis() > deadline) throw IOException("ADS111x conversion timeout")
            sleepMs(1)
        }
        return readReg(REG_CONVERSION).toShort().toInt()
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    protected fun doSetDataRate(rate: Int) {
        require(rate in 0..7) { "dr must be 0-7" }
        dr = rate
        if (continuous) writeConfig()
    }

    protected fun doSetGain(code: Int) {
        require(code in 0..5) { "pga must be 0-5" }
        pga = code
        if (continuous) writeConfig()
    }

    protected fun doStartContinuous(muxCode: Int) {
        require(muxCode in 0..7) { "mux must be 0-7" }
        mux = muxCode
        continuous = true
        writeConfig()
    }

    protected fun doReadContinuousRaw(): Int = readReg(REG_CONVERSION).toShort().toInt()

    protected fun doStopContinuous() {
        continuous = false
        writeConfig()
    }

    protected fun doIsConverting(): Boolean = (readReg(REG_CONFIG) and CFG_OS) == 0

    protected fun doFullScale(): Double = FSR[pga]

    protected fun doLsb(): Double = FSR[pga] / 32768.0

    protected fun doReset(withThresholds: Boolean) {
        writeReg(REG_CONFIG, CONFIG_RESET)
        if (withThresholds) {
            writeReg(REG_LO_THRESH, LO_RESET)
            writeReg(REG_HI_THRESH, HI_RESET)
        }
        mux = 0; pga = 2; dr = 4
        compMode = 0; compPol = 0; compLat = 0; compQue = QUE_DISABLED
        queue = 0; continuous = false; loRaw = -32768; hiRaw = 32767; source = 0
    }

    protected fun doConfigureComparator(mode: Int, polarity: Int, latch: Boolean, q: Int) {
        require(mode in 0..1 && polarity in 0..1 && q in 0..2) { "invalid comparator argument" }
        require(hiRaw > loRaw) { "high threshold must exceed low threshold" }
        compMode = mode
        compPol = polarity
        compLat = if (latch) 1 else 0
        queue = q
        compQue = q
        source = SOURCE_COMPARATOR
        writeConfig()
    }

    protected fun doDisableComparator() {
        compQue = QUE_DISABLED
        source = 0
        writeConfig()
    }

    protected fun doSetLowThresholdRaw(raw: Int) {
        loRaw = raw
        writeReg(REG_LO_THRESH, raw and 0xFFFF)
    }

    protected fun doSetHighThresholdRaw(raw: Int) {
        hiRaw = raw
        writeReg(REG_HI_THRESH, raw and 0xFFFF)
    }

    private fun voltsToCode(volts: Double): Int {
        val steps = volts / doLsb()
        val code = if (steps >= 0) (steps + 0.5).toLong() else -((-steps + 0.5).toLong())
        return code.coerceIn(-32768L, 32767L).toInt()
    }

    protected fun doSetThresholds(low: Double, high: Double) {
        require(high > low) { "high must exceed low" }
        doSetLowThresholdRaw(voltsToCode(low))
        doSetHighThresholdRaw(voltsToCode(high))
    }

    protected fun doGetThresholdsRaw(): IntArray =
        intArrayOf(readReg(REG_LO_THRESH).toShort().toInt(), readReg(REG_HI_THRESH).toShort().toInt())

    protected fun doClearAlert() {
        readReg(REG_CONVERSION)
    }

    protected fun doEnableInterrupt(src: Int) {
        when (src) {
            SOURCE_CONVERSION_READY -> {
                writeReg(REG_HI_THRESH, 0x8000)
                writeReg(REG_LO_THRESH, 0x0000)
                compQue = 0
                source = SOURCE_CONVERSION_READY
                writeConfig()
            }
            SOURCE_COMPARATOR -> {
                doSetLowThresholdRaw(loRaw)
                doSetHighThresholdRaw(hiRaw)
                doConfigureComparator(compMode, compPol, compLat == 1, queue)
            }
            else -> throw IllegalArgumentException("unknown interrupt source")
        }
    }

    protected fun doPollInterrupt(): Int {
        if (source == 0) return 0
        val pin = connection.intPin() ?: intPin
        val asserted = when {
            pin != null -> pin.isHigh() == (compPol == 1)
            source == SOURCE_CONVERSION_READY -> (readReg(REG_CONFIG) and CFG_OS) != 0
            else -> false
        }
        if (!asserted) return 0
        if (source == SOURCE_COMPARATOR && compLat == 1) doClearAlert()
        return source
    }

    protected fun doOnInterrupt(cb: (Int) -> Unit, pin: InputPin?) {
        doOffInterrupt()
        callback = cb
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, if (compPol == 1) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            startPolling(doPollInterrupt())
        }
    }

    protected fun doOffInterrupt() {
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
                    val status = doPollInterrupt()
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
        }, "ads111x-poll").apply { isDaemon = true; start() }
    }

    private fun handleEdge() {
        try {
            val status = doPollInterrupt()
            callback?.invoke(status)
        } catch (_: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
