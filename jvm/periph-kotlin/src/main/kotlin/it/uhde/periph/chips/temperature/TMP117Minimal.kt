package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * TMP117 ±0.1 °C high-accuracy, low-power digital temperature sensor (Texas
 * Instruments) — minimal interface.
 *
 * NIST-traceable 16-bit temperature sensor (0.0078125 °C per LSB) read over
 * an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian, addressed
 * through a non-incrementing Register Pointer. Four selectable addresses
 * (0x48–0x4B) via the 4-level ADD0 strap.
 *
 * Construction checks `DEVICE_ID` bits 11:0 (0x117; the revision nibble is
 * ignored) and makes no register writes: the POR/EEPROM default (continuous
 * conversion, 8-conversion averaging, 1 s cycle, Alert mode) already serves
 * the primary use case.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 * @throws IOException on bus error or identity mismatch
 */
open class TMP117Minimal(protected val connection: Connection) {

    companion object {
        /** Default I²C address (ADD0 = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48

        /** Expected `DEVICE_ID` bits 11:0 (bits 15:12 are the silicon revision). */
        const val DEVICE_ID = 0x117

        // Register pointers.
        const val REG_TEMP_RESULT = 0x00
        const val REG_CONFIG = 0x01
        const val REG_THIGH = 0x02
        const val REG_TLOW = 0x03
        const val REG_EEPROM_UL = 0x04
        const val REG_EEPROM1 = 0x05
        const val REG_EEPROM2 = 0x06
        const val REG_TEMP_OFFSET = 0x07
        const val REG_EEPROM3 = 0x08
        const val REG_DEVICE_ID = 0x0F

        /** Temperature LSB in °C, shared by TEMP_RESULT, the limits and TEMP_OFFSET. */
        const val LSB_C = 0.0078125

        /** Decode a 16-bit two's-complement temperature register (0.0078125 °C per LSB). */
        internal fun decodeTemperature(raw: Int): Double = raw.toShort().toInt() * LSB_C
    }

    init {
        val did = readReg(REG_DEVICE_ID) and 0x0FFF
        if (did != DEVICE_ID) {
            throw IOException("TMP117 not found: expected DEVICE_ID 0x117, got 0x%03X".format(did))
        }
    }

    /** Read a 16-bit big-endian register as an unsigned value. */
    protected fun readReg(reg: Int): Int {
        val b = connection.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /** Write a 16-bit big-endian register. */
    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), (value shr 8).toByte(), value.toByte()))
    }

    /**
     * Read the temperature. Decodes `TEMP_RESULT`'s 16-bit two's-complement
     * value (0.0078125 °C per LSB). Returns −256.0 until the first conversion
     * after power-up completes.
     *
     * @return temperature in °C
     */
    fun readTemperature(): Double = decodeTemperature(readReg(REG_TEMP_RESULT))
}
