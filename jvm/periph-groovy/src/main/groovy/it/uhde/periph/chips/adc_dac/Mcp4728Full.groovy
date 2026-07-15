package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import groovy.transform.Immutable
import it.uhde.periph.transport.Transport

/**
 * Per-channel state read from the MCP4728.
 *
 * @param code current DAC input register value (0–4095)
 * @param vref 0 = external (V_DD), 1 = internal (2.048 V)
 * @param gain 1 = ×1, 2 = ×2
 * @param powerDown power-down mode 0–3
 * @param eepromCode EEPROM-stored DAC value (0–4095)
 * @param eepromVref EEPROM-stored V_REF (0/1)
 * @param eepromGain EEPROM-stored gain (1/2)
 * @param eepromPowerDown EEPROM-stored power-down mode (0–3)
 */
@Immutable
class ChannelState {
    int code
    int vref
    int gain
    int powerDown
    int eepromCode
    int eepromVref
    int eepromGain
    int eepromPowerDown
}

/**
 * MCP4728 — full driver. Extends {@link Mcp4728Minimal} with per-channel
 * V_REF and gain configuration, all-channel V_REF / gain / power-down
 * commands, write-with-EEPROM persistence (Single and Sequential Write),
 * General Call commands (reset / wake-up / software update), and full
 * 24-byte read-back of all channel DAC input registers and EEPROM contents.
 *
 * <p>General Call commands require a second transport bound to address
 * 0x00. Pass {@code null} to disable them.
 *
 * <h2>EEPROM write timing</h2>
 * The EEPROM write takes up to 50 ms. New write commands are silently ignored
 * while RDY/BSY is low. Poll {@link #isEepromReady()} or wait 50 ms before
 * issuing a second EEPROM write.
 */
@CompileStatic
class Mcp4728Full extends Mcp4728Minimal {

    /** Normal operation (power-down mode 0). */
    public static final int PD_NORMAL   = 0
    /** Power-down with 1 kΩ to GND. */
    public static final int PD_1K_GND   = 1
    /** Power-down with 100 kΩ to GND. */
    public static final int PD_100K_GND = 2
    /** Power-down with 500 kΩ to GND. */
    public static final int PD_500K_GND = 3

    /** External V_DD reference. */
    public static final int VREF_EXTERNAL = 0
    /** Internal 2.048 V reference. */
    public static final int VREF_INTERNAL = 1

    /** Gain ×1. */
    public static final int GAIN_X1 = 1
    /** Gain ×2. */
    public static final int GAIN_X2 = 2

    private static final int CMD_MULTI_WRITE_BASE = 0x40
    private static final int CMD_SINGLE_WRITE     = 0x58
    private static final int CMD_SEQUENTIAL_BASE  = 0x50
    private static final int CMD_WRITE_VREF       = 0x80
    private static final int CMD_WRITE_GAIN       = 0xC0
    private static final int CMD_WRITE_POWERDOWN  = 0xA0

    private static final int ADDR_GENERAL_CALL    = 0x00
    private static final int GC_RESET             = 0x06
    private static final int GC_SOFTWARE_UPD      = 0x08
    private static final int GC_WAKE              = 0x09

    private final Transport generalCall

    /**
     * Construct the full driver.
     *
     * @param transport I²C transport bound to the MCP4728 device address
     * @param generalCall I²C transport bound to address 0x00, or {@code null}
     *                    to disable reset / wakeUp / softwareUpdate
     */
    Mcp4728Full(Transport transport, Transport generalCall) {
        super(transport)
        this.generalCall = generalCall
    }

    /**
     * Set one channel's output and persist to EEPROM (Single Write).
     */
    void setVoltageEeprom(int channel, double fraction, int vref, int gain) {
        fraction = Math.max(0.0d, Math.min(1.0d, fraction))
        setRawEeprom(channel, (int) Math.round(fraction * 4095), vref, gain)
    }

    /**
     * Set one channel's raw 12-bit code and persist to EEPROM.
     */
    void setRawEeprom(int channel, int code, int vref, int gain) {
        int ch = Math.max(0, Math.min(3, channel))
        int c = Math.max(0, Math.min(4095, code))
        int g = (gain == GAIN_X2) ? 1 : 0
        // Single Write: [0 1 0 1 1 DAC1 DAC0 UDAC] [V_REF PD1 PD0 Gx D11-D8] [D7-D0]
        transport.write([(byte) (CMD_SINGLE_WRITE | ((ch & 0x03) << 1)),
                         (byte) (((vref & 0x01) << 7) | ((0 & 0x03) << 5) | (g << 4) | ((c >> 8) & 0x0F)),
                         (byte) (c & 0xFF)] as byte[])
    }

