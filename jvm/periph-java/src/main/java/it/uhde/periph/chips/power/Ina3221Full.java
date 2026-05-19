package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

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
public class Ina3221Full extends Ina3221Minimal {

    // ---- Mode constants ----
    /** Power-down mode (MODE bits = 000). */
    public static final int MODE_POWERDOWN       = 0;
    /** Single-shot shunt voltage measurement (MODE bits = 001). */
    public static final int MODE_SHUNT_TRIG      = 1;
    /** Single-shot bus voltage measurement (MODE bits = 010). */
    public static final int MODE_BUS_TRIG        = 2;
    /** Single-shot shunt and bus voltage measurement (MODE bits = 011). */
    public static final int MODE_SHUNT_BUS_TRIG  = 3;
    /** Continuous shunt voltage measurement (MODE bits = 101). */
    public static final int MODE_SHUNT_CONT      = 5;
    /** Continuous bus voltage measurement (MODE bits = 110). */
    public static final int MODE_BUS_CONT        = 6;
    /** Continuous shunt and bus voltage measurement (MODE bits = 111, default). */
    public static final int MODE_SHUNT_BUS_CONT  = 7;

    // ---- Mask/Enable bit positions ----
    /** Critical flag channel 1 (bit 9). */
    public static final int CF1  = 512;
    /** Critical flag channel 2 (bit 8). */
    public static final int CF2  = 256;
    /** Critical flag channel 3 (bit 7). */
    public static final int CF3  = 128;
    /** Summation flag (bit 6). */
    public static final int SF   = 64;
    /** Warning flag channel 1 (bit 5). */
    public static final int WF1  = 32;
    /** Warning flag channel 2 (bit 4). */
    public static final int WF2  = 16;
    /** Warning flag channel 3 (bit 3). */
    public static final int WF3  = 8;
    /** Power-valid flag (bit 2). */
    public static final int PVF  = 4;
    /** Timing control flag (bit 1). */
    public static final int TCF  = 2;
    /** Conversion-ready flag (bit 0). */
    public static final int CVRF = 1;

    /** Last user-configured MODE bits (3 bits); saved so wake() can restore them. */
    private int savedMode = MODE_SHUNT_BUS_CONT;

    /**
     * Construct the full driver with a uniform shunt resistance for all channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω applied to all three channels
     */
    public Ina3221Full(Transport transport, double rShunt) {
        super(transport, rShunt);
    }

    /**
     * Construct the full driver with per-channel shunt resistances.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunts   shunt resistances in Ω for channels 1, 2, and 3
     */
    public Ina3221Full(Transport transport, double[] rShunts) {
        super(transport, rShunts);
    }

    /**
     * Construct the full driver with the default shunt resistance (0.1 Ω).
     *
     * @param transport I²C transport bound to the INA3221 device address
     */
    public Ina3221Full(Transport transport) {
        super(transport);
    }

    /**
     * Write the configuration register, preserving the channel-enable bits.
     *
     * <p>The channel-enable bits (CH1en, CH2en, CH3en) in the current
     * configuration are read first and OR-ed into the new value so that
     * calling configure() does not inadvertently disable channels.
     *
     * @param avg    averaging mode (0–7): 0=1, 1=4, 2=16, 3=64, 4=128, 5=256, 6=512, 7=1024 samples
     * @param vbusCt bus voltage conversion time (0–7): 0=140 µs … 7=8.244 ms
     * @param vshCt  shunt voltage conversion time (0–7): 0=140 µs … 7=8.244 ms
     * @param mode   operating mode (0–7); use MODE_* constants
     * @throws IOException on I²C error
     */
    public void configure(int avg, int vbusCt, int vshCt, int mode) throws IOException {
        savedMode = mode & 0x07;
        int current = readReg(REG_CONFIG);
        int channelBits = current & 0x7000; // CH1en[14], CH2en[13], CH3en[12]
        int val = channelBits
                | ((avg   & 0x07) << 9)
                | ((vbusCt & 0x07) << 6)
                | ((vshCt  & 0x07) << 3)
                | (mode    & 0x07);
        writeReg(REG_CONFIG, val);
    }

