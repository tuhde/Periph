package it.uhde.periph.chips.adc_dac

import it.uhde.periph.transport.Transport

/**
 * PCF8591 — 8-bit quad ADC + DAC with I²C interface (minimal driver).
 *
 * Reads the four single-ended analog inputs in 4 single-ended mode
 * (AIP=00). No configuration beyond the transport is required. Each read
 * transaction returns 5 bytes: the first is the previous conversion result
 * and must be discarded; the next four are fresh channel samples.
 *
 * Default I²C address: 0x48 (A0=A1=A2=GND), through 0x4F (all VDD).
 */
open class Pcf8591Minimal(protected val transport: Transport) {

    protected val control: Int = 0x00  // AIP=00, AOE=0, AI=0, CHN=0

    /**
     * Read a single channel as an unsigned 8-bit value.
     *
     * Uses single-shot conversion: writes the control byte selecting the
     * channel, then reads 2 bytes (discarding the stale first byte).
     *
     * @param channel channel number 0–3. Clamped to the valid range.
     * @return raw 8-bit value (0–255)
     */
    fun readChannel(channel: Int): Int {
        val ch = if (channel in 0 until NUM_CHANNELS) (channel and 0x03) else 0
        transport.write(byteArrayOf((control or (ch and 0x03)).toByte()))
        val buf = transport.read(2)
        return buf[1].toInt() and 0xFF
    }

    /**
     * Read all four channels as unsigned 8-bit values.
     *
     * Uses auto-increment (AI=1) to read all four channels in one
     * transaction. Reads 5 bytes and discards the stale first byte.
     *
     * @return four raw 8-bit values [ch0, ch1, ch2, ch3]
     */
    fun readAll(): IntArray {
        transport.write(byteArrayOf((control or 0x04).toByte()))
        val buf = transport.read(NUM_CHANNELS + 1)
        return intArrayOf(
            buf[1].toInt() and 0xFF,
            buf[2].toInt() and 0xFF,
            buf[3].toInt() and 0xFF,
            buf[4].toInt() and 0xFF,
        )
    }

    companion object {
        const val NUM_CHANNELS = 4
    }
}
