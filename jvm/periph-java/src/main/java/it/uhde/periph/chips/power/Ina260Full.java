package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * INA260 — full driver. Extends {@link Ina260Minimal} with configuration,
 * alert management, conversion-ready polling, reset, shutdown/wake, and
 * device identification.
 *
 * <h2>Alert functions (bit positions in Mask/Enable register)</h2>
 * <ul>
 *   <li>{@link #OCL}  — over-current limit (bit 15)</li>
 *   <li>{@link #UCL}  — under-current limit (bit 14)</li>
 *   <li>{@link #BOL}  — bus over-limit (bit 13)</li>
 *   <li>{@link #BUL}  — bus under-limit (bit 12)</li>
 *   <li>{@link #POL}  — power over-limit (bit 11)</li>
 *   <li>{@link #CNVR} — conversion ready (bit 10)</li>
 * </ul>
 *
 * <h2>Averaging constants</h2>
 * {@link #AVG_1}, {@link #AVG_4}, {@link #AVG_16}, {@link #AVG_64},
 * {@link #AVG_128}, {@link #AVG_256}, {@link #AVG_512}, {@link #AVG_1024}
 *
 * <h2>Conversion time constants</h2>
 * {@link #CT_140US}, {@link #CT_204US}, {@link #CT_332US}, {@link #CT_588US},
 * {@link #CT_1100US}, {@link #CT_2116US}, {@link #CT_4156US}, {@link #CT_8244US}
 *
 * <h2>Mode constants</h2>
 * {@link #MODE_POWER_DOWN}, {@link #MODE_SHUNT_TRIGGERED}, {@link #MODE_BUS_TRIGGERED},
 * {@link #MODE_SHUNT_BUS_TRIGGERED}, {@link #MODE_SHUNT_CONT}, {@link #MODE_BUS_CONT},
 * {@link #MODE_SHUNT_BUS_CONT}
 */
public class Ina260Full extends Ina260Minimal {

    // --- Alert function constants (bit mask values in Mask/Enable register) ---
    /** Alert: over-current limit (bit 15). */
    public static final int OCL  = 32768;
    /** Alert: under-current limit (bit 14). */
    public static final int UCL  = 16384;
    /** Alert: bus voltage over-limit (bit 13). */
    public static final int BOL  = 8192;
    /** Alert: bus voltage under-limit (bit 12). */
    public static final int BUL  = 4096;
    /** Alert: power over-limit (bit 11). */
    public static final int POL  = 2048;
    /** Alert: conversion ready (bit 10). */
    public static final int CNVR = 1024;
    /** Alert Function Flag (bit 4 of Mask/Enable, read-only). */
    public static final int AFF  = 0x0010;

    // --- Averaging constants ---
    /** Averaging: 1 sample. */
    public static final int AVG_1    = 0;
    /** Averaging: 4 samples. */
    public static final int AVG_4    = 1;
    /** Averaging: 16 samples. */
    public static final int AVG_16   = 2;
    /** Averaging: 64 samples. */
    public static final int AVG_64   = 3;
    /** Averaging: 128 samples. */
    public static final int AVG_128  = 4;
    /** Averaging: 256 samples. */
    public static final int AVG_256  = 5;
    /** Averaging: 512 samples. */
    public static final int AVG_512  = 6;
    /** Averaging: 1024 samples. */
    public static final int AVG_1024 = 7;

    // --- Conversion time constants ---
    /** Conversion time: 140 µs. */
    public static final int CT_140US  = 0;
    /** Conversion time: 204 µs. */
    public static final int CT_204US  = 1;
    /** Conversion time: 332 µs. */
    public static final int CT_332US  = 2;
    /** Conversion time: 588 µs. */
    public static final int CT_588US  = 3;
    /** Conversion time: 1.1 ms. */
    public static final int CT_1100US = 4;
    /** Conversion time: 2.116 ms. */
    public static final int CT_2116US = 5;
    /** Conversion time: 4.156 ms. */
    public static final int CT_4156US = 6;
    /** Conversion time: 8.244 ms. */
    public static final int CT_8244US = 7;

    // --- Mode constants ---
    /** Mode: power-down (0). */
    public static final int MODE_POWER_DOWN       = 0;
    /** Mode: shunt current triggered (1). */
    public static final int MODE_SHUNT_TRIGGERED      = 1;
    /** Mode: bus voltage triggered (2). */
    public static final int MODE_BUS_TRIGGERED        = 2;
    /** Mode: shunt current and bus voltage triggered (3). */
    public static final int MODE_SHUNT_BUS_TRIGGERED  = 3;
    /** Mode: shunt current continuous (5). */
    public static final int MODE_SHUNT_CONT      = 5;
    /** Mode: bus voltage continuous (6). */
    public static final int MODE_BUS_CONT        = 6;
    /** Mode: shunt current and bus voltage continuous (7). */
    public static final int MODE_SHUNT_BUS_CONT  = 7;

    /** Configuration register base: bits 14:12 must be written as 110. */
    private static final int CONFIG_BASE = 0x6000;

    private int avg = 0, vbusCt = 4, ishCt = 4, mode = 7;

    /**
     * Construct the full driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the INA260 device address
     * @throws IOException on I²C error
     */
    public Ina260Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    private void writeConfig(int m) throws IOException {
        writeReg(REG_CONFIG, CONFIG_BASE | (avg << 9) | (vbusCt << 6) | (ishCt << 3) | (m & 0x07));
    }

    /**
     * Write the Configuration register (bits 14:12 kept at 110).
     *
     * @param avg    averaging count (0–7, use {@code AVG_*} constants)
     * @param vbusCt bus voltage conversion time (0–7, use {@code CT_*} constants)
     * @param ishCt  shunt current conversion time (0–7, use {@code CT_*} constants)
     * @param mode   operating mode (0–7, use {@code MODE_*} constants)
     * @throws IOException on I²C error
     */
    public void configure(int avg, int vbusCt, int ishCt, int mode) throws IOException {
        this.avg = avg & 0x07;
        this.vbusCt = vbusCt & 0x07;
        this.ishCt = ishCt & 0x07;
        this.mode = mode & 0x07;
        writeConfig(this.mode);
    }

    /**
     * Start one triggered conversion with the current settings (rewrites the
     * Configuration register). Poll {@link #conversionReady()} for completion.
     *
     * @param triggerMode {@link #MODE_SHUNT_TRIGGERED}, {@link #MODE_BUS_TRIGGERED}
     *                    or {@link #MODE_SHUNT_BUS_TRIGGERED}
     * @throws IOException on I²C error
     */
    public void trigger(int triggerMode) throws IOException {
        writeConfig(triggerMode);
    }

    /**
     * Start one triggered shunt + bus conversion with the current settings.
     *
     * @throws IOException on I²C error
     */
    public void trigger() throws IOException {
        trigger(MODE_SHUNT_BUS_TRIGGERED);
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
     * Reading Mask/Enable clears CVRF.
     *
     * @return true if a conversion is ready to be read
     * @throws IOException on I²C error
     */
    public boolean conversionReady() throws IOException {
        return (readReg(REG_MASK_EN) & 0x08) != 0;
    }

    /**
     * Check whether a math overflow occurred (OVF flag, bit 2 of Mask/Enable).
     *
     * @return true if an overflow has occurred
     * @throws IOException on I²C error
     */
    public boolean overflow() throws IOException {
        return (readReg(REG_MASK_EN) & 0x04) != 0;
    }

    /**
     * Configure an alert function and its threshold (active-low, transparent).
     *
     * @param function alert function bitmask (e.g. {@link #POL})
     * @param limit    threshold: A for OCL/UCL, V for BOL/BUL, W for POL; ignored for CNVR
     * @throws IOException on I²C error
     * @see #setAlert(int, double, boolean, boolean)
     */
    public void setAlert(int function, double limit) throws IOException {
        setAlert(function, limit, false, false);
    }

    /**
     * Configure an alert function and its threshold.
     *
     * <p>The Alert Limit register is written first, then Mask/Enable. The raw limit is:
     * <ul>
     *   <li>OCL/UCL: round(limit_A / 1.25e-3) (signed)</li>
     *   <li>BOL/BUL: round(limit_V / 1.25e-3)</li>
     *   <li>POL:     round(limit_W / 10e-3)</li>
     *   <li>CNVR:    limit ignored, limit register not written</li>
     * </ul>
     *
     * @param function alert function bitmask (e.g. {@link #POL})
     * @param limit    physical threshold (A, V or W depending on the function)
     * @param polarity false = active-low (default), true = active-high
     * @param latch    false = transparent (default), true = latch until Mask/Enable is read
     * @throws IOException on I²C error
     */
    public void setAlert(int function, double limit, boolean polarity, boolean latch) throws IOException {
        if (function == OCL || function == UCL || function == BOL || function == BUL) {
            writeReg(REG_ALERT_LIM, (int) Math.round(limit / 1.25e-3) & 0xFFFF);
        } else if (function == POL) {
            writeReg(REG_ALERT_LIM, (int) Math.round(limit / 10e-3) & 0xFFFF);
        }
        writeReg(REG_MASK_EN, function | (polarity ? 0x0002 : 0) | (latch ? 0x0001 : 0));
    }

    /**
     * Disable all alert functions (Mask/Enable = 0).
     *
     * @throws IOException on I²C error
     */
    public void clearAlert() throws IOException {
        writeReg(REG_MASK_EN, 0);
    }

    /**
     * Read the raw Mask/Enable register value (clears CVRF and, in latch mode, AFF).
     *
     * @return raw 16-bit Mask/Enable register value
     * @throws IOException on I²C error
     */
    public int alertFlags() throws IOException {
        return readReg(REG_MASK_EN);
    }

    /**
     * Reset the chip (sets the RST bit); registers return to power-on defaults.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        writeReg(REG_CONFIG, 0x8000);
        avg = 0; vbusCt = 4; ishCt = 4; mode = 7;
    }

    /**
     * Put the chip into power-down mode (MODE=000); the previous mode is kept for {@link #wake()}.
     *
     * @throws IOException on I²C error
     */
    public void shutdown() throws IOException {
        writeConfig(MODE_POWER_DOWN);
    }

    /**
     * Restore the operating mode that was active before {@link #shutdown()}.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        writeConfig(mode);
    }

    /**
     * Read the Manufacturer ID register.
     *
     * @return manufacturer ID (0x5449 = "TI")
     * @throws IOException on I²C error
     */
    public int manufacturerId() throws IOException {
        return readReg(REG_MFR_ID);
    }

    /**
     * Read the Die ID register.
     *
     * @return die ID; {@code value >> 4} is 0x227, the low nibble is the revision
     * @throws IOException on I²C error
     */
    public int dieId() throws IOException {
        return readReg(REG_DIE_ID);
    }
}
