package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * TMP102 12-bit digital temperature sensor with ALERT output (Texas
 * Instruments) — minimal interface.
 *
 * Two-wire temperature sensor (0.0625 °C per LSB, ±0.5 °C typical) read over
 * I²C. Registers are 16-bit, big-endian, addressed through a persistent
 * Register Pointer. Four selectable addresses (0x48–0x4B) via the `ADD0`
 * strap.
 *
 * Construction makes no bus access: the power-on default (continuous
 * conversion at 4 Hz, comparator mode, 12-bit) already serves the primary use
 * case.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
open class TMP102Minimal(protected val connection: RegisterConnection) {

    companion object {
        /** Default I²C address (`ADD0` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        // Register pointers.
        const val REG_TEMPERATURE = 0x00
        const val REG_CONFIG = 0x01
        const val REG_T_LOW = 0x02
        const val REG_T_HIGH = 0x03

        const val LSB_C = 0.0625

        /** Decode a TEMPERATURE register value; bit 0 = 1 marks the 13-bit extended format. */
        internal fun decodeRaw(raw16: Int): Int =
            if (raw16 and 0x0001 != 0) Register.toSigned(raw16 shr 3, 13) else Register.toSigned(raw16 shr 4, 12)
    }

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
     * Read the temperature. Decodes the 12-bit or, in extended mode, 13-bit
     * two's-complement value; the format is taken from bit 0 of the result.
     *
     * @return temperature in °C
     */
    fun readTemperature(): Double = decodeRaw(readReg(REG_TEMPERATURE)) * LSB_C
}
