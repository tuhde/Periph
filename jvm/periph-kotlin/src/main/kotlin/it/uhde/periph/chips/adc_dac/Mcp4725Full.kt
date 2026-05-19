package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport
import kotlin.math.roundToInt

/**
 * MCP4725 — full driver. Extends [Mcp4725Minimal] with EEPROM persistence,
 * power-down control, read-back, and General Call commands (reset / wake-up).
 *
 * General Call commands ([reset], [wakeUp]) require a second transport bound to
 * address 0x00. Pass `null` to disable those methods.
 *
 * ## EEPROM write timing
 * The EEPROM write takes up to 50 ms. New write commands are silently ignored
 * while RDY/BSY is low. Poll [isEepromReady] or wait 50 ms before issuing a
 * second EEPROM write.
 */
class Mcp4725Full(
    transport: Transport,
    private val generalCall: Transport?
) : Mcp4725Minimal(transport) {

    /**
     * Read-back state of the MCP4725.
     *
     * @property code current DAC register value (0–4095)
     * @property voltageFraction DAC register value as fraction of V_DD (code / 4095.0)
     * @property powerDown current power-down mode of the DAC register (0–3)
     * @property eepromCode EEPROM-stored DAC value (0–4095)
     * @property eepromPowerDown EEPROM-stored power-down mode (0–3)
     * @property eepromReady true when EEPROM write is complete (RDY/BSY = 1)
     */
    data class ReadResult(
        val code: Int,
        val voltageFraction: Double,
        val powerDown: Int,
        val eepromCode: Int,
        val eepromPowerDown: Int,
        val eepromReady: Boolean
    )

    /**
     * Write fraction to both the DAC register and EEPROM.
     *
     * @param fraction target output level, 0.0–1.0
     */
    fun setVoltageEeprom(fraction: Double) {
        setRawEeprom((fraction.coerceIn(0.0, 1.0) * 4095).roundToInt())
    }

    /**
     * Write a raw 12-bit code to both the DAC register and EEPROM.
     *
     * The value persists across power cycles. Wait up to 50 ms before
     * issuing another EEPROM write.
     *
     * @param code 12-bit DAC value (0–4095)
     */
    fun setRawEeprom(code: Int) {
        val c = code.coerceIn(0, 4095)
        lastCode = c
        // Write DAC + EEPROM: C2=0 C1=1 C0=1 → byte1=0x60 (normal mode)
        transport.write(byteArrayOf(
            0x60,
            ((c shr 4) and 0xFF).toByte(),
            ((c shl 4) and 0xF0).toByte()
        ))
    }

    /**
     * Read the current state of the device (5-byte read response).
     *
     * @return snapshot of DAC register, EEPROM, power-down, and ready flag
     */
    fun read(): ReadResult {
        val b = transport.read(5)
        val eepromReady = (b[0].toInt() and 0x80) != 0
        val powerDown   = (b[0].toInt() shr 2) and 0x03
        val code        = ((b[1].toInt() and 0xFF) shl 4) or ((b[2].toInt() and 0xFF) shr 4)
        val eepromPD    = (b[3].toInt() shr 5) and 0x03
        val eepromCode  = ((b[3].toInt() and 0x0F) shl 8) or (b[4].toInt() and 0xFF)
        return ReadResult(code, code / 4095.0, powerDown, eepromCode, eepromPD, eepromReady)
    }

    /**
     * Enter or exit power-down mode using a Fast Write.
     *
     * The last-written DAC code is preserved so the output resumes at the same
     * level when mode 0 is selected again.
     *
     * @param mode 0 = normal, 1 = 1 kΩ to GND, 2 = 100 kΩ to GND, 3 = 500 kΩ to GND
     */
    fun setPowerDown(mode: Int) {
        val m = mode.coerceIn(0, 3)
        // Fast Write with PD bits set: [0 0 PD1 PD0 D11-D8] [D7-D0]
        transport.write(byteArrayOf(
            (((m and 0x03) shl 4) or ((lastCode shr 8) and 0x0F)).toByte(),
            (lastCode and 0xFF).toByte()
        ))
    }

    /**
     * Send General Call Wake-Up (address 0x00, command 0x09).
     *
     * Clears the power-down bits in the DAC register of all MCP47xx devices
     * on the bus that support General Call.
     *
     * @throws IllegalStateException if no General Call transport was provided
     */
    fun wakeUp() {
        requireGeneralCall().write(byteArrayOf(0x09))
    }

    /**
     * Send General Call Reset (address 0x00, command 0x06).
     *
     * Triggers an internal power-on reset on all MCP47xx devices on the bus
     * that support General Call, reloading EEPROM contents into the DAC register.
     *
     * @throws IllegalStateException if no General Call transport was provided
     */
    fun reset() {
        requireGeneralCall().write(byteArrayOf(0x06))
    }

    /**
     * Read the RDY/BSY bit.
     *
     * @return true when the EEPROM write is complete
     */
    fun isEepromReady(): Boolean =
        (transport.read(1)[0].toInt() and 0x80) != 0

    private fun requireGeneralCall(): Transport =
        generalCall ?: throw IllegalStateException("General Call transport not configured")
}
