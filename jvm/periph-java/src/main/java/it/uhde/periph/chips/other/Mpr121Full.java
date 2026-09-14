package it.uhde.periph.chips.other;

import java.io.IOException;

/**
 * MPR121 — full driver. Extends {@link Mpr121Minimal} with explicit Stop/Run
 * control, per-electrode and per-proximity threshold configuration,
 * filtered-data and baseline access, baseline filter and AFE (sampling)
 * configuration, debounce, autoconfig recomputation, OOR status, and
 * over-current flag clear.
 */
public class Mpr121Full extends Mpr121Minimal {

    /** AUTOCONFIG1 OORIE interrupt source (bit 2 of 0x7C). */
    public static final int SOURCE_OOR = 0x04;
    /** AUTOCONFIG1 ARFIE interrupt source (bit 1 of 0x7C). */
    public static final int SOURCE_ARF = 0x02;
    /** AUTOCONFIG1 ACFIE interrupt source (bit 0 of 0x7C). */
    public static final int SOURCE_ACF = 0x01;

    /**
     * Construct the full driver.
     *
     * @param connection I²C connection bound to the MPR121 device address
     * @throws IOException on I²C error
     */
    public Mpr121Full(it.uhde.periph.connection.Connection connection) throws IOException {
        super(connection);
    }

