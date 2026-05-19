package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * MCP4725 — 12-bit single-channel DAC with I²C interface (minimal driver).
 *
 * <p>Outputs a voltage from 0 V to V_DD by writing a 12-bit code over I²C.
 * Only the DAC register is updated; the EEPROM is never touched by this class.
 * All writes use the Fast Write command (2 bytes).
 *
 * <p>Default I²C address: 0x60 (A0 pin = GND), 0x61 (A0 pin = VDD).
 */
@CompileStatic
class Mcp4725Minimal {

    protected final Transport transport
    /** Last code written; used by subclass setPowerDown to preserve the output level. */
    protected int lastCode = 0

    /**
     * Construct the driver.
     *
     * @param transport I²C transport bound to the MCP4725 device address
     */
    Mcp4725Minimal(Transport transport) {
        this.transport = transport
    }

    /**
     * Set the DAC output as a fraction of V_DD using a Fast Write.
     *
     * <p>Fraction is clamped to [0.0, 1.0]. Maps to a 12-bit code via
     * {@code code = round(fraction × 4095)}.
     *
     * @param fraction target output level, 0.0 = 0 V, 1.0 = V_DD
     */
    void setVoltage(double fraction) {
        fraction = Math.max(0.0, Math.min(1.0, fraction))
        setRaw((int) Math.round(fraction * 4095))
    }

    /**
     * Set the DAC output to a raw 12-bit code using a Fast Write.
     *
     * <p>Code is clamped to [0, 4095]. Output voltage = V_DD × code / 4096.
     *
     * @param code 12-bit DAC value (0–4095)
     */
    void setRaw(int code) {
        code = Math.max(0, Math.min(4095, code))
        lastCode = code
        // Fast Write: [0 0 PD1 PD0 D11-D8] [D7-D0]  — PD1=PD0=0 (normal mode)
        transport.write([(byte) ((code >> 8) & 0x0F), (byte) (code & 0xFF)] as byte[])
    }
}
