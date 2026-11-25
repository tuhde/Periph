package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import kotlin.math.floor

/**
 * CAT5171 — full interface: extends [CAT5171Minimal] with ratio and W–A
 * helpers, increment/decrement, midscale, shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 *
 * @param connection configured I²C connection bound to the device (0x2C or 0x2D)
 * @param rAb end-to-end resistance RAB in Ω
 * @param rWiper wiper resistance RW in Ω
 */
class CAT5171Full(
    connection: Connection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) : CAT5171Minimal(connection, rAb, rWiper) {

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param ratio 0.0–1.0; the code is `round(ratio × 256)` clamped to 255
     */
    fun setRatio(ratio: Double) {
        writeCode(floor(ratio * 256.0 + 0.5).toInt())
    }

    /**
     * Read the divider ratio.
     *
     * @return `code / 256`
     */
    fun getRatio(): Double = readCode() / 256.0

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param ohms target W–A resistance in Ω
     */
    fun setResistanceWa(ohms: Double) {
        val steps = floor((ohms - rW) / rAb * 256.0 + 0.5).toInt()
        writeCode(256 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @return resistance in Ω, `(256 − D)/256 · RAB + RW`
     */
    fun getResistanceWa(): Double = (256 - readCode()) / 256.0 * rAb + rW

    /**
     * Move the wiper relative to its current position.
     *
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     */
    fun increment(steps: Int = 1): Int {
        val c = clampCode(readCode() + steps)
        writeCode(c)
        return c
    }

    /** Move the wiper to midscale (0x80) with the RS instruction bit; SD is kept if shut down. */
    fun midscale() {
        writeInstr(INSTR_RS or (if (sd) INSTR_SD else 0), MIDSCALE)
        code = MIDSCALE
    }

    /** Shut down: terminal A opens and the wiper shorts to B; the wiper register is kept. */
    fun shutdown() {
        sd = true
        writeInstr(INSTR_SD, code)
    }

    /** Leave shutdown (SD = 0), rewriting the tracked code. */
    fun resume() {
        sd = false
        writeInstr(0, code)
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @return true if the device was shut down by this driver
     */
    fun isShutdown(): Boolean = sd

    /** Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared. */
    fun reset() {
        sd = false
        writeInstr(INSTR_RS, MIDSCALE)
        code = MIDSCALE
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω; null keeps the current value
     */
    fun setRab(rAb: Double, rWiper: Double? = null) {
        this.rAb = rAb
        if (rWiper != null) this.rW = rWiper
    }
}
