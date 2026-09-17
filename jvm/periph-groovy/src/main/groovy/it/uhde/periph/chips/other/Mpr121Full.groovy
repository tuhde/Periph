package it.uhde.periph.chips.other

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * MPR121 — full driver. Extends Mpr121Minimal with explicit Stop/Run
 * control, per-electrode and per-proximity threshold configuration,
 * filtered-data and baseline access, baseline filter and AFE (sampling)
 * configuration, debounce, autoconfig recomputation, OOR status, and
 * over-current flag clear.
 */
@CompileStatic
class Mpr121Full extends Mpr121Minimal {

    /** AUTOCONFIG1 OORIE interrupt source (bit 2 of 0x7C). */
    static final int SOURCE_OOR = 0x04
    /** AUTOCONFIG1 ARFIE interrupt source (bit 1 of 0x7C). */
    static final int SOURCE_ARF = 0x02
    /** AUTOCONFIG1 ACFIE interrupt source (bit 0 of 0x7C). */
    static final int SOURCE_ACF = 0x01

    Mpr121Full(Connection connection) {
        super(connection)
    }

    /** Software-reset the chip and re-apply Minimal defaults. */
    void reset() {
        softReset()
        writeReg(REG_MHDR, 0x01)
        writeReg(REG_NHDR, 0x01)
        writeReg(REG_MHDF, 0x01)
        writeReg(REG_NHDF, 0x01)
        writeReg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT)
        writeReg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT)
        writeReg(REG_USL, USL_3V3)
        writeReg(REG_TL, TL_3V3)
        writeReg(REG_LSL, LSL_3V3)
        writeReg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT)
        for (int n = 0; n < 12; n++) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT)
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT)
        }
        writeReg(REG_ECR, ECR_DEFAULT)
    }

    /** Enter Stop Mode (ECR=0x00). */
    void stop() {
        writeReg(REG_ECR, 0x00)
    }

    /**
     * Enter Run Mode with the given electrode configuration.
     * @param nElectrodes 1-12
     * @param cl 0-3
     * @param eleproxEn 0-3
     */
    void start(int nElectrodes, int cl, int eleproxEn) {
        if (nElectrodes < 1 || nElectrodes > 12) {
            throw new IllegalArgumentException("n_electrodes must be in 1..12")
        }
        if (cl < 0 || cl > 3) {
            throw new IllegalArgumentException("cl must be in 0..3")
        }
        if (eleproxEn < 0 || eleproxEn > 3) {
            throw new IllegalArgumentException("eleprox_en must be in 0..3")
        }
        int ecr = ((cl & 0x03) << 6) | ((eleproxEn & 0x03) << 4) | (nElectrodes & 0x0F)
        writeReg(REG_ECR, ecr)
    }

    /** Set touch and release thresholds for a single electrode. */
    void configureThresholds(int electrode, int touch, int release) {
        if (electrode < 0 || electrode > 11) {
            throw new IllegalArgumentException("electrode must be in 0..11")
        }
        writeReg(REG_E0TTH + 2 * electrode, touch & 0xFF)
        writeReg(REG_E0RTH + 2 * electrode, release & 0xFF)
    }

    /** Apply the same touch and release thresholds to all 12 electrodes. */
    void configureAllThresholds(int touch, int release) {
        for (int n = 0; n < 12; n++) {
            configureThresholds(n, touch, release)
        }
    }

    /** Set ELEPROX touch and release thresholds. */
    void configureProximityThresholds(int touch, int release) {
        writeReg(REG_EPROXTTH, touch & 0xFF)
        writeReg(REG_EPROXRTH, release & 0xFF)
    }

    /** Read the 10-bit filtered capacitance data for an electrode. */
    int filtered(int electrode) {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12")
        }
        int addr = (electrode == 12) ? 0x1C : (0x04 + 2 * electrode)
        return readReg16(addr)
    }

    /** Read the 10-bit baseline for an electrode. */
    int baseline(int electrode) {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12")
        }
        int addr = (electrode == 12) ? 0x2A : (0x1E + electrode)
        return (readReg(addr) & 0xFF) << 2
    }

    /** Write a baseline value (Stop Mode only). */
    void setBaseline(int electrode, int value) {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12")
        }
        int addr = (electrode == 12) ? 0x2A : (0x1E + electrode)
        writeReg(addr, (value >> 2) & 0xFF)
    }

    /** Read the 13-bit out-of-range bitmask. */
    int oorStatus() {
        byte[] buf = connection.writeRead([REG_ELE0_7_OOR] as byte[], 2)
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x1F) << 8)
    }

    /** Set the global baseline filter parameters (Stop Mode). */
    void configureBaselineFilter(int mhdr, int nhdr, int nclr, int fdlr,
                                int mhdf, int nhdf, int nclf, int fdlf,
                                int nhdt, int nclt, int fdlt) {
        writeReg(REG_MHDR, mhdr & 0x3F)
        writeReg(REG_NHDR, nhdr & 0x3F)
        writeReg(0x2D, nclr & 0xFF)
        writeReg(0x2E, fdlr & 0xFF)
        writeReg(REG_MHDF, mhdf & 0x3F)
        writeReg(REG_NHDF, nhdf & 0x3F)
        writeReg(0x31, nclf & 0xFF)
        writeReg(0x32, fdlf & 0xFF)
        writeReg(0x33, nhdt & 0x3F)
        writeReg(0x34, nclt & 0xFF)
        writeReg(0x35, fdlt & 0xFF)
    }

    /** Set global AFE (sampling) configuration (Stop Mode). */
    void configureSampling(int cdc, int cdt, int ffi, int sfi, int esi) {
        int cdcCfg = ((ffi & 0x03) << 6) | (cdc & 0x3F)
        int cdtCfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07)
        writeReg(REG_CDC_CONFIG, cdcCfg)
        writeReg(REG_CDT_CONFIG, cdtCfg)
    }

    /** Set debounce counts (Stop Mode). */
    void configureDebounce(int touch, int release) {
        int deb = ((release & 0x07) << 4) | (touch & 0x07)
        writeReg(REG_DEBOUNCE, deb)
    }

    /** Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode). */
    void configureAutoconfig(int vddMv, int retry, boolean scts,
                              boolean are, boolean ace) {
        int usl = (int) (((long) (vddMv - 700) * 256) / vddMv)
        int tl  = (int) (usl * 0.9f)
        int lsl = (int) (usl * 0.65f)
        writeReg(REG_USL, usl & 0xFF)
        writeReg(REG_TL, tl & 0xFF)
        writeReg(REG_LSL, lsl & 0xFF)
        int cdcCfg = readReg(REG_CDC_CONFIG)
        int ffi = (cdcCfg >> 6) & 0x03
        int autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4) |
                          (are ? 0x08 : 0) | (ace ? 0x01 : 0)
        writeReg(REG_AUTOCONFIG0, autoconfig0)
        int autoconfig1 = scts ? 0x80 : 0x00
        writeReg(REG_AUTOCONFIG1, autoconfig1)
    }

    /** Return true if the ELEPROX virtual electrode is touched. */
    boolean proximityTouched() {
        return (readReg(REG_ELE8_PROX_TCH) & 0x10) != 0
    }

    /** Clear the OVCF bit in register 0x01. */
    void clearOvercurrent() {
        int raw = readReg(REG_ELE8_PROX_TCH)
        writeReg(REG_ELE8_PROX_TCH, raw & 0x7F)
    }

    /** Enable one of the AUTOCONFIG1-based interrupt sources. */
    void enableInterrupt(int source) {
        int cur = readReg(REG_AUTOCONFIG1) & 0x00
        writeReg(REG_AUTOCONFIG1, cur | (source & 0x07))
    }

    /** Disable one of the AUTOCONFIG1-based interrupt sources. */
    void disableInterrupt(int source) {
        int cur = readReg(REG_AUTOCONFIG1)
        writeReg(REG_AUTOCONFIG1, cur & ~(source & 0x07))
    }
}