    /**
     * Enable or disable a channel.
     *
     * <p>Reads the current configuration, toggles the CHnen bit, and writes
     * the modified value back.
     *
     * @param channel channel number (1, 2, or 3)
     * @param enabled {@code true} to enable, {@code false} to disable
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public void enableChannel(int channel, boolean enabled) throws IOException {
        checkChannel(channel);
        // Channel enable bits: CH1en=bit14, CH2en=bit13, CH3en=bit12
        int bit = 1 << (15 - channel);
        int cfg = readReg(REG_CONFIG);
        if (enabled) cfg |= bit;
        else         cfg &= ~bit;
        writeReg(REG_CONFIG, cfg & 0xFFFF);
    }

    /**
     * Read whether a channel is currently enabled.
     *
     * @param channel channel number (1, 2, or 3)
     * @return {@code true} if the channel enable bit is set
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public boolean channelEnabled(int channel) throws IOException {
        checkChannel(channel);
        int bit = 1 << (15 - channel);
        return (readReg(REG_CONFIG) & bit) != 0;
    }

    /**
     * Read the Conversion Ready Flag (CVRF) from the Mask/Enable register.
     *
     * <p>CVRF is set after a complete conversion cycle. Reading the Mask/Enable
     * register clears the latched alert flags but does not affect CVRF.
     *
     * @return {@code true} if a conversion cycle has completed since the last read
     * @throws IOException on I²C error
     */
    public boolean conversionReady() throws IOException {
        return (readReg(REG_MASK_ENABLE) & CVRF) != 0;
    }

    /**
     * Set the critical alert limit for a channel.
     *
     * <p>The alert fires when the shunt voltage magnitude exceeds this limit.
     * The value is encoded as a left-aligned 13-bit signed integer (same format
     * as the shunt voltage register).
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive; both polarities are checked by hardware)
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public void setCriticalAlert(int channel, double limitV) throws IOException {
        checkChannel(channel);
        int reg = REG_CH1_CRIT + (channel - 1) * 2;
        writeReg(reg, encodeAlertLimit(limitV));
    }

    /**
     * Set the warning alert limit for a channel.
     *
     * <p>The alert fires when the shunt voltage magnitude exceeds this limit.
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive)
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public void setWarningAlert(int channel, double limitV) throws IOException {
        checkChannel(channel);
        int reg = REG_CH1_WARN + (channel - 1) * 2;
        writeReg(reg, encodeAlertLimit(limitV));
    }

    /**
     * Read and clear the Mask/Enable register (alert flags).
     *
     * <p>Reading this register clears all latched alert flag bits. The returned
     * value can be inspected against the CF1, CF2, CF3, SF, WF1, WF2, WF3, PVF,
     * TCF, and CVRF constants.
     *
     * @return the raw 16-bit Mask/Enable register value
     * @throws IOException on I²C error
     */
    public int alertFlags() throws IOException {
        return readReg(REG_MASK_ENABLE);
    }

    /**
     * Set the summation channel selection (SCC) bits and the summation limit.
     *
     * <p>The SCC bits in the Mask/Enable register determine which channels
     * contribute to the shunt-voltage sum. The sum limit is written to register
     * 0x0E using the same left-aligned 14-bit signed format as the sum register.
     *
     * @param channels  array of channel numbers to include in the sum (values 1–3)
     * @param limitV    summation shunt voltage limit in V
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if any channel value is not 1, 2, or 3
     */
    public void setSummationChannels(int[] channels, double limitV) throws IOException {
        // SCC bits in Mask/Enable: SCC1=bit14, SCC2=bit13, SCC3=bit12
        int me = readReg(REG_MASK_ENABLE) & 0x0FFF; // clear existing SCC bits
        for (int ch : channels) {
            checkChannel(ch);
            me |= (1 << (15 - ch));
        }
        writeReg(REG_MASK_ENABLE, me & 0xFFFF);
        // Sum limit uses left-aligned 14-bit signed format (shift left by 1)
        int raw = ((int) Math.round(limitV / 40e-6)) & 0x7FFE;
        writeReg(REG_SV_SUM_LIMIT, (raw << 1) & 0xFFFE);
    }

