package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection

/**
 * LTC4151 — 7–80 V high-side current and voltage monitor (minimal driver).
 *
 * A 12-bit ΔΣ ADC scans the sense voltage (20 µV/LSB), VIN (25 mV/LSB) and ADIN
 * (0.5 mV/LSB) continuously after power-up, so no configuration is needed beyond the
 * sense resistor and the constructor makes no bus access.
 *
 * I²C address: 0x67–0x6F (ADR1/ADR0 three-state pins; 0x6A with both open).
 *
 * ## Configuration (chip power-on state, not rewritten)
 * - CONTROL = 0x0C: continuous scan, page read/write and stuck-bus timer enabled
 * - Sense resistor: 20 mΩ unless given (full-scale current 4.096 A)
 *
 * @param connection I²C connection bound to the LTC4151 device address
 * @param rSense sense resistor in ohms; must be greater than 0
 */
open class Ltc4151Minimal(
    protected val connection: RegisterConnection,
    rSense: Double = 0.02
) {
    companion object {
        // Register addresses
        const val REG_SENSE   = 0x00
        const val REG_VIN     = 0x02
        const val REG_ADIN    = 0x04
        const val REG_CONTROL = 0x06

        const val LSB_SENSE = 20e-6
        const val LSB_VIN   = 0.025
        const val LSB_ADIN  = 0.0005
    }

    protected var rSense: Double = rSense
    protected var pageEnabled: Boolean = true

    init {
        require(rSense > 0) { "rSense must be > 0" }
    }

    /**
     * Read the supply voltage at VIN.
     *
     * @return VIN in V (code × 25 mV)
     */
    fun voltage(): Double = code(readBlock(REG_VIN, 2), 0) * LSB_VIN

    /**
     * Read the load current.
     *
     * @return current in A (sense voltage / rSense)
     */
    fun current(): Double = code(readBlock(REG_SENSE, 2), 0) * LSB_SENSE / rSense

    /**
     * Read the load power from one consistent block read of SENSE and VIN.
     *
     * @return power in W (VIN × current)
     */
    fun power(): Double {
        val d = readBlock(REG_SENSE, 4)
        return code(d, 2) * LSB_VIN * (code(d, 0) * LSB_SENSE / rSense)
    }

    // ---- low-level helpers ----

    /** Extract a 12-bit code (8 MSBs then 4 LSBs in bits 7:4) starting at [offset]. */
    protected fun code(d: ByteArray, offset: Int): Int =
        ((d[offset].toInt() and 0xFF) shl 4) or ((d[offset + 1].toInt() and 0xFF) shr 4)

    /** Read consecutive registers; single-register reads when page read/write is disabled. */
    protected fun readBlock(reg: Int, length: Int): ByteArray {
        if (pageEnabled || length == 1) return connection.read(reg, length)
        return ByteArray(length) { i -> connection.read(reg + i, 1)[0] }
    }

    /** Read the raw CONTROL register. */
    protected fun readControlRaw(): Int = connection.read(REG_CONTROL, 1)[0].toInt() and 0xFF

    /** Write the raw CONTROL register. */
    protected fun writeControlRaw(value: Int) {
        connection.write(REG_CONTROL, byteArrayOf(value.toByte()))
    }
}
