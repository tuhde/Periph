package it.uhde.periph.chips.light

import it.uhde.periph.connection.Connection

/**
 * BH1750FVI — full driver: raw codes, one-time measurements in each mode, continuous measurement,
 * power-down/power-on, reset, the measurement-time register (MTreg) and sensitivity adjustment.
 * Extends [Bh1750Minimal]. [read] uses the mode of the last [measureOnce] or [startContinuous]
 * call (H-resolution by default).
 */
class Bh1750Full(connection: Connection) : Bh1750Minimal(connection) {

    private fun checkMode(mode: Int) {
        require(mode in MODE_H..MODE_L) { "BH1750 mode must be MODE_H, MODE_H2 or MODE_L" }
    }

    /** Run one one-time measurement in the current mode; the raw 16-bit count. */
    fun readRaw(): Int = measureRaw(mode)

    /**
     * Run one one-time measurement.
     *
     * @param mode [Bh1750Minimal.MODE_H] (1 lx), [Bh1750Minimal.MODE_H2] (0.5 lx) or [Bh1750Minimal.MODE_L] (4 lx)
     * @return illuminance in lux
     */
    fun measureOnce(mode: Int = MODE_H): Double {
        checkMode(mode)
        this.mode = mode
        return toLux(measureRaw(mode), mode)
    }

    /** Start continuous measurements; the first result is ready after [measurementTime]. */
    fun startContinuous(mode: Int = MODE_H) {
        checkMode(mode)
        this.mode = mode
        command(OP_CONTINUOUS[mode])
    }

    /** Read the latest continuous-mode result without waiting; lux. */
    fun readContinuous(): Double = toLux(readWord(), mode)

    /** Enter power-down (opcode 0x00). */
    fun powerDown() = command(OP_POWER_DOWN)

    /** Leave power-down and wait for a measurement command (opcode 0x01). */
    fun powerOn() = command(OP_POWER_ON)

    /** Clear the illuminance data register (opcode 0x07; powers on first, as reset is ignored in power-down). */
    fun reset() {
        command(OP_POWER_ON)
        command(OP_RESET)
    }

    /**
     * Write the measurement-time register (two opcodes, high bits then low bits).
     *
     * @param mtreg register value, 31–254 (default 69); [IllegalArgumentException] otherwise
     */
    fun setMeasurementTime(mtreg: Int) {
        require(mtreg in 31..254) { "BH1750 MTreg must be 31-254" }
        command(OP_MT_HIGH or (mtreg shr 5))
        command(OP_MT_LOW or (mtreg and 0x1F))
        this.mtreg = mtreg
    }

    /** The last written MTreg (the chip has no read-back; 69 after init). */
    fun getMeasurementTimeRegister(): Int = mtreg

    /** Set the sensitivity relative to the default (MTreg = round(69 × factor), factor 0.45–3.68). */
    fun setSensitivity(factor: Double) {
        setMeasurementTime(Math.round(DEFAULT_MTREG * factor).toInt())
    }

    /** Maximum measurement time of a mode at the current MTreg, in seconds. */
    fun measurementTime(mode: Int = MODE_H): Double {
        checkMode(mode)
        return T_MAX_MS[mode] * mtreg / DEFAULT_MTREG / 1000.0
    }

    /** Illuminance represented by one raw count at the current MTreg, in lux. */
    fun luxPerCount(mode: Int = MODE_H): Double {
        checkMode(mode)
        return toLux(1, mode)
    }
}
