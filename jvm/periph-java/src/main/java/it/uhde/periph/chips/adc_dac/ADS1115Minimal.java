package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * ADS1115 16-bit ΔΣ ADC (four-channel multiplexer, PGA and comparator; Texas Instruments) — minimal interface.
 *
 * <p>Four I²C addresses ({@code 0x48}–{@code 0x4B}) via the {@code ADDR} strap, no ID register.
 * The constructor makes no bus traffic: every conversion writes the full Config word (single-shot,
 * ±2.048&nbsp;V, 128&nbsp;SPS defaults) and leaves the chip in power-down. The implementation is
 * shared with its siblings in {@link ADS111xBase}.
 */
public class ADS1115Minimal extends ADS111xBase {

    /**
     * Construct the driver; makes no bus traffic.
     *
     * @param connection I²C register connection pointing at the device (0x48–0x4B)
     */
    public ADS1115Minimal(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Run a single-shot conversion of AIN0 against GND.
     *
     * @return signed 16-bit code (−32768…32767)
     * @throws IOException on bus error
     */
    public int readRaw() throws IOException {
        return readRaw(0);
    }

    /**
     * Run a single-shot conversion of AIN&lt;channel&gt; against GND.
     *
     * @param channel input 0–3 (clamped)
     * @return signed 16-bit code (−32768…32767)
     * @throws IOException on bus error
     */
    public int readRaw(int channel) throws IOException {
        return convert(4 + Math.max(0, Math.min(3, channel)));
    }

    /**
     * Run a single-shot conversion of AIN0 and return volts (raw × 62.5&nbsp;µV at the default range).
     *
     * @return voltage in V
     * @throws IOException on bus error
     */
    public double readVoltage() throws IOException {
        return readVoltage(0);
    }

    /**
     * Run a single-shot conversion and return volts (raw × 62.5&nbsp;µV at the default range).
     *
     * @param channel input 0–3 (clamped)
     * @return voltage in V
     * @throws IOException on bus error
     */
    public double readVoltage(int channel) throws IOException {
        return readRaw(channel) * 0.0000625;
    }
}
