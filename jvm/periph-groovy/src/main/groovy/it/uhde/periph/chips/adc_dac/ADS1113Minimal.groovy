package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic

import it.uhde.periph.connection.RegisterConnection

import java.io.IOException

/**
 * ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V; Texas Instruments) — minimal interface.
 *
 * <p>Four I²C addresses ({@code 0x48}–{@code 0x4B}) via the {@code ADDR} strap, no ID register.
 * The constructor makes no bus traffic: every conversion writes the full Config word (single-shot,
 * ±2.048&nbsp;V, 128&nbsp;SPS defaults) and leaves the chip in power-down. The implementation is
 * shared with its siblings in {@link ADS111xBase}.
 */
@CompileStatic
class ADS1113Minimal extends ADS111xBase {

    /**
     * Construct the driver; makes no bus traffic.
     *
     * @param connection I²C register connection pointing at the device (0x48–0x4B)
     */
    public ADS1113Minimal(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Run a single-shot conversion of AIN0 − AIN1.
     *
     * @return signed 16-bit code (−32768…32767)
     * @throws IOException on bus error
     */
    public int readRaw() throws IOException {
        return convert(0)
    }

    /**
     * Run a single-shot conversion and return volts (raw × 62.5&nbsp;µV at the default range).
     *
     * @return voltage AIN0 − AIN1 in V
     * @throws IOException on bus error
     */
    public double readVoltage() throws IOException {
        return readRaw() * 0.0000625
    }
}
