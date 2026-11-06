package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1115 16-bit ΔΣ ADC (four-channel multiplexer, PGA and comparator; Texas Instruments) — minimal interface.
 *
 * Four I²C addresses (`0x48`–`0x4B`) via the `ADDR` strap, no ID register. Construction makes no
 * bus traffic: every conversion writes the full Config word (single-shot, ±2.048 V, 128 SPS
 * defaults) and leaves the chip in power-down. The implementation is shared with its siblings in
 * [ADS111xBase].
 *
 * @param connection I²C register connection pointing at the device (0x48–0x4B)
 */
open class ADS1115Minimal(connection: RegisterConnection) : ADS111xBase(connection) {

    companion object {
        /** Default 7-bit I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48
    }

    /**
     * Run a single-shot conversion of AIN<channel> against GND.
     *
     * @param channel input 0–3 (clamped)
     * @return signed 16-bit code (−32768…32767)
     */
    fun readRaw(channel: Int = 0): Int = convert(4 + channel.coerceIn(0, 3))

    /**
     * Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
     *
     * @param channel input 0–3 (clamped)
     * @return voltage in V
     */
    open fun readVoltage(channel: Int = 0): Double = readRaw(channel) * 0.0000625
}
