package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * INA226 — full driver. Extends {@link Ina226Minimal} with configuration,
 * alert management, conversion-ready polling, reset, shutdown/wake, and
 * device identification.
 *
 * <h2>Alert functions (bit positions in Mask/Enable register)</h2>
 * <ul>
 *   <li>{@link #SOL}  — shunt voltage over-limit (bit 15)</li>
 *   <li>{@link #SUL}  — shunt voltage under-limit (bit 14)</li>
 *   <li>{@link #BOL}  — bus voltage over-limit (bit 13)</li>
 *   <li>{@link #BUL}  — bus voltage under-limit (bit 12)</li>
 *   <li>{@link #POL}  — power over-limit (bit 11)</li>
 *   <li>{@link #CNVR} — conversion ready (bit 10)</li>
 * </ul>
 */
@CompileStatic
class Ina226Full extends Ina226Minimal {

    // --- Alert function constants ---
    static final int SOL  = 32768
    static final int SUL  = 16384
    static final int BOL  = 8192
    static final int BUL  = 4096
    static final int POL  = 2048
    static final int CNVR = 1024

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
    static final int MODE_POWERDOWN      = 0
    static final int MODE_SHUNT_TRIG     = 1
    static final int MODE_BUS_TRIG       = 2
    static final int MODE_SHUNT_BUS_TRIG = 3
    static final int MODE_SHUNT_CONT     = 5
    static final int MODE_BUS_CONT       = 6
    static final int MODE_SHUNT_BUS_CONT = 7

    /**
     * Construct the full driver with default shunt (0.1 Ω) and max current (2.0 A).
     *
     * @param transport I²C transport bound to the INA226 device address
     */
    Ina226Full(Transport transport) {
        super(transport)
    }

    /**
     * Construct the full driver.
     *
     * @param transport  I²C transport bound to the INA226 device address
     * @param rShunt     shunt resistor value in Ω
     * @param maxCurrent maximum expected current in A
     */
    Ina226Full(Transport transport, double rShunt, double maxCurrent) {
        super(transport, rShunt, maxCurrent)
    }

    /**
     * Write the Configuration register.
     *
     * @param avg    averaging count (0–7, use {@code AVG_*} constants)
     * @param vbusCt bus voltage conversion time (0–7, use {@code CT_*} constants)
     * @param vshCt  shunt voltage conversion time (0–7, use {@code CT_*} constants)
     * @param mode   operating mode (0–7, use {@code MODE_*} constants)
     */
    void configure(int avg, int vbusCt, int vshCt, int mode) {
        int cfg = ((avg    & 0x07) << 9) |
                  ((vbusCt & 0x07) << 6) |
                  ((vshCt  & 0x07) << 3) |
                   (mode   & 0x07)
        lastMode = mode & 0x07
        writeReg(REG_CONFIG, cfg)
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
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
     * Configure an alert function and its threshold.
     *
     * <p>The raw limit is derived from the physical limit:
     * <ul>
     *   <li>SOL/SUL: raw = int(limit_V / 2.5e-6)</li>
     *   <li>BOL/BUL: raw = int(limit_V / 1.25e-3)</li>
     *   <li>POL:     raw = int(limit_W / (25 × Current_LSB))</li>
     *   <li>CNVR:    limit is ignored</li>
     * </ul>
     *
     * @param function alert function bitmask (e.g. {@code POL})
     * @param limit    physical threshold (V for shunt/bus alerts, W for power alert)
     */
    void setAlert(int function, double limit) {
        int raw
        if (function == SOL || function == SUL) {
            raw = (int) (limit / 2.5e-6d)
        } else if (function == BOL || function == BUL) {
            raw = (int) (limit / 1.25e-3d)
        } else if (function == POL) {
            raw = (int) (limit / (25.0d * currentLsb))
        } else {
            raw = 0
        }
        writeReg(REG_MASK_EN, function)
        writeReg(REG_ALERT_LIM, raw & 0xFFFF)
    }

    /**
     * Read the raw Mask/Enable register value.
     *
     * <p>Reading this register also clears the alert latch. The flags of interest
     * are CVRF (bit 3) and OVF (bit 2); alert-function bits are in bits 10–15.
     *
     * @return raw 16-bit Mask/Enable register value
     */
    int alertFlags() {
        readReg(REG_MASK_EN)
    }

    /**
     * Reset the chip (sets RST bit) and re-write calibration.
     *
     * <p>After reset the chip returns to default configuration (0x4127). This method
     * re-writes the Calibration register so that current and power readings remain valid.
     */
    void reset() {
        writeReg(REG_CONFIG, 0x8000)
        writeReg(REG_CAL, cal)
        lastMode = 7
    }

    /**
     * Put the chip into power-down mode (MODE=000).
     *
     * <p>The current mode is preserved in {@code lastMode} so that {@link #wake()}
     * can restore it.
     */
    void shutdown() {
        int cfg = readReg(REG_CONFIG)
        lastMode = cfg & 0x07
        writeReg(REG_CONFIG, cfg & ~0x07)
    }

    /**
     * Restore the operating mode that was active before {@link #shutdown()}.
     */
    void wake() {
        int cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg & ~0x07) | (lastMode & 0x07))
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
     * @return die ID (0x2260 for INA226)
     */
    int dieId() {
        readReg(REG_DIE_ID)
    }
}
