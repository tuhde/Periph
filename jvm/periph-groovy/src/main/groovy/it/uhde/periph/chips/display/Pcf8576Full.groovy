package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * PCF8576 — full driver. Extends {@link Pcf8576Minimal} with drive mode, bias,
 * blink configuration, RAM bank selection, and subaddress counter control
 * for cascaded displays.
 *
 * <h2>Drive mode constants</h2>
 * {@link #BACKPLANES_1}, {@link #BACKPLANES_2}, {@link #BACKPLANES_3},
 * {@link #BACKPLANES_4}
 *
 * <h2>Bias constants</h2>
 * {@link #BIAS_1_3_FULL}, {@link #BIAS_1_2_FULL}
 *
 * <h2>Blink constants</h2>
 * {@link #BLINK_OFF}, {@link #BLINK_2_HZ}, {@link #BLINK_1_HZ},
 * {@link #BLINK_0_5_HZ}
 *
 * <h2>Bank constants</h2>
 * {@link #BANK_0}, {@link #BANK_1}
 */
@CompileStatic
class Pcf8576Full extends Pcf8576Minimal {

    static final int BLINK_OFF     = 0
    static final int BLINK_2_HZ    = 1
    static final int BLINK_1_HZ    = 2
    static final int BLINK_0_5_HZ  = 3

    static final int BIAS_1_3_FULL = 0
    static final int BIAS_1_2_FULL = 1

    static final int BACKPLANES_1 = 1
    static final int BACKPLANES_2 = 2
    static final int BACKPLANES_3 = 3
    static final int BACKPLANES_4 = 4

    static final int BANK_0 = 0
    static final int BANK_1 = 1

    private boolean enabled = true
    private int bias = BIAS_1_3_FULL

    Pcf8576Full(Transport transport) {
        super(transport)
    }

    private int modeCode(int backplanes) {
        switch (backplanes) {
            case BACKPLANES_1: return MODE_STATIC
            case BACKPLANES_2: return MODE_1_2
            case BACKPLANES_3: return MODE_1_3
            default:           return MODE_1_4
        }
    }

    private void applyMode() {
        int biasBits = (bias == BIAS_1_2_FULL) ? BIAS_1_2 : BIAS_1_3
        sendCommands(cmdMode(enabled, biasBits, modeCode(backplanes)))
    }

    /** Turn the display on (E = 1). RAM contents are preserved. */
    void enable() {
        enabled = true
        applyMode()
    }

    /** Blank the display output (E = 0). RAM contents are preserved. */
    void disable() {
        enabled = false
        applyMode()
    }

    /**
     * Reconfigure drive mode and bias at runtime.
     */
    void setMode(int backplanes, int bias) {
        this.backplanes = backplanes
        this.bias = bias
        applyMode()
    }

    /** Set the blink frequency. */
    void setBlink(int frequency, boolean alternateBank) {
        int ab = alternateBank ? 0x04 : 0x00
        sendCommands(CMD_BLINK_SELECT | ab | (frequency & 0x03))
    }

    /** Select the active RAM bank. */
    void setBank(int inputBank, int outputBank) {
        sendCommands(CMD_BANK_SELECT | ((inputBank & 1) << 1) | (outputBank & 1))
    }

    /** Change the subaddress counter for cascaded displays. */
    void deviceSelect(int subaddress) {
        sendCommands(CMD_DEVICE_SELECT | (subaddress & 0x07))
    }
}
