package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1013 12-bit I²C ADC with a fixed ±2.048 V range (Texas Instruments) —
 * minimal interface.
 *
 * Smallest member of the ADS101x family: one input (`AIN0` − `AIN1`), no PGA,
 * no multiplexer, no comparator and no `ALERT/RDY` pin. Registers are 16-bit,
 * big-endian, addressed through a persistent Address Pointer. Four selectable
 * addresses (0x48–0x4B) via the `ADDR` strap. The chip has no ID register, so
 * construction performs no I²C traffic.
 *
 * Defaults: 1600 SPS, single-shot. The whole Config word is written for every
 * conversion.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
open class ADS1013Minimal(protected val connection: RegisterConnection) {

    companion object {
        /** Default I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        // Register pointers.
        const val REG_CONVERSION = 0x00
        const val REG_CONFIG = 0x01

        const val CFG_OS = 0x8000
        const val CFG_MODE_SINGLE = 0x0100

        // Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
        const val CFG_RESERVED = 0x0403

        const val LSB = 0.001

        @JvmStatic protected val SPS = intArrayOf(128, 250, 490, 920, 1600, 2400, 3300)
    }

    protected var dr = 4
    protected var continuous = false

    /** Read a 16-bit big-endian register as an unsigned value. */
    protected fun readReg(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf((value shr 8).toByte(), value.toByte()))
    }

    /**
     * Build the whole 16-bit Config word from the cached settings.
     *
     * @param os value of the `OS` bit (start a conversion)
     * @param continuous true for continuous mode (`MODE` = 0)
     */
    protected fun configWord(os: Boolean, continuous: Boolean): Int {
        var word = CFG_RESERVED or (dr shl 5)
        if (os) word = word or CFG_OS
        if (!continuous) word = word or CFG_MODE_SINGLE
        return word
    }

    /** Read the CONVERSION register as a signed 12-bit code (−2048…2047). */
    protected fun readConversion(): Int = Register.toSigned(readReg(REG_CONVERSION), 16) shr 4

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
     * @return voltage `AIN0` − `AIN1` in volts (`raw × 0.001`)
     */
    fun readVoltage(): Double = readRaw() * LSB
}