    /**
     * Read the shunt-voltage sum register.
     *
     * <p>The register holds a 14-bit signed value, left-aligned by 1 bit.
     * The LSB is 20 µV (40 µV × 0.5 due to the extra shift).
     *
     * @return shunt-voltage sum in V
     * @throws IOException on I²C error
     */
    public double summationValue() throws IOException {
        int raw = readReg(REG_SV_SUM);
        // 14-bit signed, left-aligned by 1 → arithmetic right-shift by 1
        return ((short) raw >> 1) * 40e-6;
    }

    /**
     * Set the power-valid upper and lower voltage thresholds.
     *
     * <p>The power-valid flag (PVF) is set when all enabled bus voltages are
     * between lowerV and upperV. Thresholds are 12-bit unsigned, left-aligned
     * by 3 bits (8 mV LSB, same as bus voltage).
     *
     * @param upperV upper threshold in V (register 0x10, reset = 10.000 V)
     * @param lowerV lower threshold in V (register 0x11, reset = 9.000 V)
     * @throws IOException on I²C error
     */
    public void setPowerValidLimits(double upperV, double lowerV) throws IOException {
        writeReg(REG_PV_UPPER, ((int) Math.round(upperV / 8e-3) & 0x1FFF) << 3);
        writeReg(REG_PV_LOWER, ((int) Math.round(lowerV / 8e-3) & 0x1FFF) << 3);
    }

    /**
     * Read the Power Valid Flag (PVF) from the Mask/Enable register.
     *
     * <p>PVF is set when all enabled bus voltages are within the power-valid
     * window set by {@link #setPowerValidLimits(double, double)}.
     *
     * @return {@code true} if all enabled channels are within the power-valid window
     * @throws IOException on I²C error
     */
    public boolean powerValid() throws IOException {
        return (readReg(REG_MASK_ENABLE) & PVF) != 0;
    }

    /**
     * Put the device into power-down mode (MODE = 000).
     *
     * <p>The current MODE setting is saved so that {@link #wake()} can restore it.
     *
     * @throws IOException on I²C error
     */
    public void shutdown() throws IOException {
        int cfg = readReg(REG_CONFIG);
        savedMode = cfg & 0x07;
        writeReg(REG_CONFIG, (cfg & 0xFFF8) | MODE_POWERDOWN);
    }

    /**
     * Restore the operating mode saved by the last call to {@link #shutdown()} or
     * {@link #configure(int, int, int, int)}.
     *
     * <p>If neither method has been called, the default continuous shunt+bus mode
     * (7) is restored.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        int cfg = readReg(REG_CONFIG);
        writeReg(REG_CONFIG, (cfg & 0xFFF8) | (savedMode & 0x07));
    }

    /**
     * Trigger a software reset by setting the RST bit, then restore the user
     * configuration by writing the saved MODE back into the configuration register.
     *
     * <p>After reset, the chip returns to its hardware defaults. The previously
     * saved mode is written back so the chip resumes normal operation.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        // RST bit is bit 15 of configuration register
        writeReg(REG_CONFIG, 0x8000);
        // The reset value is 0x7127; restore saved mode into MODE bits
        int defaults = 0x7127;
        writeReg(REG_CONFIG, (defaults & 0xFFF8) | (savedMode & 0x07));
    }

    /**
     * Read the Manufacturer ID register.
     *
     * <p>Expected value: 0x5449 ("TI").
     *
     * @return manufacturer ID
     * @throws IOException on I²C error
     */
    public int manufacturerId() throws IOException {
        return readReg(REG_MANUFACTURER);
    }

    /**
     * Read the Die ID register.
     *
     * <p>Expected value: 0x3220.
     *
     * @return die ID
     * @throws IOException on I²C error
     */
    public int dieId() throws IOException {
        return readReg(REG_DIE_ID);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Encode a shunt voltage alert limit into the left-aligned 13-bit register format.
     *
     * @param limitV threshold in V
     * @return encoded 16-bit register value
     */
    private static int encodeAlertLimit(double limitV) {
        return ((int) Math.round(limitV / 40e-6) << 3) & 0xFFF8;
    }
}
