package it.uhde.periph.chips.display

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * PCF8576 — full driver. Extends [Pcf8576Minimal] with drive mode, bias,
 * blink configuration, RAM bank selection, and subaddress counter control
 * for cascaded displays.
 *
 * ## Drive mode constants
 * [BACKPLANES_1], [BACKPLANES_2], [BACKPLANES_3], [BACKPLANES_4]
 *
 * ## Bias constants
 * [BIAS_1_3_FULL], [BIAS_1_2_FULL]
 *
 * ## Blink constants
 * [BLINK_OFF], [BLINK_2_HZ], [BLINK_1_HZ], [BLINK_0_5_HZ]
 *
 * ## Bank constants
 * [BANK_0], [BANK_1]
 */
class Pcf8576Full(transport: Transport) : Pcf8576Minimal(transport) {

    companion object {
        const val BLINK_OFF     = 0
        const val BLINK_2_HZ    = 1
        const val BLINK_1_HZ    = 2
        const val BLINK_0_5_HZ  = 3

        const val BIAS_1_3_FULL = 0
        const val BIAS_1_2_FULL = 1

        const val BACKPLANES_1 = 1
        const val BACKPLANES_2 = 2
        const val BACKPLANES_3 = 3
        const val BACKPLANES_4 = 4

        const val BANK_0 = 0
        const val BANK_1 = 1
    }

    private var enabled: Boolean = true
    private var bias: Int = BIAS_1_3_FULL

    private fun modeCode(backplanes: Int): Int = when (backplanes) {
        BACKPLANES_1 -> MODE_STATIC
        BACKPLANES_2 -> MODE_1_2
        BACKPLANES_3 -> MODE_1_3
        else         -> MODE_1_4
    }

    private fun applyMode() {
        val biasBits = if (bias == BIAS_1_2_FULL) BIAS_1_2 else BIAS_1_3
        sendCommands(cmdMode(enabled, biasBits, modeCode(backplanes)))
    }

    /**
     * Turn the display on (E = 1). RAM contents are preserved.
     */
    fun enable() {
        enabled = true
        applyMode()
    }

    /**
     * Blank the display output (E = 0). RAM contents are preserved.
     */
    fun disable() {
        enabled = false
        applyMode()
    }

    /**
     * Reconfigure drive mode and bias at runtime.
     *
     * @param backplanes number of backplanes — 1 (static), 2 (1:2), 3 (1:3), 4 (1:4 multiplex)
     * @param bias       0 = 1/3 bias (recommended for 1:3 and 1:4 multiplex), 1 = 1/2 bias
     */
    fun setMode(backplanes: Int, bias: Int) {
        this.backplanes = backplanes
        this.bias = bias
        applyMode()
    }

    /**
     * Set the blink frequency.
     *
     * @param frequency     0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz
     * @param alternateBank true to enable alternate-RAM-bank blinking (static/1:2 only)
     */
    fun setBlink(frequency: Int, alternateBank: Boolean = false) {
        val ab = if (alternateBank) 0x04 else 0x00
        sendCommands(CMD_BLINK_SELECT or ab or (frequency and 0x03))
    }

    /**
     * Select the active RAM bank.
     *
     * @param inputBank  0 (rows 0-1) or 1 (rows 2-3)
     * @param outputBank 0 (rows 0-1) or 1 (rows 2-3). Only meaningful in static and 1:2 multiplex modes.
     */
    fun setBank(inputBank: Int, outputBank: Int) {
        sendCommands(CMD_BANK_SELECT or ((inputBank and 1) shl 1) or (outputBank and 1))
    }

    /**
     * Change the subaddress counter for cascaded displays.
     *
     * @param subaddress 0-7; must match the A0/A1/A2 pin state of the target device on the bus
     */
    fun deviceSelect(subaddress: Int) {
        sendCommands(CMD_DEVICE_SELECT or (subaddress and 0x07))
    }
}
