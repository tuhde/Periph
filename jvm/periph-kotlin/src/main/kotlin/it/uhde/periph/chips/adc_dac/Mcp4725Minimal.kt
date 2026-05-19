package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport
import kotlin.math.roundToInt

/**
 * MCP4725 — 12-bit single-channel DAC with I²C interface (minimal driver).
 *
 * Outputs a voltage from 0 V to V_DD by writing a 12-bit code over I²C.
 * Only the DAC register is updated; the EEPROM is never touched by this class.
 * All writes use the Fast Write command (2 bytes).
 *
 * Default I²C address: 0x60 (A0 pin = GND), 0x61 (A0 pin = VDD).
 */
open class Mcp4725Minimal(protected val transport: Transport) {

    /** Last code written; used by subclass [Mcp4725Full.setPowerDown] to preserve the output level. */
    protected var lastCode: Int = 0

    /**
     * Set the DAC output as a fraction of V_DD using a Fast Write.
     *
     * Fraction is clamped to [0.0, 1.0]. Maps to a 12-bit code via
     * `code = round(fraction × 4095)`.
     *
     * @param fraction target output level, 0.0 = 0 V, 1.0 = V_DD
     */
    fun setVoltage(fraction: Double) {
        setRaw((fraction.coerceIn(0.0, 1.0) * 4095).roundToInt())
    }

    /**
     * Set the DAC output to a raw 12-bit code using a Fast Write.
     *
     * Code is clamped to [0, 4095]. Output voltage = V_DD × code / 4096.
     *
     * @param code 12-bit DAC value (0–4095)
     */
    fun setRaw(code: Int) {
        val c = code.coerceIn(0, 4095)
        lastCode = c
        // Fast Write: [0 0 PD1 PD0 D11-D8] [D7-D0]  — PD1=PD0=0 (normal mode)
        transport.write(byteArrayOf(
            ((c shr 8) and 0x0F).toByte(),
            (c and 0xFF).toByte()
        ))
    }
}
