package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * PCF8576 — 40x4 universal LCD segment driver (minimal driver).
 *
 * <p>Drives a single 7-segment LCD display (static or 1:4 multiplex) out of
 * the box. The chip is write-only — the host never reads back. I2C address is
 * 0x38 (SA0 = VSS) or 0x39 (SA0 = VDD).
 *
 * <p>Default configuration: 1:4 multiplex drive mode, 1/3 bias, display
 * enabled, and a 7-segment digit lookup table for the default multiplex mode.
 */
@CompileStatic
class Pcf8576Minimal {

    static final int ADDR_SA0_LOW  = 0x38
    static final int ADDR_SA0_HIGH = 0x39

    static final int CMD_MODE_SET      = 0x40
    static final int CMD_LOAD_PTR      = 0x00
    static final int CMD_DEVICE_SELECT = 0x60
    static final int CMD_BANK_SELECT   = 0x78
    static final int CMD_BLINK_SELECT  = 0x70

    static final int MODE_1_4    = 0x00
    static final int MODE_STATIC = 0x01
    static final int MODE_1_2    = 0x02
    static final int MODE_1_3    = 0x03

    static final int BIAS_1_3 = 0x00
    static final int BIAS_1_2 = 0x04

    static final int DISPLAY_OFF = 0x00
    static final int DISPLAY_ON  = 0x08

    static final int[] SEVEN_SEG = [
        0xED, 0x60, 0xA7, 0xE3, 0x6A,
        0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
    ]

    protected final Transport transport
    protected int backplanes = MODE_1_4

    Pcf8576Minimal(Transport transport) {
        this.transport = transport
        doClear()
    }

    protected int cmdMode(boolean enable, int bias, int mode) {
        return CMD_MODE_SET | (enable ? DISPLAY_ON : DISPLAY_OFF) | bias | mode
    }

    protected void sendCommands(int... cmds) {
        byte[] buf = new byte[cmds.length]
        for (int i = 0; i < cmds.length; i++) {
            if (i + 1 < cmds.length) {
                buf[i] = (byte) (0x80 | (cmds[i] & 0x7F))
            } else {
                buf[i] = (byte) (cmds[i] & 0x7F)
            }
        }
        transport.write(buf)
    }

    protected void sendCommandsWithData(int cmd, byte[] data) {
        byte[] buf = new byte[1 + data.length]
        buf[0] = (byte) (cmd & 0x7F)
        System.arraycopy(data, 0, buf, 1, data.length)
        transport.write(buf)
    }

    private void doClear() {
        sendCommands(cmdMode(true, BIAS_1_3, MODE_1_4))
        byte[] zeros = new byte[20]
        sendCommandsWithData(CMD_LOAD_PTR, zeros)
    }

    /**
     * Zero all 40 columns of display RAM; all segments off.
     */
    void clear() {
        doClear()
    }

    /**
     * Set the data pointer to {@code address} and write raw data bytes.
     *
     * @param address RAM column address, 0-39
     * @param data    bytes to write to display RAM
     */
    void writeRaw(int address, byte[] data) {
        if (data == null || data.length == 0) return
        sendCommandsWithData(CMD_LOAD_PTR | (address & 0x3F), data)
    }

    /**
     * Write one 7-segment byte at column {@code position * 2}.
     */
    void setDigit7seg(int position, int segments) {
        writeRaw(position * 2, new byte[]{(byte) (segments & 0xFF)} as byte[])
    }
}
