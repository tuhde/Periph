package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport

/**
 * INA219 — zero-drift, bidirectional current/power monitor with I²C interface
 * (minimal driver).
 *
 * Measures bus voltage (0–32 V), shunt voltage, current, and power via a
 * sense resistor on the high or low side of the load. The calibration register
 * is computed and written once during construction; the configuration register
 * is left at its hardware reset value (0x399F: ±32 V bus, PGA /8, 12-bit,
 * continuous shunt+bus mode).
 *
 * Default I²C address: 0x40 (A1=GND, A0=GND).
 *
 * @param transport  I²C transport bound to the INA219 device address
 * @param rShunt     shunt resistance in Ω (default 0.1)
 * @param maxCurrent maximum expected current in A (default 2.0)
 */
open class Ina219Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    protected val rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) {
    /** Current register LSB in A/LSB. */
    protected val currentLsb: Double = maxCurrent / 32768.0

    /** Power register LSB in W/LSB (= 20 × currentLsb). */
    protected val powerLsb: Double = 20.0 * currentLsb

    companion object {
        const val REG_CONFIG    = 0x00
        const val REG_SHUNT_V   = 0x01
        const val REG_BUS_V     = 0x02
        const val REG_POWER     = 0x03
        const val REG_CURRENT   = 0x04
        const val REG_CALIBRATE = 0x05
    }

    init {
        val cal = (0.04096 / (currentLsb * rShunt)).toInt() and 0xFFFE
        writeReg(REG_CALIBRATE, cal)
    }

    /**
     * Read the bus voltage.
     *
     * Reads the Bus Voltage register (0x02), right-shifts by 3, and
     * multiplies by the 4 mV LSB. Range: 0–32 V.
     *
     * @return bus voltage in V
     */
    fun voltage(): Double {
        val raw = readReg(REG_BUS_V)
        return ((raw shr 3) and 0x1FFF) * 4e-3
    }

    /**
     * Read the shunt (differential) voltage.
     *
     * Reads the Shunt Voltage register (0x01) as a signed 16-bit value and
     * multiplies by the 10 µV LSB.
     *
     * @return shunt voltage in V (negative for reverse current flow)
     */
    fun shuntVoltage(): Double {
        val raw = readReg(REG_SHUNT_V)
        return raw.toShort() * 10e-6
    }

    /**
     * Read the current through the shunt resistor.
     *
     * Reads the Current register (0x04) as a signed 16-bit value and
     * multiplies by the current LSB computed during construction.
     *
     * @return current in A (negative for reverse flow)
     */
    fun current(): Double {
        val raw = readReg(REG_CURRENT)
        return raw.toShort() * currentLsb
    }

    /**
     * Read the calculated power.
     *
     * Reads the Power register (0x03) as an unsigned 16-bit value and
     * multiplies by the power LSB (= 20 × current LSB).
     *
     * @return power in W (always non-negative)
     */
    fun power(): Double {
        val raw = readReg(REG_POWER)
        return (raw and 0xFFFF) * powerLsb
    }

    // -------------------------------------------------------------------------
    // Protected register I/O helpers (shared with Ina219Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @return raw unsigned 16-bit value
     */
    protected fun readReg(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @param val 16-bit value to write
     */
    protected fun writeReg(reg: Int, `val`: Int) {
        transport.write(byteArrayOf(
            reg.toByte(),
            ((`val` shr 8) and 0xFF).toByte(),
            (`val` and 0xFF).toByte()
        ))
    }
}