    /**
     * Update all four channels and EEPROM (Sequential Write from A to D).
     */
    void setAllEeprom(double[] fractions, int[] vrefs, int[] gains) {
        if (fractions == null || fractions.length != 4
                || vrefs == null || vrefs.length != 4
                || gains == null || gains.length != 4) {
            throw new IllegalArgumentException("fractions, vrefs, gains must each have 4 elements")
        }
        byte[] buf = new byte[9]
        // Sequential Write starting at channel 0: [0 1 0 1 0 0 0 UDAC] = 0x50
        buf[0] = (byte) (CMD_SEQUENTIAL_BASE | 0x00)
        for (int i = 0; i < 4; i++) {
            double f = Math.max(0.0d, Math.min(1.0d, fractions[i]))
            int code = Math.max(0, Math.min(4095, (int) Math.round(f * 4095)))
            int v = (vrefs[i] != 0) ? 1 : 0
            int g = (gains[i] == GAIN_X2) ? 1 : 0
            // Per-channel byte layout (Multi-Write format): [V_REF PD1 PD0 Gx D11-D8]
            buf[1 + i * 2]     = (byte) (((v & 0x01) << 7) | ((g & 0x01) << 4) | ((code >> 8) & 0x0F))
            buf[1 + i * 2 + 1] = (byte) (code & 0xFF)
        }
        transport.write(buf)
    }

    /**
     * Set V_REF for all four channels (volatile register only).
     */
    void setVref(int vrefA, int vrefB, int vrefC, int vrefD) {
        int byte1 = CMD_WRITE_VREF |
            ((vrefA != 0 ? 1 : 0) << 3) | ((vrefB != 0 ? 1 : 0) << 2) |
            ((vrefC != 0 ? 1 : 0) << 1) |  (vrefD != 0 ? 1 : 0)
        transport.write([(byte) byte1] as byte[])
    }

    /**
     * Set gain for all four channels (volatile register only).
     */
    void setGain(int gainA, int gainB, int gainC, int gainD) {
        int byte1 = CMD_WRITE_GAIN |
            ((gainA == GAIN_X2 ? 1 : 0) << 3) | ((gainB == GAIN_X2 ? 1 : 0) << 2) |
            ((gainC == GAIN_X2 ? 1 : 0) << 1) |  (gainD == GAIN_X2 ? 1 : 0)
        transport.write([(byte) byte1] as byte[])
    }

    /**
     * Set power-down mode for all four channels (volatile register only).
     */
    void setPowerDown(int pdA, int pdB, int pdC, int pdD) {
        int a = Math.max(0, Math.min(3, pdA))
        int b = Math.max(0, Math.min(3, pdB))
        int c = Math.max(0, Math.min(3, pdC))
        int d = Math.max(0, Math.min(3, pdD))
        int byte1 = CMD_WRITE_POWERDOWN |
            (((a >> 1) & 0x01) << 4) | ((a & 0x01) << 3) |
            (((b >> 1) & 0x01) << 2) | ((b & 0x01) << 1)
        int byte2 = (((c >> 1) & 0x01) << 6) | ((c & 0x01) << 5) |
                    (((d >> 1) & 0x01) << 4) | ((d & 0x01) << 3)
        transport.write([(byte) byte1, (byte) byte2] as byte[])
    }

    /**
     * Read all four channels' DAC input registers and EEPROM contents.
     */
    ChannelState[] read() {
        byte[] b = transport.read(24)
        boolean eepromReady = (b[0] & 0x80) != 0
        ChannelState[] ch = new ChannelState[4]
        for (int i = 0; i < 4; i++) {
            int base = i * 3
            int code = ((b[base + 1] & 0xFF) << 4) | ((b[base + 2] & 0xFF) >> 4) // adjust below
            // The per-channel 3-byte layout is: byte1=[RDY/BSY POR DAC1 DAC0 0 A2 A1 A0], byte2=[V_REF PD1 PD0 Gx D11-D8], byte3=[D7-D0]
            int code12 = ((b[base + 1] & 0x0F) << 8) | (b[base + 2] & 0xFF)
            int vref = (b[base + 1] >> 7) & 0x01
            int pd   = (b[base + 1] >> 5) & 0x03
            int gain = ((b[base + 1] >> 4) & 0x01) != 0 ? GAIN_X2 : GAIN_X1
            ch[i] = new ChannelState(code12, vref, gain, pd, 0, 0, GAIN_X1, 0)
        }
        for (int i = 0; i < 4; i++) {
            int base = 12 + i * 3
            int code = ((b[base + 1] & 0x0F) << 8) | (b[base + 2] & 0xFF)
            int vref = (b[base + 1] >> 7) & 0x01
            int pd   = (b[base + 1] >> 5) & 0x03
            int gain = ((b[base + 1] >> 4) & 0x01) != 0 ? GAIN_X2 : GAIN_X1
            ch[i] = new ChannelState(ch[i].code, ch[i].vref, ch[i].gain, ch[i].powerDown,
                                     code, vref, gain, pd)
        }
        return ch
    }

    /** {@return true when RDY/BSY = 1 (no EEPROM write in progress)} */
    boolean isEepromReady() {
        (transport.read(1)[0] & 0x80) != 0
    }

    /**
     * Send General Call Software Update (0x00, 0x08).
     */
    void softwareUpdate() {
        requireGeneralCall().write([(byte) GC_SOFTWARE_UPD] as byte[])
    }

    /**
     * Send General Call Wake-Up (0x00, 0x09).
     */
    void wakeUp() {
        requireGeneralCall().write([(byte) GC_WAKE] as byte[])
    }

    /**
     * Send General Call Reset (0x00, 0x06).
     */
    void reset() {
        requireGeneralCall().write([(byte) GC_RESET] as byte[])
    }

    private Transport requireGeneralCall() {
        if (generalCall == null)
            throw new IllegalStateException('General Call transport not configured')
        generalCall
    }
}
