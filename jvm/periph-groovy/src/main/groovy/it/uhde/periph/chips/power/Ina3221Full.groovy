package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * INA3221 — full driver. Extends {@link Ina3221Minimal} with configuration,
 * per-channel enable/disable, alert limits, summation, power-valid thresholds,
 * shutdown/wake, and identification registers.
 *
 * <h2>Alert flags</h2>
 * Reading the Mask/Enable register (via {@link #alertFlags()}) also clears the
 * latched alert flags. Read it once after a monitoring interval to capture and
 * clear all pending alerts.
 *
 * <h2>Mode constants</h2>
 * <ul>
 *   <li>{@link #MODE_POWERDOWN} — power-down (0)</li>
 *   <li>{@link #MODE_SHUNT_TRIG} — shunt triggered (1)</li>
 *   <li>{@link #MODE_BUS_TRIG} — bus triggered (2)</li>
 *   <li>{@link #MODE_SHUNT_BUS_TRIG} — shunt + bus triggered (3)</li>
 *   <li>{@link #MODE_SHUNT_CONT} — shunt continuous (5)</li>
 *   <li>{@link #MODE_BUS_CONT} — bus continuous (6)</li>
 *   <li>{@link #MODE_SHUNT_BUS_CONT} — shunt + bus continuous (7, default)</li>
 * </ul>
 */
@CompileStatic
class Ina3221Full extends Ina3221Minimal {

    // Mode constants
    static final int MODE_POWERDOWN      = 0
    static final int MODE_SHUNT_TRIG     = 1
    static final int MODE_BUS_TRIG       = 2
    static final int MODE_SHUNT_BUS_TRIG = 3
    static final int MODE_SHUNT_CONT     = 5
    static final int MODE_BUS_CONT       = 6
    static final int MODE_SHUNT_BUS_CONT = 7

    // Mask/Enable bit positions
    static final int CF1  = 512
    static final int CF2  = 256
    static final int CF3  = 128
    static final int SF   =  64
    static final int WF1  =  32
    static final int WF2  =  16
    static final int WF3  =   8
    static final int PVF  =   4
    static final int TCF  =   2
    static final int CVRF =   1

    // Register addresses (redeclared for access within @CompileStatic subclass)
    private static final int REG_CONFIG       = 0x00
    private static final int REG_CH1_CRIT     = 0x07
    private static final int REG_CH1_WARN     = 0x08
    private static final int REG_SV_SUM       = 0x0D
    private static final int REG_SV_SUM_LIMIT = 0x0E
    private static final int REG_MASK_ENABLE  = 0x0F
    private static final int REG_PV_UPPER     = 0x10
    private static final int REG_PV_LOWER     = 0x11
    private static final int REG_MANUFACTURER = 0xFE
    private static final int REG_DIE_ID       = 0xFF

    /** Last user-configured MODE bits; saved so wake() can restore them. */
    private int savedMode = MODE_SHUNT_BUS_CONT

    /**
     * Construct the full driver with a uniform shunt resistance for all channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω applied to all three channels
     */
    Ina3221Full(Transport transport, double rShunt = 0.1) {
        super(transport, rShunt)
    }

    /**
     * Construct the full driver with per-channel shunt resistances.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunts   shunt resistances in Ω for channels 1, 2, and 3
     */
    Ina3221Full(Transport transport, double[] rShunts) {
        super(transport, rShunts)
    }

    /**
     * Write the configuration register, preserving the channel-enable bits.
     *
     * @param avg    averaging mode (0–7)
     * @param vbusCt bus voltage conversion time (0–7)
     * @param vshCt  shunt voltage conversion time (0–7)
     * @param mode   operating mode (0–7); use MODE_* constants
     */
    void configure(int avg, int vbusCt, int vshCt, int mode) {
        savedMode = mode & 0x07
        int current = readReg(REG_CONFIG)
        int channelBits = current & 0x7000
        int val = channelBits |
                ((avg    & 0x07) << 9) |
                ((vbusCt & 0x07) << 6) |
                ((vshCt  & 0x07) << 3) |
                 (mode   & 0x07)
        writeReg(REG_CONFIG, val)
    }

    /**
     * Enable or disable a channel.
     *
     * @param channel channel number (1, 2, or 3)
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    void enableChannel(int channel, boolean enabled) {
        checkChannel(channel)
        int bit = 1 << (15 - channel)
        int cfg = readReg(REG_CONFIG)
        cfg = enabled ? (cfg | bit) : (cfg & ~bit)
        writeReg(REG_CONFIG, cfg & 0xFFFF)
    }

    /**
     * Read whether a channel is currently enabled.
     *
     * @param channel channel number (1, 2, or 3)
     * @return {@code true} if the channel enable bit is set
     */
    boolean channelEnabled(int channel) {
        checkChannel(channel)
        int bit = 1 << (15 - channel)
        (readReg(REG_CONFIG) & bit) != 0
    }

    /**
     * Read the Conversion Ready Flag (CVRF) from the Mask/Enable register.
     *
     * @return {@code true} if a conversion cycle has completed since the last read
     */
    boolean conversionReady() {
        (readReg(REG_MASK_ENABLE) & CVRF) != 0
    }

    /**
     * Set the critical alert limit for a channel.
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive)
     */
    void setCriticalAlert(int channel, double limitV) {
        checkChannel(channel)
        int reg = REG_CH1_CRIT + (channel - 1) * 2
        writeReg(reg, encodeAlertLimit(limitV))
    }

    /**
     * Set the warning alert limit for a channel.
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive)
     */
    void setWarningAlert(int channel, double limitV) {
        checkChannel(channel)
        int reg = REG_CH1_WARN + (channel - 1) * 2
        writeReg(reg, encodeAlertLimit(limitV))
    }

    /**
     * Read and clear the Mask/Enable register (alert flags).
     *
     * @return the raw 16-bit Mask/Enable register value
     */
    int alertFlags() {
        readReg(REG_MASK_ENABLE)
    }

    /**
     * Set the summation channel selection (SCC) bits and the summation limit.
     *
     * @param channels array of channel numbers to include in the sum (values 1–3)
     * @param limitV   summation shunt voltage limit in V
     */
    void setSummationChannels(int[] channels, double limitV) {
        int me = readReg(REG_MASK_ENABLE) & 0x0FFF
        for (int ch : channels) {
            checkChannel(ch)
            me |= (1 << (15 - ch))
        }
        writeReg(REG_MASK_ENABLE, me & 0xFFFF)
        int raw = ((int) Math.round(limitV / 40e-6)) & 0x7FFE
        writeReg(REG_SV_SUM_LIMIT, (raw << 1) & 0xFFFE)
    }

    /**
     * Read the shunt-voltage sum register.
     *
     * @return shunt-voltage sum in V
     */
    double summationValue() {
        def raw = readReg(REG_SV_SUM)
        def signed = (short) raw
        (signed >> 1) * 40e-6
    }

    /**
     * Set the power-valid upper and lower voltage thresholds.
     *
     * @param upperV upper threshold in V (register 0x10)
     * @param lowerV lower threshold in V (register 0x11)
     */
    void setPowerValidLimits(double upperV, double lowerV) {
        writeReg(REG_PV_UPPER, (((int) Math.round(upperV / 8e-3)) & 0x1FFF) << 3)
        writeReg(REG_PV_LOWER, (((int) Math.round(lowerV / 8e-3)) & 0x1FFF) << 3)
    }

    /**
     * Read the Power Valid Flag (PVF) from the Mask/Enable register.
     *
     * @return {@code true} if all enabled channels are within the power-valid window
     */
    boolean powerValid() {
        (readReg(REG_MASK_ENABLE) & PVF) != 0
    }

    /**
     * Put the device into power-down mode (MODE = 000).
     *
     * <p>The current MODE setting is saved so that {@link #wake()} can restore it.
     */
    void shutdown() {
        int cfg = readReg(REG_CONFIG)
        savedMode = cfg & 0x07
        writeReg(REG_CONFIG, (cfg & 0xFFF8) | MODE_POWERDOWN)
    }

    /**
     * Restore the operating mode saved by the last call to {@link #shutdown()} or
     * {@link #configure(int,int,int,int)}.
     */
    void wake() {
        int cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg & 0xFFF8) | (savedMode & 0x07))
    }

    /**
     * Trigger a software reset by setting the RST bit, then restore the saved mode.
     */
    void reset() {
        writeReg(REG_CONFIG, 0x8000)
        int defaults = 0x7127
        writeReg(REG_CONFIG, (defaults & 0xFFF8) | (savedMode & 0x07))
    }

    /**
     * Read the Manufacturer ID register.
     *
     * <p>Expected value: 0x5449 ("TI").
     *
     * @return manufacturer ID
     */
    int manufacturerId() {
        readReg(REG_MANUFACTURER)
    }

    /**
     * Read the Die ID register.
     *
     * <p>Expected value: 0x3220.
     *
     * @return die ID
     */
    int dieId() {
        readReg(REG_DIE_ID)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static int encodeAlertLimit(double limitV) {
        (((int) Math.round(limitV / 40e-6)) << 3) & 0xFFF8
    }
}
