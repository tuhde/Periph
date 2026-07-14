package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport

/**
 * MCP4728 — full driver. Extends [Mcp4728Minimal] with per-channel V_REF
 * and gain configuration, all-channel V_REF / gain / power-down commands,
 * write-with-EEPROM persistence (Single and Sequential Write), General
 * Call commands (reset / wake-up / software update), and full 24-byte
 * read-back of all channel DAC input registers and EEPROM contents.
 *
 * General Call commands ([reset], [wakeUp], [softwareUpdate]) require a
 * second transport bound to address 0x00. Pass `null` to disable them.
 *
 * ## EEPROM write timing
 * The EEPROM write takes up to 50 ms. New write commands are silently
 * ignored while RDY/BSY is low. Poll [isEepromReady] or wait 50 ms before
 * issuing a second EEPROM write.
 */
class Mcp4728Full(
    transport: Transport,
    private val generalCall: Transport?
) : Mcp4728Minimal(transport) {

    /**
     * Per-channel state read from the chip.
     *
     * @property code current DAC input register value (0–4095)
     * @property vref 0 = external (V_DD), 1 = internal (2.048 V)
     * @property gain 1 = ×1, 2 = ×2
     * @property powerDown power-down mode 0–3
     * @property eepromCode EEPROM-stored DAC value (0–4095)
     * @property eepromVref EEPROM-stored V_REF (0/1)
     * @property eepromGain EEPROM-stored gain (1/2)
     * @property eepromPowerDown EEPROM-stored power-down mode (0–3)
     */
    data class ChannelState(
        val code: Int,
        val vref: Int,
        val gain: Int,
        val powerDown: Int,
        val eepromCode: Int,
        val eepromVref: Int,
        val eepromGain: Int,
        val eepromPowerDown: Int
    )

    /**
     * All four channels plus the EEPROM-ready flag.
     */
    data class ReadResult(
        val channel: Array<ChannelState>,
        val eepromReady: Boolean
    )

    companion object {
        /** Normal operation (power-down mode 0). */
        const val PD_NORMAL   = 0
        /** Power-down with 1 kΩ to GND. */
        const val PD_1K_GND   = 1
        /** Power-down with 100 kΩ to GND. */
        const val PD_100K_GND = 2
        /** Power-down with 500 kΩ to GND. */
        const val PD_500K_GND = 3

        /** External V_DD reference. */
        const val VREF_EXTERNAL = 0
        /** Internal 2.048 V reference. */
        const val VREF_INTERNAL = 1

        /** Gain ×1. */
        const val GAIN_X1 = 1
        /** Gain ×2. */
        const val GAIN_X2 = 2

        private const val CMD_MULTI_WRITE_BASE = 0x40
        private const val CMD_SINGLE_WRITE     = 0x58
        private const val CMD_SEQUENTIAL_BASE  = 0x50
        private const val CMD_WRITE_VREF       = 0x80
        private const val CMD_WRITE_GAIN       = 0xC0
        private const val CMD_WRITE_POWERDOWN  = 0xA0

        private const val ADDR_GENERAL_CALL    = 0x00
        private const val GC_RESET             = 0x06
        private const val GC_SOFTWARE_UPD      = 0x08
        private const val GC_WAKE              = 0x09
    }

    /**
     * Set one channel's output and persist to EEPROM (Single Write).
     *
     * @param channel channel index 0 (A) – 3 (D)
     * @param fraction target output level, 0.0–1.0 of full scale
     * @param vref 0 = external (V_DD), 1 = internal (2.048 V)
     * @param gain 1 = ×1, 2 = ×2
     */
    fun setVoltageEeprom(channel: Int, fraction: Double, vref: Int, gain: Int) {
        setRawEeprom(channel, (fraction.coerceIn(0.0, 1.0) * 4095).toInt(), vref, gain)
    }

    /**
     * Set one channel's raw 12-bit code and persist to EEPROM.
     */
    fun setRawEeprom(channel: Int, code: Int, vref: Int, gain: Int) {
        val ch = channel.coerceIn(0, 3)
        val c = code.coerceIn(0, 4095)
        val g = if (gain == GAIN_X2) 1 else 0
        // Single Write: [0 1 0 1 1 DAC1 DAC0 UDAC] [V_REF PD1 PD0 Gx D11-D8] [D7-D0]
        transport.write(byteArrayOf(
            (CMD_SINGLE_WRITE or ((ch and 0x03) shl 1)).toByte(),
            (((vref and 0x01) shl 7) or ((0 and 0x03) shl 5) or (g shl 4) or ((c shr 8) and 0x0F)).toByte(),
            (c and 0xFF).toByte()
        ))
    }

    /**
     * Update all four channels and EEPROM (Sequential Write from A to D).
     */
    fun setAllEeprom(fractions: DoubleArray, vrefs: IntArray, gains: IntArray) {
        require(fractions.size == 4 && vrefs.size == 4 && gains.size == 4) {
            "fractions, vrefs, gains must each have 4 elements"
        }
        val buf = ByteArray(9)
        // Sequential Write starting at channel 0: [0 1 0 1 0 0 0 UDAC] = 0x50
        buf[0] = (CMD_SEQUENTIAL_BASE or 0x00).toByte()
        for (i in 0..3) {
            val f = fractions[i].coerceIn(0.0, 1.0)
            val code = (f * 4095).toInt().coerceIn(0, 4095)
            val v = if (vrefs[i] != 0) 1 else 0
            val g = if (gains[i] == GAIN_X2) 1 else 0
            // Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
            buf[1 + i * 2]     = (((v and 0x01) shl 7) or (g shl 4) or ((code shr 8) and 0x0F)).toByte()
            buf[1 + i * 2 + 1] = (code and 0xFF).toByte()
        }
        transport.write(buf)
    }

    /**
     * Set V_REF for all four channels (volatile register only).
     */
    fun setVref(vrefA: Int, vrefB: Int, vrefC: Int, vrefD: Int) {
        val byte1 = CMD_WRITE_VREF or
            ((if (vrefA != 0) 1 else 0) shl 3) or
            ((if (vrefB != 0) 1 else 0) shl 2) or
            ((if (vrefC != 0) 1 else 0) shl 1) or
             (if (vrefD != 0) 1 else 0)
        transport.write(byteArrayOf(byte1.toByte()))
    }

    /**
     * Set gain for all four channels (volatile register only).
     */
    fun setGain(gainA: Int, gainB: Int, gainC: Int, gainD: Int) {
        val byte1 = CMD_WRITE_GAIN or
            ((if (gainA == GAIN_X2) 1 else 0) shl 3) or
            ((if (gainB == GAIN_X2) 1 else 0) shl 2) or
            ((if (gainC == GAIN_X2) 1 else 0) shl 1) or
             (if (gainD == GAIN_X2) 1 else 0)
        transport.write(byteArrayOf(byte1.toByte()))
    }

    /**
     * Set power-down mode for all four channels (volatile register only).
     */
    fun setPowerDown(pdA: Int, pdB: Int, pdC: Int, pdD: Int) {
        val a = pdA.coerceIn(0, 3)
        val b = pdB.coerceIn(0, 3)
        val c = pdC.coerceIn(0, 3)
        val d = pdD.coerceIn(0, 3)
        val byte1 = CMD_WRITE_POWERDOWN or
            (((a shr 1) and 0x01) shl 4) or ((a and 0x01) shl 3) or
            (((b shr 1) and 0x01) shl 2) or ((b and 0x01) shl 1)
        val byte2 = (((c shr 1) and 0x01) shl 6) or ((c and 0x01) shl 5) or
                    (((d shr 1) and 0x01) shl 4) or ((d and 0x01) shl 3)
        transport.write(byteArrayOf(byte1.toByte(), byte2.toByte()))
    }

    /**
     * Read all four channels' DAC input registers and EEPROM contents.
     */
    fun read(): ReadResult {
        val b = transport.read(24)
        val eepromReady = (b[0].toInt() and 0x80) != 0
        val ch = Array(4) { idx ->
            val base = idx * 3
            val code = ((b[base + 1].toInt() and 0x0F) shl 8) or (b[base + 2].toInt() and 0xFF)
            val vref = (b[base + 1].toInt() shr 7) and 0x01
            val pd   = (b[base + 1].toInt() shr 5) and 0x03
            val gain = if (((b[base + 1].toInt() shr 4) and 0x01) != 0) GAIN_X2 else GAIN_X1
            ChannelState(code, vref, gain, pd, 0, 0, GAIN_X1, 0)
        }
        for (i in 0..3) {
            val base = 12 + i * 3
            val code = ((b[base + 1].toInt() and 0x0F) shl 8) or (b[base + 2].toInt() and 0xFF)
            val vref = (b[base + 1].toInt() shr 7) and 0x01
            val pd   = (b[base + 1].toInt() shr 5) and 0x03
            val gain = if (((b[base + 1].toInt() shr 4) and 0x01) != 0) GAIN_X2 else GAIN_X1
            ch[i] = ch[i].copy(eepromCode = code, eepromVref = vref, eepromGain = gain, eepromPowerDown = pd)
        }
        return ReadResult(ch, eepromReady)
    }

    /**
     * Read the RDY/BSY bit.
     */
    fun isEepromReady(): Boolean =
        (transport.read(1)[0].toInt() and 0x80) != 0

    /**
     * Send General Call Software Update (0x00, 0x08).
     */
    fun softwareUpdate() {
        requireGeneralCall().write(byteArrayOf(GC_SOFTWARE_UPD.toByte()))
    }

    /**
     * Send General Call Wake-Up (0x00, 0x09).
     */
    fun wakeUp() {
        requireGeneralCall().write(byteArrayOf(GC_WAKE.toByte()))
    }

    /**
     * Send General Call Reset (0x00, 0x06).
     */
    fun reset() {
        requireGeneralCall().write(byteArrayOf(GC_RESET.toByte()))
    }

    private fun requireGeneralCall(): Transport =
        generalCall ?: throw IllegalStateException("General Call transport not configured")
}
