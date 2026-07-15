package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport

/**
 * MCP4728 — quad-channel 12-bit DAC with I²C interface (minimal driver).
 *
 * Provides simple voltage output as a fraction of V_DD for any of the four
 * channels (A–D) plus a convenience method to update all four channels
 * simultaneously. V_REF is fixed at external (V_DD), gain is fixed at ×1,
 * and power-down is off. EEPROM is never written by this class.
 *
 * Default I²C address: 0x60 (A2:A1:A0 = 000). Address range 0x60–0x67.
 */
open class Mcp4728Minimal(protected val transport: Transport) {

    /** Channel A. */
    companion object {
        const val CH_A = 0
        /** Channel B. */
        const val CH_B = 1
        /** Channel C. */
        const val CH_C = 2
        /** Channel D. */
        const val CH_D = 3
    }

    /**
     * Set one channel's DAC output as a fraction of V_DD using Multi-Write.
     *
     * V_REF=external, gain=×1, PD=00, UDAC=0. EEPROM is not written.
     *
     * @param channel channel index 0 (A) – 3 (D)
     * @param fraction target output level, 0.0 = 0 V, 1.0 = V_DD
     */
    fun setVoltage(channel: Int, fraction: Double) {
        setRaw(channel, (fraction.coerceIn(0.0, 1.0) * 4095).toInt())
    }

    /**
     * Set one channel's raw 12-bit DAC code using Multi-Write.
     *
     * Code is clamped to [0, 4095]. EEPROM is not written.
     *
     * @param channel channel index 0 (A) – 3 (D)
     * @param code    12-bit DAC value (0–4095)
     */
    fun setRaw(channel: Int, code: Int) {
        val ch = channel.coerceIn(0, 3)
        val c = code.coerceIn(0, 4095)
        // Multi-Write: [0 1 0 0 0 DAC1 DAC0 UDAC] [V_REF PD1 PD0 Gx D11-D8] [D7-D0]
        transport.write(byteArrayOf(
            (0x40 or ((ch and 0x03) shl 1)).toByte(),
            (((c shr 8) and 0x0F)).toByte(),
            (c and 0xFF).toByte()
        ))
    }

    /**
     * Update all four channels simultaneously using Fast Write.
     *
     * V_REF and gain bits are not carried by Fast Write; they retain
     * whatever values are currently in each channel's input register.
     * PD=00 for all four channels.
     *
     * @param fractions 4 values in [0.0, 1.0], index 0 = A
     */
    fun setAll(fractions: DoubleArray) {
        require(fractions.size == 4) { "fractions must have exactly 4 elements" }
        val buf = ByteArray(8)
        for (i in 0..3) {
            val f = fractions[i].coerceIn(0.0, 1.0)
            val code = (f * 4095).toInt().coerceIn(0, 4095)
            // Fast Write per channel: [0 0 PD1 PD0 D11-D8] [D7-D0]; PD=00
            buf[i * 2]     = ((code shr 8) and 0x0F).toByte()
            buf[i * 2 + 1] = (code and 0xFF).toByte()
        }
        transport.write(buf)
    }
}
