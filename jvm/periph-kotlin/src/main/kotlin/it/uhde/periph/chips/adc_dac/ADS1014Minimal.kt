package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * ADS1014 12-bit I²C ADC with PGA and programmable comparator (Texas
 * Instruments) — minimal interface.
 *
 * Single-input (`AIN0` − `AIN1`) ΔΣ ADC with internal reference and
 * oscillator. Registers are 16-bit, big-endian, addressed through a
 * persistent Address Pointer. Four selectable addresses (0x48–0x4B) via the
 * `ADDR` strap. The chip has no ID register, so construction performs no I²C
 * traffic.
 *
 * Defaults: ±2.048 V range (1 mV/LSB), 1600 SPS, single-shot, comparator
 * disabled. The whole Config word is written for every conversion.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
open class ADS1014Minimal(protected val connection: RegisterConnection) {

    companion object {
        /** Default I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        // Register pointers.
        const val REG_CONVERSION = 0x00
        const val REG_CONFIG = 0x01
        const val REG_LO_THRESH = 0x02
        const val REG_HI_THRESH = 0x03

        const val CFG_OS = 0x8000
        const val CFG_MODE_SINGLE = 0x0100
        const val QUEUE_DISABLE = 3

        /** Interrupt source: a conversion finished. */
        const val SOURCE_CONVERSION_READY = 0x01

        /** Interrupt source: the result is beyond the comparator thresholds. */
        const val SOURCE_COMPARATOR = 0x02

        @JvmStatic protected val FULL_SCALE = doubleArrayOf(6.144, 4.096, 2.048, 1.024, 0.512, 0.256)
        @JvmStatic protected val SPS = intArrayOf(128, 250, 490, 920, 1600, 2400, 3300)
    }

    protected var pga = 2
    protected var dr = 4
    protected var continuous = false
    protected var compMode = 0
    protected var compPol = 0
    protected var compLat = false
    protected var queue = 0
    protected var source = 0

    /** Read a 16-bit big-endian register as an unsigned value. */
    protected fun readReg(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf((value shr 8).toByte(), value.toByte()))
    }

    private fun compQue(): Int = when (source) {
        SOURCE_CONVERSION_READY -> 0
        SOURCE_COMPARATOR -> queue
        else -> QUEUE_DISABLE
    }

    /**
     * Build the whole 16-bit Config word from the cached settings.
     *
     * @param os value of the `OS` bit (start a conversion)
     * @param continuous true for continuous mode (`MODE` = 0)
     */
    protected fun configWord(os: Boolean, continuous: Boolean): Int {
        var word = if (os) CFG_OS else 0
        word = word or (pga shl 9)
        if (!continuous) word = word or CFG_MODE_SINGLE
        word = word or (dr shl 5)
        word = word or (compMode shl 4)
        word = word or (compPol shl 3)
        word = word or ((if (compLat) 1 else 0) shl 2)
        return word or compQue()
    }

    /** Read the CONVERSION register as a signed 12-bit code (−2048…2047). */
    protected fun readConversion(): Int = Register.toSigned(readReg(REG_CONVERSION), 16) shr 4

    /** Size of one conversion code at the current gain: `full_scale / 2048` volts. */
    protected fun lsbVolts(): Double = FULL_SCALE[pga] / 2048.0

    /**
     * Run one single-shot conversion of `AIN0` − `AIN1`. Writes the whole
     * Config word with `OS` = 1, polls `OS` until the chip is idle again, then
     * reads `CONVERSION`.
     *
     * @return 12-bit two's-complement code, −2048…2047
     */
    fun readRaw(): Int {
        writeReg(REG_CONFIG, configWord(true, false))
        continuous = false
        for (i in 0 until 100) {
            if (readReg(REG_CONFIG) and CFG_OS != 0) break
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return readConversion()
    }

    /**
     * Run one single-shot conversion and convert it to volts.
     *
     * @return voltage `AIN0` − `AIN1` in volts (`raw × full-scale / 2048`)
     */
    fun readVoltage(): Double = readRaw() * lsbVolts()
}
