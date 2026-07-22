package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * PCF8591 — 8-bit quad ADC + DAC with I²C interface (minimal driver).
 *
 * <p>Reads the four single-ended analog inputs in 4 single-ended mode
 * (AIP=00). No configuration beyond the transport is required. Each read
 * transaction returns 5 bytes: the first is the previous conversion result
 * and must be discarded; the next four are fresh channel samples.
 *
 * <p>Default I²C address: 0x48 (A0=A1=A2=GND), through 0x4F (all VDD).
 */
public class Pcf8591Minimal {

    protected static final int NUM_CHANNELS = 4;
    protected static final byte CONTROL_DEFAULT = 0x00;  // AIP=00, AOE=0, AI=0, CHN=0

    protected final Transport transport;

    /**
     * Construct the driver.
     *
     * @param transport I²C transport bound to the PCF8591 device address
     */
    public Pcf8591Minimal(Transport transport) {
        this.transport = transport;
    }

    /**
     * Read a single channel as an unsigned 8-bit value.
     *
     * <p>Uses single-shot conversion: writes the control byte selecting the
     * channel, then reads 2 bytes (discarding the stale first byte).
     *
     * @param channel channel number 0–3. Clamped to the valid range.
     * @return raw 8-bit value (0–255)
     * @throws IOException on I²C error
     */
    public int readChannel(int channel) throws IOException {
        int ch = (channel >= 0 && channel < NUM_CHANNELS) ? (channel & 0x03) : 0;
        byte ctrl = (byte) (CONTROL_DEFAULT | (ch & 0x03));
        transport.write(new byte[]{ctrl});
        byte[] buf = transport.read(2);
        return buf[1] & 0xFF;
    }

    /**
     * Read all four channels as unsigned 8-bit values.
     *
     * <p>Uses auto-increment (AI=1) to read all four channels in one
     * transaction. Reads 5 bytes and discards the stale first byte.
     *
     * @return four raw 8-bit values [ch0, ch1, ch2, ch3]
     * @throws IOException on I²C error
     */
    public int[] readAll() throws IOException {
        byte ctrl = (byte) (CONTROL_DEFAULT | 0x04);  // AI=1
        transport.write(new byte[]{ctrl});
        byte[] buf = transport.read(NUM_CHANNELS + 1);
        return new int[]{buf[1] & 0xFF, buf[2] & 0xFF, buf[3] & 0xFF, buf[4] & 0xFF};
    }
}
