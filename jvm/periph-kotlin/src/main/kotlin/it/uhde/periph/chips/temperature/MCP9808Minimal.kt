package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * MCP9808 ±0.5 °C maximum accuracy digital temperature sensor (Microchip) —
 * minimal interface.
 *
 * Band-gap temperature sensor with a delta-sigma ADC, read over I²C.
 * Registers are 16-bit, big-endian, addressed through a non-incrementing
 * Register Pointer. Eight selectable addresses (0x18–0x1F) via the
 * A0/A1/A2 strap pins.
 *
 * Construction checks `MANUFACTURER_ID` (0x0054) and the `DEVICE_ID` byte
 * (0x04; the revision byte is ignored) and makes no register writes: the POR
 * default (continuous conversion at 0.0625 °C, Alert output disabled)
 * already serves the primary use case.
 *
 * @param connection configured I²C connection bound to the device (0x18–0x1F)
 * @throws IOException on bus error or identity mismatch
 */
open class MCP9808Minimal(protected val connection: Connection) {

    companion object {
        /** Default I²C address (A0 = A1 = A2 = GND). Valid range 0x18–0x1F. */
        const val DEFAULT_ADDRESS = 0x18

        /** Expected `MANUFACTURER_ID` register value. */
        const val MANUFACTURER_ID = 0x0054

        /** Expected `DEVICE_ID` (upper byte of `DEVICE_ID_REV`). */
        const val DEVICE_ID = 0x04

        // Register pointers.
        const val REG_CONFIG = 0x01
        const val REG_TUPPER = 0x02
        const val REG_TLOWER = 0x03
        const val REG_TCRIT = 0x04
        const val REG_TA = 0x05
        const val REG_MFR_ID = 0x06
        const val REG_DEVICE_ID = 0x07
        const val REG_RESOLUTION = 0x08
    }

    init {
        val mfr = readReg(REG_MFR_ID)
        if (mfr != MANUFACTURER_ID) {
            throw IOException("MCP9808 not found: expected MANUFACTURER_ID 0x0054, got 0x%04X".format(mfr))
        }
        val dev = readReg(REG_DEVICE_ID) shr 8
        if (dev != DEVICE_ID) {
            throw IOException("MCP9808 not found: expected DEVICE_ID 0x04, got 0x%02X".format(dev))
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
     * Read the ambient temperature. Masks off `TA`'s three boundary-status
     * bits and decodes the 13-bit two's-complement value (0.0625 °C per LSB).
     *
     * @return ambient temperature in °C
     */
    fun readTemperature(): Double {
        var raw = readReg(REG_TA) and 0x1FFF
        if (raw and 0x1000 != 0) raw -= 0x2000
        return raw / 16.0
    }
}
