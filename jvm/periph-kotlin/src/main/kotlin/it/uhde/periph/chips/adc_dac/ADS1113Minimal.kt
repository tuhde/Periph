package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V; Texas Instruments) — minimal interface.
 *
 * Four I²C addresses (`0x48`–`0x4B`) via the `ADDR` strap, no ID register. Construction makes no
 * bus traffic: every conversion writes the full Config word (single-shot, ±2.048 V, 128 SPS
 * defaults) and leaves the chip in power-down. The implementation is shared with its siblings in
 * [ADS111xBase].
 *
 * @param connection I²C register connection pointing at the device (0x48–0x4B)
 */
open class ADS1113Minimal(connection: RegisterConnection) : ADS111xBase(connection) {

    companion object {
        /** Default 7-bit I²C address (`ADDR` = GND). Valid range 0x48–0x4B. */
        const val DEFAULT_ADDRESS = 0x48
    }

    /**
     * Run a single-shot conversion of AIN0 − AIN1.
     *
     * @return signed 16-bit code (−32768…32767)
     */
    fun readRaw(): Int = convert(0)

    /**
     * Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
     *
     * @return voltage AIN0 − AIN1 in V
     */
    open fun readVoltage(): Double = readRaw() * 0.0000625
}
