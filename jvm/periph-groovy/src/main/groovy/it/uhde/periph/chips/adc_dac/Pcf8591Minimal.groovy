package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * PCF8591 — 8-bit quad ADC + DAC with I²C interface (minimal driver).
 *
 * <p>Reads the four single-ended analog inputs in 4 single-ended mode
 * (AIP=00). No configuration beyond the connection is required. Each read
 * transaction returns 5 bytes: the first is the previous conversion result
 * and must be discarded; the next four are fresh channel samples.
 *
 * <p>Default I²C address: 0x48 (A0=A1=A2=GND), through 0x4F (all VDD).
 */
@CompileStatic
class Pcf8591Minimal {

    static final int NUM_CHANNELS = 4
    protected static final byte CONTROL_DEFAULT = (byte) 0x00  // AIP=00, AOE=0, AI=0, CHN=0

    protected final Connection connection

    /**
     * Construct the driver.
     *
     * @param connection I²C connection bound to the PCF8591 device address
     */
    Pcf8591Minimal(Connection connection) {
        this.connection = connection
    }

    /**
     * Read a single channel as an unsigned 8-bit value.
     *
     * <p>Uses single-shot conversion: writes the control byte selecting the
     * channel, then reads 2 bytes (discarding the stale first byte).
     *
     * @param channel channel number 0–3. Clamped to the valid range.
     * @return raw 8-bit value (0–255)
     */
    int readChannel(int channel) {
        int ch = (channel >= 0 && channel < NUM_CHANNELS) ? (channel & 0x03) : 0
        byte ctrl = (byte) (CONTROL_DEFAULT | (ch & 0x03))
        connection.write([ctrl] as byte[])
        byte[] buf = connection.read(2)
        return buf[1] & 0xFF
    }

    /**
     * Read all four channels as unsigned 8-bit values.
     *
     * <p>Uses auto-increment (AI=1) to read all four channels in one
     * transaction. Reads 5 bytes and discards the stale first byte.
     *
     * @return four raw 8-bit values [ch0, ch1, ch2, ch3]
     */
    int[] readAll() {
        byte ctrl = (byte) (CONTROL_DEFAULT | 0x04)  // AI=1
        connection.write([ctrl] as byte[])
        byte[] buf = connection.read(NUM_CHANNELS + 1)
        return [buf[1] & 0xFF, buf[2] & 0xFF, buf[3] & 0xFF, buf[4] & 0xFF] as int[]
    }
}
