package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * INA260 — full driver. Extends {@link Ina260Minimal} with configuration, triggered
 * conversions, alert management, conversion-ready polling, reset,
 * shutdown/wake, and device identification.
 *
 * <h2>Alert functions (bit positions in Mask/Enable register)</h2>
 * <ul>
 *   <li>{@link #OCL}  — over-current limit (bit 15)</li>
 *   <li>{@link #UCL}  — under-current limit (bit 14)</li>
 *   <li>{@link #BOL}  — bus voltage over-limit (bit 13)</li>
 *   <li>{@link #BUL}  — bus voltage under-limit (bit 12)</li>
 *   <li>{@link #POL}  — power over-limit (bit 11)</li>
 *   <li>{@link #CNVR} — conversion ready (bit 10)</li>
 * </ul>
 */
@CompileStatic
class Ina260Full extends Ina260Minimal {

    // --- Alert function constants ---
    static final int OCL  = 32768
    static final int UCL  = 16384
    static final int BOL  = 8192
    static final int BUL  = 4096
    static final int POL  = 2048
    static final int CNVR = 1024
    static final int AFF  = 0x0010

    // --- Averaging constants ---
    static final int AVG_1    = 0
    static final int AVG_4    = 1
    static final int AVG_16   = 2
    static final int AVG_64   = 3
    static final int AVG_128  = 4
    static final int AVG_256  = 5
    static final int AVG_512  = 6
    static final int AVG_1024 = 7

    // --- Conversion time constants ---
    static final int CT_140US  = 0
    static final int CT_204US  = 1
    static final int CT_332US  = 2
    static final int CT_588US  = 3
    static final int CT_1100US = 4
    static final int CT_2116US = 5
    static final int CT_4156US = 6
    static final int CT_8244US = 7

    // --- Mode constants ---
    static final int MODE_POWER_DOWN          = 0
    static final int MODE_SHUNT_TRIGGERED     = 1
    static final int MODE_BUS_TRIGGERED       = 2
    static final int MODE_SHUNT_BUS_TRIGGERED = 3
    static final int MODE_SHUNT_CONT          = 5
    static final int MODE_BUS_CONT            = 6
    static final int MODE_SHUNT_BUS_CONT      = 7

    /** Configuration register base: bits 14:12 must be written as 110. */
    private static final int CONFIG_BASE = 0x6000

    private int avg = 0
    private int vbusCt = 4
    private int ishCt = 4
    private int mode = 7

    /**
     * Construct the full driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the INA260 device address
     */
    Ina260Full(RegisterConnection connection) {
        super(connection)
    }

    private void writeConfig(int m) {
        writeReg(REG_CONFIG, CONFIG_BASE | (avg << 9) | (vbusCt << 6) | (ishCt << 3) | (m & 0x07))
    }

    /**
     * Write the Configuration register (bits 14:12 kept at 110).
     *
     * @param avg    averaging count (0–7, use {@code AVG_*} constants)
     * @param vbusCt bus voltage conversion time (0–7, use {@code CT_*} constants)
     * @param ishCt  shunt current conversion time (0–7, use {@code CT_*} constants)
     * @param mode   operating mode (0–7, use {@code MODE_*} constants)
     */
    void configure(int avg, int vbusCt, int ishCt, int mode) {
        this.avg = avg & 0x07
        this.vbusCt = vbusCt & 0x07
        this.ishCt = ishCt & 0x07
        this.mode = mode & 0x07
        writeConfig(this.mode)
    }

    /**
     * Start one triggered conversion with the current settings (rewrites the
     * Configuration register). Poll {@link #conversionReady()} for completion.
     *
     * @param triggerMode {@link #MODE_SHUNT_TRIGGERED}, {@link #MODE_BUS_TRIGGERED}
     *                    or {@link #MODE_SHUNT_BUS_TRIGGERED}
     */
    void trigger(int triggerMode) {
        writeConfig(triggerMode)
    }

    /** Start one triggered shunt + bus conversion with the current settings. */
    void trigger() {
        trigger(MODE_SHUNT_BUS_TRIGGERED)
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
     * Reading Mask/Enable clears CVRF.
     *
     * @return true if a conversion is ready to be read
     */
    boolean conversionReady() {
        (readReg(REG_MASK_EN) & 0x08) != 0
    }

    /**
     * Check whether a math overflow occurred (OVF flag, bit 2 of Mask/Enable).
     *
     * @return true if an overflow has occurred
     */
    boolean overflow() {
        (readReg(REG_MASK_EN) & 0x04) != 0
    }

    /**
     * Configure an alert function and its threshold (active-low, transparent).
     *
     * @param function alert function bitmask (e.g. {@link #POL})
     * @param limit    threshold: A for OCL/UCL, V for BOL/BUL, W for POL; ignored for CNVR
     */
    void setAlert(int function, double limit) {
        setAlert(function, limit, false, false)
    }

    /**
     * Configure an alert function and its threshold.
     *
     * <p>The Alert Limit register is written first, then Mask/Enable. The raw limit is
     * round(limit_A / 1.25e-3) for OCL/UCL, round(limit_V / 1.25e-3) for BOL/BUL,
     * round(limit_W / 10e-3) for POL; for CNVR the limit register is not written.
     *
     * @param function alert function bitmask (e.g. {@link #POL})
     * @param limit    physical threshold (A, V or W depending on the function)
     * @param polarity false = active-low (default), true = active-high
     * @param latch    false = transparent (default), true = latch until Mask/Enable is read
     */
    void setAlert(int function, double limit, boolean polarity, boolean latch) {
        if (function == OCL || function == UCL || function == BOL || function == BUL) {
            writeReg(REG_ALERT_LIM, ((int) Math.round(limit / 1.25e-3d)) & 0xFFFF)
        } else if (function == POL) {
            writeReg(REG_ALERT_LIM, ((int) Math.round(limit / 10e-3d)) & 0xFFFF)
        }
        writeReg(REG_MASK_EN, function | (polarity ? 0x0002 : 0) | (latch ? 0x0001 : 0))
    }

    /** Disable all alert functions (Mask/Enable = 0). */
    void clearAlert() {
        writeReg(REG_MASK_EN, 0)
    }

    /**
     * Read the raw Mask/Enable register value (clears CVRF and, in latch mode, AFF).
     *
     * @return raw 16-bit Mask/Enable register value
     */
    int alertFlags() {
        readReg(REG_MASK_EN)
    }

    /** Reset the chip (sets the RST bit); registers return to power-on defaults. */
    void reset() {
        writeReg(REG_CONFIG, 0x8000)
        avg = 0
        vbusCt = 4
        ishCt = 4
        mode = 7
    }

    /** Put the chip into power-down mode (MODE=000); the previous mode is kept for {@link #wake()}. */
    void shutdown() {
        writeConfig(MODE_POWER_DOWN)
    }

    /** Restore the operating mode that was active before {@link #shutdown()}. */
    void wake() {
        writeConfig(mode)
    }

    /**
     * Read the Manufacturer ID register.
     *
     * @return manufacturer ID (0x5449 = "TI")
     */
    int manufacturerId() {
        readReg(REG_MFR_ID)
    }

    /**
     * Read the Die ID register.
     *
     * @return die ID; {@code value >> 4} is 0x227, the low nibble is the revision
     */
    int dieId() {
        readReg(REG_DIE_ID)
    }
}
