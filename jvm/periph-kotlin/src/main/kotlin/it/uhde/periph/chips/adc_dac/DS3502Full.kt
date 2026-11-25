package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.roundToInt

/**
 * DS3502 full interface — extends [DS3502Minimal] with ratio / resistance
 * helpers, increment, EEPROM storage of the power-up value, MODE control and
 * acknowledge polling.
 *
 * @param connection I²C register connection bound to the device (0x28–0x2B)
 * @param vHigh voltage applied to RH, in volts
 * @param vLow voltage applied to RL, in volts
 */
class DS3502Full(
    connection: RegisterConnection,
    vHigh: Double = 1.0,
    vLow: Double = 0.0,
) : DS3502Minimal(connection, vHigh, vLow) {

    private companion object {
        /** Nominal end-to-end resistance in Ω (±20 %). */
        const val R_TOTAL = 10000.0
    }

    /**
     * Set the wiper by divider ratio V(RW−RL) / V(RH−RL).
     *
     * @param ratio 0.0–1.0; the code is `round(ratio * 127)`
     */
    fun setRatio(ratio: Double) = writeCode((ratio * MAX_CODE).roundToInt())

    /**
     * Read the divider ratio.
     *
     * @return `code / 127`
     */
    fun getRatio(): Double = readCode().toDouble() / MAX_CODE

    /**
     * Nominal wiper resistance for the current code (RTOTAL = 10 kΩ ±20 %).
     *
     * @param terminal `'L'` for wiper-to-RL, `'H'` for wiper-to-RH
     * @return resistance in Ω (approximate; excludes the wiper resistance)
     * @throws IllegalArgumentException if terminal is not 'L' or 'H'
     */
    fun getResistance(terminal: Char = 'L'): Double {
        require(terminal == 'L' || terminal == 'H') { "terminal must be 'L' or 'H'" }
        val c = readCode()
        return if (terminal == 'L') c.toDouble() / MAX_CODE * R_TOTAL else (MAX_CODE - c).toDouble() / MAX_CODE * R_TOTAL
    }

    /**
     * Move the wiper relative to its current position (SRAM only).
     *
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–127
     */
    fun increment(steps: Int = 1): Int {
        val c = clampCode(readCode() + steps)
        writeCode(c)
        return c
    }

    /**
     * Write the Initial Value Register (EEPROM power-up position). Sets MODE = 0,
     * writes WR / IVR, waits for the EEPROM write (acknowledge polling, ≤ 20 ms)
     * and restores MODE = 1, also on error. The wiper moves to [value]. Each call
     * counts toward the EEPROM endurance (50 000 writes at 70 °C, 200 000 at 25 °C).
     *
     * @param value position 0–127 to store; default the current wiper position
     * @throws IOException if the EEPROM write does not finish in time
     */
    fun storeWiper(value: Int? = null) {
        val c = if (value == null) readCode() else clampCode(value)
        writeReg(REG_CR, CR_MODE_EEPROM)
        try {
            writeReg(REG_WR, c)
            code = c
            waitReady()
        } finally {
            writeReg(REG_CR, CR_MODE_SRAM)
        }
    }

    /**
     * Select what a write to the wiper register updates (advanced; [storeWiper] handles it).
     *
     * @param eeprom true for MODE = 0 (writes also program the IVR), false for MODE = 1 (SRAM only)
     */
    fun setMode(eeprom: Boolean) = writeReg(REG_CR, if (eeprom) CR_MODE_EEPROM else CR_MODE_SRAM)

    /**
     * Read the MODE bit of the Control Register.
     *
     * @return true if MODE = 0 (writes reach the EEPROM)
     */
    fun getMode(): Boolean = (readReg(REG_CR) and CR_MODE_SRAM) == 0

    /**
     * Acknowledge polling: repeat a read of CR until the chip ACKs (it NACKs
     * while an EEPROM write is in progress), waiting 1 ms between attempts.
     *
     * @param timeoutMs maximum time to wait, in milliseconds
     * @throws IOException if the chip still NACKs after [timeoutMs]
     */
    fun waitReady(timeoutMs: Int = 30) {
        for (i in 0..timeoutMs) {
            try {
                readReg(REG_CR)
                return
            } catch (e: IOException) {
                sleepMs(1)
            }
        }
        throw IOException("DS3502 busy: EEPROM write did not finish")
    }

    /**
     * Change the RH / RL voltages used by the conversions (no bus traffic).
     *
     * @param vHigh voltage applied to RH, in volts
     * @param vLow voltage applied to RL, in volts
     */
    fun setEndpoints(vHigh: Double, vLow: Double = 0.0) {
        this.vHigh = vHigh
        this.vLow = vLow
    }
}
