package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection

/**
 * INA260 — 36 V current/voltage/power monitor with integrated 2 mΩ shunt (minimal driver).
 *
 * The shunt is integrated and the Current, Bus Voltage and Power registers have
 * fixed LSBs (1.25 mA, 1.25 mV, 10 mW), so no calibration is needed and the
 * constructor makes no bus access.
 *
 * Default I²C address: 0x40 (A0=GND, A1=GND); range 0x40–0x4F.
 *
 * ## Configuration (chip power-on state, not rewritten)
 * - Mode: continuous shunt+bus (7)
 * - Bus voltage conversion time: 1.1 ms (4)
 * - Shunt current conversion time: 1.1 ms (4)
 * - Averaging: 1 sample (0)
 */
open class Ina260Minimal(
    protected val connection: RegisterConnection
) {
    companion object {
        // Register addresses
        const val REG_CONFIG    = 0x00
        const val REG_CURRENT   = 0x01
        const val REG_BUS       = 0x02
        const val REG_POWER     = 0x03
        const val REG_MASK_EN   = 0x06
        const val REG_ALERT_LIM = 0x07
        const val REG_MFR_ID    = 0xFE
        const val REG_DIE_ID    = 0xFF
    }

    /**
     * Read the bus voltage.
     *
     * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
     */
    fun voltage(): Double = readReg(REG_BUS) * 1.25e-3

    /**
     * Read the current through the integrated shunt.
     *
     * @return current in A (1.25 mA LSB, signed; positive flows IN+ to IN−)
     */
    fun current(): Double = readRegSigned(REG_CURRENT) * 1.25e-3

    /**
     * Read the power.
     *
     * @return power in W (10 mW LSB, unsigned)
     */
    fun power(): Double = readReg(REG_POWER) * 10e-3

    // ---- low-level helpers ----

    /**
     * Write a 16-bit value to a register (big-endian, register-pointer protocol).
     *
     * @param reg register address
     * @param val 16-bit value
     */
    protected fun writeReg(reg: Int, `val`: Int) {
        connection.write(reg, byteArrayOf((`val` shr 8).toByte(),
            (`val` and 0xFF).toByte()))
    }

    /**
     * Read an unsigned 16-bit value from a register.
     *
     * @param reg register address
     * @return unsigned 16-bit value (0–65535)
     */
    protected fun readReg(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Read a signed 16-bit value from a register.
     *
     * @param reg register address
     * @return signed 16-bit value (-32768–32767)
     */
    protected fun readRegSigned(reg: Int): Int {
        val b = connection.read(reg, 2)
        return (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toShort().toInt()
    }
}
