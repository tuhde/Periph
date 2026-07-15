package it.uhde.periph.chips.display;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

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
 * {@link #BIAS_1_3}, {@link #BIAS_1_2}
 *
 * <h2>Blink constants</h2>
 * {@link #BLINK_OFF}, {@link #BLINK_2_HZ}, {@link #BLINK_1_HZ},
 * {@link #BLINK_0_5_HZ}
 *
 * <h2>Bank constants</h2>
 * {@link #BANK_0}, {@link #BANK_1}
 */
public class Pcf8576Full extends Pcf8576Minimal {

    public static final int BLINK_OFF     = 0;
    public static final int BLINK_2_HZ    = 1;
    public static final int BLINK_1_HZ    = 2;
    public static final int BLINK_0_5_HZ  = 3;

    public static final int BIAS_1_3_FULL = 0;
    public static final int BIAS_1_2_FULL = 1;

    public static final int BACKPLANES_1 = 1;
    public static final int BACKPLANES_2 = 2;
    public static final int BACKPLANES_3 = 3;
    public static final int BACKPLANES_4 = 4;

    public static final int BANK_0 = 0;
    public static final int BANK_1 = 1;

    private boolean enabled = true;
    private int bias = BIAS_1_3_FULL;

    /**
     * Construct the full driver and initialise the chip with defaults.
     *
     * @param transport I2C transport bound to the PCF8576 address
     * @throws IOException on I2C error
     */
    public Pcf8576Full(Transport transport) throws IOException {
        super(transport);
    }

    private int modeCode(int backplanes) {
        switch (backplanes) {
            case BACKPLANES_1: return MODE_STATIC;
            case BACKPLANES_2: return MODE_1_2;
            case BACKPLANES_3: return MODE_1_3;
            default:           return MODE_1_4;
        }
    }

    private void applyMode() throws IOException {
        int biasBits = (bias == BIAS_1_2_FULL) ? BIAS_1_2 : BIAS_1_3;
        sendCommands(cmdMode(enabled, biasBits, modeCode(backplanes)));
    }

    /**
     * Turn the display on (E = 1). RAM contents are preserved.
     *
     * @throws IOException on I2C error
     */
    public void enable() throws IOException {
        enabled = true;
        applyMode();
    }

    /**
     * Blank the display output (E = 0). RAM contents are preserved.
     *
     * @throws IOException on I2C error
     */
    public void disable() throws IOException {
        enabled = false;
        applyMode();
    }

    /**
     * Reconfigure drive mode and bias at runtime.
     *
     * @param backplanes number of backplanes — 1 (static), 2 (1:2), 3 (1:3),
     *                   4 (1:4 multiplex)
     * @param bias       0 = 1/3 bias (recommended for 1:3 and 1:4 multiplex),
     *                   1 = 1/2 bias
     * @throws IOException on I2C error
     */
    public void setMode(int backplanes, int bias) throws IOException {
        this.backplanes = backplanes;
        this.bias = bias;
        applyMode();
    }

    /**
     * Set the blink frequency.
     *
     * @param frequency     0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz
     * @param alternateBank true to enable alternate-RAM-bank blinking
     *                      (static and 1:2 multiplex only)
     * @throws IOException on I2C error
     */
    public void setBlink(int frequency, boolean alternateBank) throws IOException {
        int ab = alternateBank ? 0x04 : 0x00;
        sendCommands(CMD_BLINK_SELECT | ab | (frequency & 0x03));
    }

    /**
     * Select the active RAM bank.
     *
     * @param inputBank  0 (rows 0-1) or 1 (rows 2-3)
     * @param outputBank 0 (rows 0-1) or 1 (rows 2-3).
     *                   Only meaningful in static and 1:2 multiplex modes.
     * @throws IOException on I2C error
     */
    public void setBank(int inputBank, int outputBank) throws IOException {
        sendCommands(CMD_BANK_SELECT | ((inputBank & 1) << 1) | (outputBank & 1));
    }

    /**
     * Change the subaddress counter for cascaded displays.
     *
     * @param subaddress 0-7; must match the A0/A1/A2 pin state of the target
     *                   device on the bus
     * @throws IOException on I2C error
     */
    public void deviceSelect(int subaddress) throws IOException {
        sendCommands(CMD_DEVICE_SELECT | (subaddress & 0x07));
    }
}
