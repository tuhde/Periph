package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import kotlin.math.floor

/**
 * CAT5171 — 256-position I²C digital potentiometer (ON Semiconductor) —
 * minimal interface.
 *
 * A single 3-terminal potentiometer (A, W, B) usable as a voltage divider or
 * 2-terminal rheostat. The chip has no register map: a write is
 * `[instruction, data]` where the instruction byte carries the
 * midscale-reset flag (`RS`, bit 6) and the shutdown flag (`SD`, bit 5); a
 * read is a bare one-byte read returning the wiper register. The driver
 * therefore uses the plain [Connection] `write` / `read` calls. The I²C
 * address is 0x2C (AD0 = GND) or 0x2D (AD0 = VDD). The wiper is volatile
 * (power-up midscale 0x80).
 *
 * Resistances follow `R_WB = D/256 · RAB + RW`. Construction performs no bus
 * traffic.
 *
 * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
 * @param rAb end-to-end resistance RAB in Ω
 * @param rWiper wiper resistance RW in Ω
 */
open class CAT5171Minimal(
    protected val connection: Connection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) {

    companion object {
        /** 7-bit I²C address with AD0 = GND. */
        const val DEFAULT_ADDRESS = 0x2C

        /** 7-bit I²C address with AD0 = VDD. */
        const val ADDRESS_AD0_HIGH = 0x2D

        /** Default end-to-end resistance RAB in Ω (100 kΩ part). */
        const val DEFAULT_RAB = 100000.0

        /** Default wiper resistance RW in Ω (typical, 5 V). */
        const val DEFAULT_RW = 50.0

        const val INSTR_RS = 0x40
        const val INSTR_SD = 0x20
        const val MIDSCALE = 0x80

        internal fun clampCode(code: Int): Int = maxOf(0, minOf(255, code))
    }

    protected var rAb: Double = rAb
    protected var rW: Double = rWiper
    protected var code: Int = MIDSCALE
    protected var sd: Boolean = false

    protected fun writeInstr(instruction: Int, value: Int) {
        connection.write(byteArrayOf(instruction.toByte(), value.toByte()))
    }

    protected fun writeCode(value: Int) {
        val c = clampCode(value)
        writeInstr(if (sd) INSTR_SD else 0, c)
        code = c
    }

    protected fun readCode(): Int {
        val c = connection.read(1)[0].toInt() and 0xFF
        code = c
        return c
    }

    /**
     * Set the wiper position.
     *
     * @param value wiper position 0–255 (clamped)
     */
    fun setWiper(value: Int) {
        writeCode(value)
    }

    /**
     * Read back the wiper position with a single read transaction.
     *
     * @return wiper position 0–255
     */
    fun getWiper(): Int = readCode()

    /**
     * Set the W–B resistance to the nearest reachable code.
     *
     * @param ohms target W–B resistance in Ω (clamped to the reachable range)
     */
    fun setResistance(ohms: Double) {
        writeCode(floor((ohms - rW) / rAb * 256.0 + 0.5).toInt())
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @return resistance in Ω, `D/256 · RAB + RW`
     */
    fun getResistance(): Double = readCode() / 256.0 * rAb + rW
}
