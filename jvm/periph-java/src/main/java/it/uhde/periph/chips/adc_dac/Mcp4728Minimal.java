package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MCP4728 — quad-channel 12-bit DAC with I²C interface (minimal driver).
 *
 * <p>Provides simple voltage output as a fraction of V_DD for any of the four
 * channels (A–D) plus a convenience method to update all four channels
 * simultaneously. V_REF is fixed at external (V_DD), gain is fixed at ×1,
 * and power-down is off. EEPROM is never written by this class.
 *
 * <p>Default I²C address: 0x60 (A2:A1:A0 = 000). Address range 0x60–0x67.
 */
public class Mcp4728Minimal {

    /** Channel A. */
    public static final int CH_A = 0;
    /** Channel B. */
    public static final int CH_B = 1;
    /** Channel C. */
    public static final int CH_C = 2;
    /** Channel D. */
    public static final int CH_D = 3;

    protected final Transport transport;

    /**
     * Construct the driver.
     *
     * @param transport I²C transport bound to the MCP4728 device address
     */
    public Mcp4728Minimal(Transport transport) {
        this.transport = transport;
    }

    /**
     * Set one channel's DAC output as a fraction of V_DD using Multi-Write.
     *
     * <p>V_REF=external, gain=×1, PD=00, UDAC=0. EEPROM is not written.
     *
     * @param channel  channel index 0 (A) – 3 (D)
     * @param fraction target output level, 0.0 = 0 V, 1.0 = V_DD
     * @throws IOException on I²C error
     */
    public void setVoltage(int channel, double fraction) throws IOException {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        setRaw(channel, (int) Math.round(fraction * 4095));
    }

    /**
     * Set one channel's raw 12-bit DAC code using Multi-Write.
     *
     * <p>Code is clamped to [0, 4095]. EEPROM is not written.
     *
     * @param channel channel index 0 (A) – 3 (D)
     * @param code    12-bit DAC value (0–4095)
     * @throws IOException on I²C error
     */
    public void setRaw(int channel, int code) throws IOException {
        int ch = Math.max(0, Math.min(3, channel));
        int c = Math.max(0, Math.min(4095, code));
        // Multi-Write: [0 1 0 0 0 DAC1 DAC0 UDAC] [V_REF PD1 PD0 Gx D11-D8] [D7-D0]
        // V_REF=0 (external), PD=00, Gx=0, UDAC=0
        int byte1 = 0x40 | ((ch & 0x03) << 1);
        int byte2 = (c >> 8) & 0x0F;
        int byte3 = c & 0xFF;
        transport.write(new byte[]{(byte) byte1, (byte) byte2, (byte) byte3});
    }

    /**
     * Update all four channels simultaneously using Fast Write.
     *
     * <p>Issues a single 8-byte Fast Write transaction; channels A→D are
     * updated at once. V_REF and gain bits are not carried by Fast Write;
     * they retain whatever values are currently in each channel's input
     * register. PD=00 for all four channels.
     *
     * @param fractions 4 values in [0.0, 1.0], index 0 = A
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if fractions is null or has length != 4
     */
    public void setAll(double[] fractions) throws IOException {
        if (fractions == null || fractions.length != 4) {
            throw new IllegalArgumentException("fractions must have exactly 4 elements");
        }
        byte[] buf = new byte[8];
        for (int i = 0; i < 4; i++) {
            double f = Math.max(0.0, Math.min(1.0, fractions[i]));
            int code = Math.max(0, Math.min(4095, (int) Math.round(f * 4095)));
            // Fast Write per channel: [0 0 PD1 PD0 D11-D8] [D7-D0]; PD=00
            buf[i * 2]     = (byte) ((code >> 8) & 0x0F);
            buf[i * 2 + 1] = (byte) (code & 0xFF);
        }
        transport.write(buf);
    }
}
