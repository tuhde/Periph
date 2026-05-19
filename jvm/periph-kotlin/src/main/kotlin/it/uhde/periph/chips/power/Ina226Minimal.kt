package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport

/**
 * INA226 — 16-bit current/power monitor with I²C interface (minimal driver).
 *
 * Measures shunt voltage, bus voltage, current, and power. Calibration is
 * computed from the shunt resistance and maximum expected current; the result
 * is written to the Calibration register at construction time.
 *
 * Default I²C address: 0x40 (A0=GND, A1=GND).
 *
 * ## Configuration defaults
 * - Mode: continuous shunt+bus (7)
 * - Bus voltage conversion time: 1.1 ms (4)
 * - Shunt voltage conversion time: 1.1 ms (4)
 * - Averaging: 1 sample (0)
 */
open class Ina226Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) {
    protected val currentLsb: Double = maxCurrent / 32768.0
    protected val cal: Int = (0.00512 / (currentLsb * rShunt)).toInt()

    /** Stored MODE bits (2:0) for [Ina226Full.wake]. Updated by configure() and shutdown(). */
    protected var lastMode: Int = 7

    companion object {
        // Register addresses
        const val REG_CONFIG    = 0x00
        const val REG_SHUNT     = 0x01
        const val REG_BUS       = 0x02
        const val REG_POWER     = 0x03
        const val REG_CURRENT   = 0x04
        const val REG_CAL       = 0x05
        const val REG_MASK_EN   = 0x06
        const val REG_ALERT_LIM = 0x07
        const val REG_MFR_ID    = 0xFE
        const val REG_DIE_ID    = 0xFF

        /** Default configuration: mode=7, VBUSCT=4, VSHCT=4, AVG=0 → 0x4127. */
        const val DEFAULT_CONFIG = 0x4127
    }

    init {
        writeReg(REG_CONFIG, DEFAULT_CONFIG)
        writeReg(REG_CAL, cal)
    }

    /**
     * Read the bus voltage.
     *
     * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
     */
    fun voltage(): Double = readReg(REG_BUS) * 1.25e-3

    /**
     * Read the shunt voltage.
     *
     * @return shunt voltage in V (2.5 µV LSB, signed 16-bit)
     */
    fun shuntVoltage(): Double = readRegSigned(REG_SHUNT) * 2.5e-6

    /**
     * Read the current through the shunt resistor.
     *
     * Requires a valid Calibration register value (written in the constructor).
     *
     * @return current in A (signed, Current_LSB per bit)
     */
    fun current(): Double = readRegSigned(REG_CURRENT) * currentLsb

    /**
     * Read the calculated power.
     *
     * Power = 25 × Current_LSB × raw power register (unsigned).
     *
     * @return power in W
     */
    fun power(): Double = readReg(REG_POWER) * 25.0 * currentLsb

    // ---- low-level helpers ----

    /**
     * Write a 16-bit value to a register (big-endian, register-pointer protocol).
     *
     * @param reg register address
     * @param val 16-bit value
     */
    protected fun writeReg(reg: Int, `val`: Int) {
        transport.write(byteArrayOf(
            reg.toByte(),
            (`val` shr 8).toByte(),
            (`val` and 0xFF).toByte()
        ))
    }

    /**
     * Read an unsigned 16-bit value from a register.
     *
     * @param reg register address
     * @return unsigned 16-bit value (0–65535)
     */
    protected fun readReg(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Read a signed 16-bit value from a register.
     *
     * @param reg register address
     * @return signed 16-bit value (-32768–32767)
     */
    protected fun readRegSigned(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toShort().toInt()
    }
}
