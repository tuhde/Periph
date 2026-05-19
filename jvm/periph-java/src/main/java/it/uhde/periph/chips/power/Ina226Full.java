package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * INA226 — full driver. Extends {@link Ina226Minimal} with configuration,
 * alert management, conversion-ready polling, reset, shutdown/wake, and
 * device identification.
 *
 * <h2>Alert functions (bit positions in Mask/Enable register)</h2>
 * <ul>
 *   <li>{@link #SOL}  — shunt over-limit (bit 15)</li>
 *   <li>{@link #SUL}  — shunt under-limit (bit 14)</li>
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
 * {@link #MODE_POWERDOWN}, {@link #MODE_SHUNT_TRIG}, {@link #MODE_BUS_TRIG},
 * {@link #MODE_SHUNT_BUS_TRIG}, {@link #MODE_SHUNT_CONT}, {@link #MODE_BUS_CONT},
 * {@link #MODE_SHUNT_BUS_CONT}
 */
public class Ina226Full extends Ina226Minimal {

    // --- Alert function constants (bit mask values in Mask/Enable register) ---
    /** Alert: shunt voltage over-limit (bit 15). */
    public static final int SOL  = 32768;
    /** Alert: shunt voltage under-limit (bit 14). */
    public static final int SUL  = 16384;
    /** Alert: bus voltage over-limit (bit 13). */
    public static final int BOL  = 8192;
    /** Alert: bus voltage under-limit (bit 12). */
    public static final int BUL  = 4096;
    /** Alert: power over-limit (bit 11). */
    public static final int POL  = 2048;
    /** Alert: conversion ready (bit 10). */
    public static final int CNVR = 1024;

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
    public static final int MODE_POWERDOWN       = 0;
    /** Mode: shunt voltage triggered (1). */
    public static final int MODE_SHUNT_TRIG      = 1;
    /** Mode: bus voltage triggered (2). */
    public static final int MODE_BUS_TRIG        = 2;
    /** Mode: shunt and bus voltage triggered (3). */
    public static final int MODE_SHUNT_BUS_TRIG  = 3;
    /** Mode: shunt voltage continuous (5). */
    public static final int MODE_SHUNT_CONT      = 5;
    /** Mode: bus voltage continuous (6). */
    public static final int MODE_BUS_CONT        = 6;
    /** Mode: shunt and bus voltage continuous (7). */
    public static final int MODE_SHUNT_BUS_CONT  = 7;

    /**
     * Construct the full driver with default shunt (0.1 Ω) and max current (2.0 A).
     *
     * @param transport I²C transport bound to the INA226 device address
     * @throws IOException on I²C error
     */
    public Ina226Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Construct the full driver.
     *
     * @param transport  I²C transport bound to the INA226 device address
     * @param rShunt     shunt resistor value in Ω
     * @param maxCurrent maximum expected current in A
     * @throws IOException on I²C error
     */
    public Ina226Full(Transport transport, double rShunt, double maxCurrent) throws IOException {
        super(transport, rShunt, maxCurrent);
    }

    /**
     * Write the Configuration register.
     *
     * <p>Bits 15 (RST) and 12 are reserved/zero. The supplied fields are packed as:
     * {@code [0 0 0 0 | AVG2 AVG1 AVG0 | VBUSCT2 VBUSCT1 VBUSCT0 | VSHCT2 VSHCT1 VSHCT0 | MODE2 MODE1 MODE0]}.
     *
     * @param avg    averaging count (0–7, use {@code AVG_*} constants)
     * @param vbusCt bus voltage conversion time (0–7, use {@code CT_*} constants)
     * @param vshCt  shunt voltage conversion time (0–7, use {@code CT_*} constants)
     * @param mode   operating mode (0–7, use {@code MODE_*} constants)
     * @throws IOException on I²C error
     */
    public void configure(int avg, int vbusCt, int vshCt, int mode) throws IOException {
        int cfg = ((avg   & 0x07) << 9)
                | ((vbusCt & 0x07) << 6)
                | ((vshCt  & 0x07) << 3)
                |  (mode   & 0x07);
        lastMode = mode & 0x07;
        writeReg(REG_CONFIG, cfg);
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
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
     * <p>An overflow means the current or power result exceeded the range of the
     * register; reduce maxCurrent or increase rShunt to avoid this condition.
     *
     * @return true if an overflow has occurred
     * @throws IOException on I²C error
     */
    public boolean overflow() throws IOException {
        return (readReg(REG_MASK_EN) & 0x04) != 0;
    }

    /**
     * Configure an alert function and its threshold.
     *
     * <p>The {@code function} value is one of the alert-function constants
     * ({@link #SOL}, {@link #SUL}, {@link #BOL}, {@link #BUL}, {@link #POL},
     * {@link #CNVR}). The raw alert limit is derived from the physical limit
     * according to the function:
     * <ul>
     *   <li>SOL/SUL (shunt): raw = int(limit_V / 2.5e-6)</li>
     *   <li>BOL/BUL (bus):   raw = int(limit_V / 1.25e-3)</li>
     *   <li>POL (power):     raw = int(limit_W / (25 × Current_LSB))</li>
     *   <li>CNVR:            limit is ignored (no threshold required)</li>
     * </ul>
     *
     * @param function alert function bitmask (e.g. {@link #POL})
     * @param limit    physical threshold (V for shunt/bus alerts, W for power alert)
     * @throws IOException on I²C error
     */
    public void setAlert(int function, double limit) throws IOException {
        int raw;
        if (function == SOL || function == SUL) {
            raw = (int) (limit / 2.5e-6);
        } else if (function == BOL || function == BUL) {
            raw = (int) (limit / 1.25e-3);
        } else if (function == POL) {
            raw = (int) (limit / (25.0 * currentLsb));
        } else {
            raw = 0;
        }
        writeReg(REG_MASK_EN, function);
        writeReg(REG_ALERT_LIM, raw & 0xFFFF);
    }

    /**
     * Read the raw Mask/Enable register value.
     *
     * <p>Reading this register also clears the alert latch. The flags of interest
     * are CVRF (bit 3) and OVF (bit 2); alert-function bits are in bits 10–15.
     *
     * @return raw 16-bit Mask/Enable register value
     * @throws IOException on I²C error
     */
    public int alertFlags() throws IOException {
        return readReg(REG_MASK_EN);
    }

    /**
     * Reset the chip (sets RST bit in Configuration register) and re-write calibration.
     *
     * <p>After reset the chip returns to default configuration (0x4127). This method
     * re-writes the Calibration register so that current and power readings remain valid.
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        writeReg(REG_CONFIG, 0x8000);
        writeReg(REG_CAL, cal);
        lastMode = 7;
    }

    /**
     * Put the chip into power-down mode (MODE=000).
     *
     * <p>The current mode is preserved in {@code lastMode} so that {@link #wake()}
     * can restore it.
     *
     * @throws IOException on I²C error
     */
    public void shutdown() throws IOException {
        int cfg = readReg(REG_CONFIG);
        lastMode = cfg & 0x07;
        writeReg(REG_CONFIG, cfg & ~0x07);
    }

    /**
     * Restore the operating mode that was active before {@link #shutdown()}.
     *
     * @throws IOException on I²C error
     */
    public void wake() throws IOException {
        int cfg = readReg(REG_CONFIG);
        writeReg(REG_CONFIG, (cfg & ~0x07) | (lastMode & 0x07));
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
     * @return die ID (0x2260 for INA226)
     * @throws IOException on I²C error
     */
    public int dieId() throws IOException {
        return readReg(REG_DIE_ID);
    }
}