    /** Software-reset the chip and re-apply Minimal defaults. */
    public void reset() throws IOException {
        softReset();
        writeReg(REG_MHDR, 0x01);
        writeReg(REG_NHDR, 0x01);
        writeReg(REG_MHDF, 0x01);
        writeReg(REG_NHDF, 0x01);
        writeReg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT);
        writeReg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT);
        writeReg(REG_USL, USL_3V3);
        writeReg(REG_TL,  TL_3V3);
        writeReg(REG_LSL, LSL_3V3);
        writeReg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT);
        for (int n = 0; n < 12; n++) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT);
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT);
        }
        writeReg(REG_ECR, ECR_DEFAULT);
    }

    /** Enter Stop Mode (ECR=0x00). Required before writing most config registers. */
    public void stop() throws IOException {
        writeReg(REG_ECR, 0x00);
    }

    /**
     * Enter Run Mode with the given electrode configuration.
     *
     * @param nElectrodes number of electrodes to enable 1-12
     * @param cl calibration lock / baseline init 0-3
     * @param eleproxEn proximity enable 0-3
     */
    public void start(int nElectrodes, int cl, int eleproxEn) throws IOException {
        if (nElectrodes < 1 || nElectrodes > 12) {
            throw new IllegalArgumentException("n_electrodes must be in 1..12");
        }
        if (cl < 0 || cl > 3) {
            throw new IllegalArgumentException("cl must be in 0..3");
        }
        if (eleproxEn < 0 || eleproxEn > 3) {
            throw new IllegalArgumentException("eleprox_en must be in 0..3");
        }
        int ecr = ((cl & 0x03) << 6) | ((eleproxEn & 0x03) << 4) | (nElectrodes & 0x0F);
        writeReg(REG_ECR, ecr);
    }

    /** Set touch and release thresholds for a single electrode. */
    public void configureThresholds(int electrode, int touch, int release) throws IOException {
        if (electrode < 0 || electrode > 11) {
            throw new IllegalArgumentException("electrode must be in 0..11");
        }
        writeReg(REG_E0TTH + 2 * electrode, touch & 0xFF);
        writeReg(REG_E0RTH + 2 * electrode, release & 0xFF);
    }

    /** Apply the same touch and release thresholds to all 12 electrodes. */
    public void configureAllThresholds(int touch, int release) throws IOException {
        for (int n = 0; n < 12; n++) {
            configureThresholds(n, touch, release);
        }
    }

    /** Set ELEPROX touch and release thresholds. */
    public void configureProximityThresholds(int touch, int release) throws IOException {
        writeReg(REG_EPROXTTH, touch & 0xFF);
        writeReg(REG_EPROXRTH, release & 0xFF);
    }

    /**
     * Read the 10-bit filtered capacitance data for an electrode.
     *
     * @param electrode 0-11 for ELE0-ELE11, 12 for ELEPROX
     * @return 10-bit value, inversely proportional to capacitance
     */
    public int filtered(int electrode) throws IOException {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12");
        }
        int addr = (electrode == 12) ? 0x1C : (0x04 + 2 * electrode);
        return readReg16(addr);
    }

    /**
     * Read the 10-bit baseline for an electrode.
     *
     * The chip stores only the 8 MSBs of the baseline; the returned value is
     * shifted left by 2 to align with {@link #filtered(int)}.
     */
    public int baseline(int electrode) throws IOException {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12");
        }
        int addr = (electrode == 12) ? 0x2A : (0x1E + electrode);
        return (readReg(addr) & 0xFF) << 2;
    }

    /** Write a baseline value (Stop Mode only). */
    public void setBaseline(int electrode, int value) throws IOException {
        if (electrode < 0 || electrode > 12) {
            throw new IllegalArgumentException("electrode must be in 0..12");
        }
        int addr = (electrode == 12) ? 0x2A : (0x1E + electrode);
        writeReg(addr, (value >> 2) & 0xFF);
    }

    /** Read the 13-bit out-of-range bitmask. */
    public int oorStatus() throws IOException {
        byte[] buf = connection.writeRead(new byte[] { REG_ELE0_7_OOR }, 2);
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x1F) << 8);
    }

    /** Set the global baseline filter parameters (Stop Mode). */
    public void configureBaselineFilter(int mhdr, int nhdr, int nclr, int fdlr,
                                        int mhdf, int nhdf, int nclf, int fdlf,
                                        int nhdt, int nclt, int fdlt) throws IOException {
        writeReg(REG_MHDR, mhdr & 0x3F);
        writeReg(REG_NHDR, nhdr & 0x3F);
        writeReg(0x2D, nclr & 0xFF);
        writeReg(0x2E, fdlr & 0xFF);
        writeReg(REG_MHDF, mhdf & 0x3F);
        writeReg(REG_NHDF, nhdf & 0x3F);
        writeReg(0x31, nclf & 0xFF);
        writeReg(0x32, fdlf & 0xFF);
        writeReg(0x33, nhdt & 0x3F);
        writeReg(0x34, nclt & 0xFF);
        writeReg(0x35, fdlt & 0xFF);
    }

    /** Set global AFE (sampling) configuration (Stop Mode). */
    public void configureSampling(int cdc, int cdt, int ffi, int sfi, int esi) throws IOException {
        int cdcCfg = ((ffi & 0x03) << 6) | (cdc & 0x3F);
        int cdtCfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07);
        writeReg(REG_CDC_CONFIG, cdcCfg);
        writeReg(REG_CDT_CONFIG, cdtCfg);
    }

    /** Set debounce counts (Stop Mode). */
    public void configureDebounce(int touch, int release) throws IOException {
        int deb = ((release & 0x07) << 4) | (touch & 0x07);
        writeReg(REG_DEBOUNCE, deb);
    }

    /**
     * Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode).
     */
    public void configureAutoconfig(int vddMv, int retry, boolean scts,
                                    boolean are, boolean ace) throws IOException {
        int usl = (int) (((long) (vddMv - 700) * 256) / vddMv);
        int tl  = (int) (usl * 0.9f);
        int lsl = (int) (usl * 0.65f);
        writeReg(REG_USL, usl & 0xFF);
        writeReg(REG_TL,  tl & 0xFF);
        writeReg(REG_LSL, lsl & 0xFF);
        int cdcCfg = readReg(REG_CDC_CONFIG);
        int ffi = (cdcCfg >> 6) & 0x03;
        int autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4)
                         | (are ? 0x08 : 0) | (ace ? 0x01 : 0);
        writeReg(REG_AUTOCONFIG0, autoconfig0);
        int autoconfig1 = scts ? 0x80 : 0x00;
        writeReg(REG_AUTOCONFIG1, autoconfig1);
    }

    /** Return true if the ELEPROX virtual electrode is touched. */
    public boolean proximityTouched() throws IOException {
        return (readReg(REG_ELE8_PROX_TCH) & 0x10) != 0;
    }

    /** Clear the OVCF bit in register 0x01. */
    public void clearOvercurrent() throws IOException {
        int raw = readReg(REG_ELE8_PROX_TCH);
        writeReg(REG_ELE8_PROX_TCH, raw & 0x7F);
    }

    /** Enable one of the AUTOCONFIG1-based interrupt sources. */
    public void enableInterrupt(int source) throws IOException {
        int cur = readReg(REG_AUTOCONFIG1) & 0x00;
        writeReg(REG_AUTOCONFIG1, cur | (source & 0x07));
    }

    /** Disable one of the AUTOCONFIG1-based interrupt sources. */
    public void disableInterrupt(int source) throws IOException {
        int cur = readReg(REG_AUTOCONFIG1);
        writeReg(REG_AUTOCONFIG1, cur & ~(source & 0x07));
    }
}
