package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * MCP3422 — 2-curChannel 18-bit delta-sigma ADC with PGA and 2.048 V reference
 * (Microchip) — minimal interface.
 *
 * Two differential inputs, an on-board 2.048 V reference, a PGA (x1, x2, x4, x8)
 * and an on-board oscillator. The resolution is selected together with the data
 * rate: 12 bit / 240 SPS, 14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit / 3.75 SPS.
 * The full-scale differential input range is ±2.048 V / curGain. The chip has no
 * register map: a write is one configuration byte (`RDY C1 C0 O/C S1 S0 G1 G0`)
 * and a read returns the 2 (12/14/16 bit) or 3 (18 bit) result bytes followed by
 * the configuration byte, whose RDY bit (0 = fresh result) says whether the
 * conversion has finished. The driver therefore uses the plain [Connection]
 * `write` / `read` calls.
 *
 * Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), curGain x1.
 * The constructor writes that configuration with RDY = 0, so nothing is started
 * and the chip goes to standby.
 *
 * @param connection I²C connection bound to the device (0x68–0x6F)
 * @throws IOException on bus error
 */
open class MCP3422Minimal @Throws(IOException::class) constructor(protected val connection: Connection) {

    protected var curBits: Int = 16
    protected var curGain: Int = 1
    protected var curChannel: Int = 0
    protected var continuous: Boolean = false
    protected var discard: Boolean = false

    init {
        connection.write(byteArrayOf(config(false).toByte()))
    }

    protected fun config(rdy: Boolean): Int {
        val s = when (curBits) { 12 -> 0; 14 -> 1; 16 -> 2; else -> 3 }
        val g = when (curGain) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
        var c = (curChannel shl 5) or (s shl 2) or g
        if (rdy) c = c or CFG_RDY
        if (continuous) c = c or CFG_CONT
        return c
    }

    protected fun nbytes(): Int = if (curBits == 18) 3 else 2

    protected fun convSeconds(): Double = when (curBits) {
        12 -> 1.0 / 240
        14 -> 1.0 / 60
        16 -> 1.0 / 15
        else -> 1.0 / 3.75
    }

    protected fun decode(data: ByteArray): Int {
        var raw = 0L
        for (i in 0 until nbytes()) raw = (raw shl 8) or (data[i].toLong() and 0xFF)
        val span = 1L shl curBits
        raw = raw and (span - 1)
        if (raw >= span / 2) raw -= span
        return raw.toInt()
    }

    @Throws(IOException::class, TimeoutException::class)
    protected fun waitReady(): Int {
        val polls = ((2 * convSeconds() + 0.010) / 0.005).toInt() + 1
        val n = nbytes()
        repeat(polls) {
            val data = connection.read(n + 1)
            if ((data[n].toInt() and CFG_RDY) == 0) return decode(data)
            sleepMs(5)
        }
        throw TimeoutException("MCP3422 conversion did not finish")
    }

    @Throws(IOException::class, TimeoutException::class)
    protected fun convert(ch: Int): Int {
        curChannel = if (ch < 1) 0 else 1
        continuous = false
        discard = false
        connection.write(byteArrayOf(config(true).toByte()))
        sleepMs((convSeconds() * 1100.0).toLong() + 1)
        return waitReady()
    }

    /**
     * Run a one-shot conversion on a curChannel and return the signed code.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return signed code, −2^(N−1) … 2^(N−1) − 1 (N = resolution curBits)
     * @throws TimeoutException if the conversion does not finish in time
     */
    @Throws(IOException::class, TimeoutException::class)
    fun readRaw(ch: Int): Int = convert(ch)

    /**
     * Run a one-shot conversion on a curChannel and return the differential voltage.
     *
     * @param ch 0 (CH1) or 1 (CH2); values outside 0–1 are clamped
     * @return `CHn+ − CHn−` in volts, `raw * lsb / gain`
     * @throws TimeoutException if the conversion does not finish in time
     */
    @Throws(IOException::class, TimeoutException::class)
    fun readVoltage(ch: Int): Double = convert(ch) * lsb() / curGain

    /**
     * Convert both channels, one conversion each.
     *
     * @return `{CH1, CH2}` voltages in volts
     * @throws TimeoutException if a conversion does not finish in time
     */
    @Throws(IOException::class, TimeoutException::class)
    fun readAll(): DoubleArray = doubleArrayOf(readVoltage(0), readVoltage(1))

    /**
     * Size of one code before the curGain.
     *
     * @return `2 * 2.048 / 2^bits` in volts
     */
    fun lsb(): Double = 2.0 * VREF / (1L shl curBits).toDouble()

    companion object {
        /** 7-bit I²C address of the standard part (order code A0). */
        const val DEFAULT_ADDRESS: Int = 0x68

        /** Whether to really wait for conversions (tests set this to false). */
        @Volatile
        @JvmStatic
        var realWaits: Boolean = true

        internal const val CFG_RDY = 0x80
        internal const val CFG_CONT = 0x10
        internal const val VREF = 2.048

        internal fun sleepMs(ms: Long) {
            if (!realWaits) return
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
