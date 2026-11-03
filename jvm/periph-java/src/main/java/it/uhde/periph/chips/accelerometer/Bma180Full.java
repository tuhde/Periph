package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * BMA180 — full interface — extends {@link Bma180Minimal} with configuration,
 * range/bandwidth/mode/filter selection, temperature, new-data, shadow,
 * sample-skip, low-g / high-g / slope / alert / tap interrupts with per-axis
 * enable and filter selection, latched or self-resetting interrupts,
 * self-wake-up, sleep, soft reset, electrostatic self-test, offset
 * regulation, version register, and the two CUSTOMER scratch bytes.
 */
public class Bma180Full extends Bma180Minimal {

    /** Interrupt source bits. */
    public static final int SOURCE_LOW_G    = 0x01;
    public static final int SOURCE_HIGH_G   = 0x02;
    public static final int SOURCE_SLOPE    = 0x04;
    public static final int SOURCE_ALERT    = 0x08;
    public static final int SOURCE_TAP      = 0x10;
    public static final int SOURCE_NEW_DATA = 0x20;

    /** STATUS_REG3 latched interrupt flag bits. */
    public static final int STATUS_HIGH_G  = 0x80;
    public static final int STATUS_LOW_G   = 0x40;
    public static final int STATUS_SLOPE   = 0x20;
    public static final int STATUS_TAP     = 0x10;
    public static final int STATUS_X_FIRST = 0x04;
    public static final int STATUS_Y_FIRST = 0x02;
    public static final int STATUS_Z_FIRST = 0x01;

    /** CTRL_REG3 bits (interrupt enables). */
    private static final int CR3_SLOPE_ALERT  = 0x80;
    private static final int CR3_SLOPE_INT    = 0x40;
    private static final int CR3_HIGH_INT     = 0x20;
    private static final int CR3_LOW_INT      = 0x10;
    private static final int CR3_TAP_INT      = 0x08;
    private static final int CR3_ADV_INT      = 0x04;
    private static final int CR3_NEW_DATA_INT = 0x02;
    private static final int CR3_LAT_INT      = 0x01;

    /** HIGH_LOW_INFO bits: high axes 7:5, high_filt 4, low axes 3:1, low_filt 0. */
    private static final int HLI_HIGH_AXIS_SHIFT = 5;
    private static final int HLI_LOW_AXIS_SHIFT  = 1;
    private static final int HLI_HIGH_FILT_BIT   = 0x10;
    private static final int HLI_LOW_FILT_BIT    = 0x01;

    /** SLOPE_TAPSENS_INFO bits: slope axes 7:5, slope_filt 4, tap axes 3:1, tap_filt 0. */
    private static final int STI_SLOPE_AXIS_SHIFT = 5;
    private static final int STI_TAP_AXIS_SHIFT   = 1;
    private static final int STI_SLOPE_FILT_BIT   = 0x10;
    private static final int STI_TAP_FILT_BIT     = 0x01;

    /** HY register bits: high_hy 7:3, low_hy 2:0. */
    private static final int HY_HIGH_SHIFT = 3;
    private static final int HY_LOW_MASK   = 0x07;

    /** CTRL_REG4 bits: low_hy<1:0> 7:6, mot_cd_r 5:4, ff_cd_r 3:2, offset_finetuning 1:0. */
    private static final int CR4_LOW_HY_SHIFT = 6;
    private static final int CR4_MOT_CD_SHIFT = 4;
    private static final int CR4_FF_CD_SHIFT  = 2;

    /** OFFSET_LSB1 bits: offset_x LSBs 7:4, range 3:1, smp_skip 0. */
    private static final int OLSB1_RANGE_MASK = 0x0E;
    private static final int OLSB1_SMP_SKIP   = 0x01;

    /** GAIN_Y bit 0 = shadow_dis; GAIN_Z bit 0 = wake_up. */
    private static final int GY_SHADOW    = 0x01;
    private static final int GZ_WAKE_UP   = 0x01;

    /** BW_TCS bits: bw 7:4, tcs 3:0. */
    private static final int BW_HIGH_PASS_1HZ = 0x80;
    private static final int BW_BAND_PASS     = 0x90;

    /** TCO_X bits: tco_x 7:2, slope_dur 1:0; TCO_Y bits: tco_y 7:2, wake_up_dur 1:0;
     *  TCO_Z bits: tco_z 7:2, mode_config 1:0. */
    private static final int TCO_X_SLOPE_MASK = 0x03;
    private static final int TCO_Y_WAKE_MASK  = 0x03;
    private static final int TCO_Z_MODE_MASK  = 0x03;

    /** GAIN_T bits: gain_t 7:3, tapsens_dur 2:0. */
    private static final int GT_TAP_MASK = 0x07;

    /** LOW_DUR bits: low_dur 7:1, tco_range 0 (preserve!); HIGH_DUR bits: high_dur 7:1, dis_i2c 0. */
    private static final int LOW_DUR_MASK  = 0xFE;
    private static final int HIGH_DUR_MASK = 0xFE;

    /** OFFSET_T bit 0 = readout_12bit. */
    private static final int OT_12BIT_MASK = 0x01;

    /** Duration time base: T_update = 417 µs, *dur = 5 * T_update ≈ 2.085 ms/LSB. */
    private static final float DUR_LSB_MS = 2.085f;

    private int enabledSources = 0;
    private boolean sleeping = false;

    public Bma180Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /** Read 3-axis linear acceleration in *g*. Delegates to {@link Bma180Minimal#read()}. */
    @Override
    public float[] read() throws IOException {
        return super.read();
    }

    /** Set the measurement range to ±1/±1.5/±2/±3/±4/±8/±16 *g*. */
    public void setRange(float rangeG) throws IOException {
        int bits;
        if      (rangeG == 1.0f)   bits = RANGE_1G_MASK;
        else if (rangeG == 1.5f) bits = RANGE_1_5G_MASK;
        else if (rangeG == 2.0f) bits = RANGE_2G_MASK;
        else if (rangeG == 3.0f) bits = RANGE_3G_MASK;
        else if (rangeG == 4.0f) bits = RANGE_4G_MASK;
        else if (rangeG == 8.0f) bits = RANGE_8G_MASK;
        else if (rangeG == 16.0f) bits = RANGE_16G_MASK;
        else { throw new IOException("rangeG must be one of 1, 1.5, 2, 3, 4, 8, 16"); }
        this.rangeG = rangeG;
        this.rangeBits = bits;
        int olsb1 = readReg(REG_OFFSET_LSB1);
        writeReg(REG_OFFSET_LSB1, (olsb1 & 0xF1) | bits);
    }

    /** Set the low-pass bandwidth to the nearest supported value (10..1200 Hz). */
    public void setBandwidth(int bandwidthHz) throws IOException {
        int bwCode = nearestBandwidth(bandwidthHz);
        int cur = readReg(REG_BW_TCS);
        writeReg(REG_BW_TCS, (cur & 0x0F) | bwCode);
    }

    /** Set the filter mode: 0 = low-pass (use setBandwidth), 1 = high-pass 1 Hz, 2 = band-pass 0.2..300 Hz. */
    public void setFilterMode(int mode) throws IOException {
        int code;
        if      (mode == 1) code = BW_HIGH_PASS_1HZ;
        else if (mode == 2) code = BW_BAND_PASS;
        else return;
        int cur = readReg(REG_BW_TCS);
        writeReg(REG_BW_TCS, (cur & 0x0F) | code);
    }

    /** Set the noise/power sub-mode (0..3). Offsets shift; recalibrate after. */
    public void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IOException("mode must be 0..3");
        int tcoz = readReg(REG_TCO_Z);
        writeReg(REG_TCO_Z, (tcoz & 0xFC) | (mode & 0x03));
    }

    /** Set the data resolution: 12 or 14 bits. */
    public void setResolution(int bits) throws IOException {
        if (bits != 12 && bits != 14) throw new IOException("bits must be 12 or 14");
        int ot = readReg(REG_OFFSET_T);
        if (bits == 12) ot |= OT_12BIT_MASK;
        else            ot &= ~OT_12BIT_MASK & 0xFF;
        writeReg(REG_OFFSET_T, ot);
    }

    /** Read raw 14-bit two's-complement acceleration counts. */
    public int[] readRaw() throws IOException {
        byte[] raw = connection.read(REG_ACC_X_LSB, 6);
        int rx = Register.toSigned(((raw[1] & 0xFF) << 6) | ((raw[0] & 0xFF) >> 2), 14);
        int ry = Register.toSigned(((raw[3] & 0xFF) << 6) | ((raw[2] & 0xFF) >> 2), 14);
        int rz = Register.toSigned(((raw[5] & 0xFF) << 6) | ((raw[4] & 0xFF) >> 2), 14);
        return new int[] { rx, ry, rz };
    }

    /** Read on-chip temperature in °C (computed as 25.0 + (signed - 2) * 0.5). */
    public float readTemperature() throws IOException {
        int raw = readReg(REG_TEMP);
        int signed = (raw < 128) ? raw : raw - 256;
        return 25.0f + (signed - 2) * 0.5f;
    }

    /** Return true if all three new_data_X/Y/Z bits are set. */
    public boolean newDataAvailable() throws IOException {
        int x = readReg(REG_ACC_X_LSB);
        int y = readReg(REG_ACC_Y_LSB);
        int z = readReg(REG_ACC_Z_LSB);
        return (x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0;
    }

    /** Enable or disable MSB-only reads (shadow_dis = not enabled). */
    public void setShadow(boolean enabled) throws IOException {
        int gy = readReg(REG_GAIN_Y);
        if (enabled) gy &= ~GY_SHADOW & 0xFF;
        else         gy |= GY_SHADOW;
        writeReg(REG_GAIN_Y, gy);
    }

    /** Toggle the sample-skip bit (only useful with the new-data interrupt). */
    public void setSampleSkip(boolean enabled) throws IOException {
        int olsb1 = readReg(REG_OFFSET_LSB1);
        if (enabled) olsb1 |= OLSB1_SMP_SKIP;
        else         olsb1 &= ~OLSB1_SMP_SKIP & 0xFF;
        writeReg(REG_OFFSET_LSB1, olsb1);
    }

    /** Configure the low-g (free-fall) interrupt and enable it. */
    public void setLowG(float thresholdG, int durationMs, float hysteresisG,
                        int axes, int counter, boolean filtered) throws IOException {
        writeThreshold(REG_LOW_TH, thresholdG);
        writeLowDur(durationMs);
        writeLowHy(hysteresisG);
        writeLowAxes(axes);
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_LOW_FILT_BIT, filtered);
        writeDebounce('l', counter);
        enableSource(SOURCE_LOW_G);
    }

    /** Configure the high-g (shock) interrupt and enable it. */
    public void setHighG(float thresholdG, int durationMs, float hysteresisG,
                         int axes, int counter, boolean filtered) throws IOException {
        writeThreshold(REG_HIGH_TH, thresholdG);
        writeHighDur(durationMs);
        writeHighHy(hysteresisG);
        writeHighAxes(axes);
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_HIGH_FILT_BIT, filtered);
        writeDebounce('h', counter);
        enableSource(SOURCE_HIGH_G);
    }

    /** Configure the slope (any-motion) interrupt and enable it (exclusive with alert). */
    public void setSlope(float thresholdG, int samples, int axes, boolean filtered) throws IOException {
        writeSlopeThreshold(REG_SLOPE_TH, thresholdG);
        writeSlopeDur(samples);
        writeSlopeAxes(axes);
        writeFiltBit(REG_SLOPE_TAPSENS, STI_SLOPE_FILT_BIT, filtered);
        writeCR3Bit(CR3_SLOPE_INT, true);
        writeCR3Bit(CR3_SLOPE_ALERT, false);
        writeCR3Bit(CR3_ADV_INT, true);
        enableSource(SOURCE_SLOPE);
    }

    /** Enable alert mode (mutually exclusive with slope). */
    public void setAlert(boolean enabled) throws IOException {
        if (enabled) {
            enabledSources &= ~SOURCE_SLOPE;
            writeCR3Bit(CR3_SLOPE_INT, false);
            writeCR3Bit(CR3_SLOPE_ALERT, true);
            writeCR3Bit(CR3_ADV_INT, true);
            enableSource(SOURCE_ALERT);
        } else {
            disableSource(SOURCE_ALERT);
            writeCR3Bit(CR3_SLOPE_ALERT, false);
        }
    }

    /** Configure the double-tap interrupt and enable it. */
    public void setTap(float thresholdG, int windowMs, int axes, boolean filtered) throws IOException {
        writeSlopeThreshold(REG_TAPSENS_TH, thresholdG);
        writeTapDur(windowMs);
        writeTapAxes(axes);
        writeFiltBit(REG_SLOPE_TAPSENS, STI_TAP_FILT_BIT, filtered);
        enableSource(SOURCE_TAP);
    }

    /** Enable latched interrupts (cleared by {@link #clearInterrupt()}). */
    public void setLatch(boolean enabled) throws IOException {
        writeCR3Bit(CR3_LAT_INT, enabled);
    }

    /** Clear latched interrupts (writes reset_INT to CTRL_REG0). */
    public void clearInterrupt() throws IOException {
        if (sleeping) return;
        int ctrl0 = readReg(REG_CTRL_REG0);
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_RESET_INT);
    }

    /** Enable one interrupt source (see {@code SOURCE_*}). */
    public void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_NEW_DATA) {
            writeCR3Bit(CR3_NEW_DATA_INT, true);
        } else {
            writeCR3Bit(CR3_NEW_DATA_INT, false);
            if      (source == SOURCE_SLOPE) {
                writeCR3Bit(CR3_SLOPE_ALERT, false);
                writeCR3Bit(CR3_SLOPE_INT, true);
                writeCR3Bit(CR3_ADV_INT, true);
                disableSource(SOURCE_ALERT);
            } else if (source == SOURCE_ALERT) {
                writeCR3Bit(CR3_SLOPE_INT, false);
                writeCR3Bit(CR3_SLOPE_ALERT, true);
                writeCR3Bit(CR3_ADV_INT, true);
                disableSource(SOURCE_SLOPE);
            } else if (source == SOURCE_HIGH_G) {
                writeCR3Bit(CR3_HIGH_INT, true);
            } else if (source == SOURCE_LOW_G) {
                writeCR3Bit(CR3_LOW_INT, true);
            } else if (source == SOURCE_TAP) {
                writeCR3Bit(CR3_TAP_INT, true);
            }
        }
        enableSource(source);
    }

    /** Disable one interrupt source. */
    public void disableInterrupt(int source) throws IOException {
        switch (source) {
            case SOURCE_NEW_DATA: writeCR3Bit(CR3_NEW_DATA_INT, false); break;
            case SOURCE_SLOPE:    writeCR3Bit(CR3_SLOPE_INT, false); break;
            case SOURCE_ALERT:
                writeCR3Bit(CR3_SLOPE_ALERT, false);
                writeCR3Bit(CR3_ADV_INT, false);
                break;
            case SOURCE_HIGH_G: writeCR3Bit(CR3_HIGH_INT, false); break;
            case SOURCE_LOW_G:  writeCR3Bit(CR3_LOW_INT, false); break;
            case SOURCE_TAP:    writeCR3Bit(CR3_TAP_INT, false); break;
            default: break;
        }
        disableSource(source);
    }

    /** Read STATUS_REG3 (latched flags) without clearing. */
    public int pollInterrupt() throws IOException {
        return readReg(REG_STATUS_REG3);
    }

    /** Read all four status registers (1..4). */
    public int[] readStatus() throws IOException {
        return new int[] {
            readReg(REG_STATUS_REG1),
            readReg(REG_STATUS_REG2),
            readReg(REG_STATUS_REG3),
            readReg(REG_STATUS_REG4),
        };
    }

    /** Configure self-wake-up mode. */
    public void setWakeUp(boolean enabled, int pauseMs) throws IOException {
        int code;
        switch (pauseMs) {
            case 80:    code = 0x01; break;
            case 320:   code = 0x02; break;
            case 2560:  code = 0x03; break;
            default:    code = 0x00; break;
        }
        int tcoy = readReg(REG_TCO_Y);
        writeReg(REG_TCO_Y, (tcoy & 0xFC) | code);
        int gz = readReg(REG_GAIN_Z);
        if (enabled) gz |= GZ_WAKE_UP;
        else         gz &= ~GZ_WAKE_UP & 0xFF;
        writeReg(REG_GAIN_Z, gz);
    }

    /** Enter sleep mode. */
    public void sleep() throws IOException {
        if (sleeping) return;
        int ctrl0 = readReg(REG_CTRL_REG0);
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_SLEEP);
        sleeping = true;
    }

    /** Leave sleep mode. Waits 2 ms for the analog to settle. */
    public void wake() throws IOException, InterruptedException {
        if (!sleeping) return;
        int ctrl0 = readReg(REG_CTRL_REG0);
        writeReg(REG_CTRL_REG0, ctrl0 & ~CTRL_REG0_SLEEP & 0xFF);
        Thread.sleep(2);
        sleeping = false;
    }

    /** Issue a power-on-equivalent reset; restores defaults. */
    public void softReset() throws IOException, InterruptedException {
        writeReg(REG_RESET, SOFT_RESET_CMD);
        Thread.sleep(30);
        this.rangeG = 2.0f;
        this.rangeBits = RANGE_2G_MASK;
        int id = readReg(REG_CHIP_ID);
        if ((id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw new IOException(String.format(
                "BMA180 CHIP_ID after reset: expected 0x%02X, got 0x%02X",
                CHIP_ID_VALUE, id & CHIP_ID_MASK));
        }
        int ctrl0 = readReg(REG_CTRL_REG0);
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_EE_W);
        int olsb1 = readReg(REG_OFFSET_LSB1);
        writeReg(REG_OFFSET_LSB1, (olsb1 & 0xF1) | RANGE_2G_MASK);
        int bw = readReg(REG_BW_TCS);
        writeReg(REG_BW_TCS, (bw & 0x0F) | 0x40);
        sleeping = false;
    }

    /** Run the electrostatic self-test. Returns true if every axis responds > 200 LSB. */
    public boolean selfTest() throws IOException, InterruptedException {
        int ctrl0 = readReg(REG_CTRL_REG0);
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_ST0);
        Thread.sleep(10);
        int[] r = readRaw();
        writeReg(REG_CTRL_REG0, ctrl0);
        boolean ok = Math.abs(r[0]) > 200 && Math.abs(r[1]) > 200 && Math.abs(r[2]) > 200;
        softReset();
        return ok;
    }

    /** Run the in-field zero-g offset calibration (volatile). */
    public void calibrateOffset(int axes, int mode) throws IOException, InterruptedException {
        if (mode < 0 || mode > 3) throw new IOException("mode must be 0..3");
        int cr4 = readReg(REG_CTRL_REG4);
        writeReg(REG_CTRL_REG4, (cr4 & 0xFC) | (mode & 0x03));
        int[][] ax = { {0x01, 0x80}, {0x02, 0x40}, {0x04, 0x20} };
        for (int[] a : ax) {
            if ((axes & a[0]) == 0) continue;
            int ctrl1 = readReg(REG_CTRL_REG1);
            writeReg(REG_CTRL_REG1, ctrl1 | a[1]);
            for (int t = 0; t < 100; t++) {
                int s1 = readReg(REG_STATUS_REG1);
                if ((s1 & 0x02) != 0) break;
                Thread.sleep(100);
            }
            ctrl1 = readReg(REG_CTRL_REG1);
            writeReg(REG_CTRL_REG1, ctrl1 & ~a[1] & 0xFF);
        }
        cr4 = readReg(REG_CTRL_REG4);
        writeReg(REG_CTRL_REG4, cr4 & 0xFC);
    }

    /** Read VERSION split into (al_version, ml_version). */
    public int[] readVersion() throws IOException {
        int raw = readReg(REG_VERSION);
        return new int[] { (raw >> 4) & 0x0F, raw & 0x0F };
    }

    /** Read one of the two CUSTOMER scratch bytes. */
    public int readCustomer(int index) throws IOException {
        return readReg(index == 0 ? REG_CD1 : REG_CD2);
    }

    /** Write one of the two CUSTOMER scratch bytes. */
    public void writeCustomer(int index, int value) throws IOException {
        writeReg(index == 0 ? REG_CD1 : REG_CD2, value & 0xFF);
    }

    // ---- internal helpers ------------------------------------------------

    private void writeThreshold(int reg, float thresholdG) throws IOException {
        int code = Math.round(thresholdG / rangeG * 255.0f);
        if (code < 0)   code = 0;
        if (code > 255) code = 255;
        writeReg(reg, code);
    }

    private void writeSlopeThreshold(int reg, float thresholdG) throws IOException {
        int code = Math.round(thresholdG / (0.0156f * rangeG / 2.0f));
        if (code < 0)   code = 0;
        if (code > 255) code = 255;
        writeReg(reg, code);
    }

    private void writeLowDur(int durationMs) throws IOException {
        int code = Math.round(durationMs / DUR_LSB_MS);
        if (code < 0)   code = 0;
        if (code > 127) code = 127;
        int ld = readReg(REG_LOW_DUR);
        writeReg(REG_LOW_DUR, (ld & 0x01) | ((code & 0x7F) << 1));
    }

    private void writeHighDur(int durationMs) throws IOException {
        int code = Math.round(durationMs / DUR_LSB_MS);
        if (code < 0)   code = 0;
        if (code > 127) code = 127;
        int hd = readReg(REG_HIGH_DUR);
        writeReg(REG_HIGH_DUR, (hd & 0x01) | ((code & 0x7F) << 1));
    }

    private void writeLowHy(float hysteresisG) throws IOException {
        int code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f);
        if (code < 0)  code = 0;
        if (code > 31) code = 31;
        int hy = readReg(REG_HY);
        writeReg(REG_HY, (hy & 0xF8) | (code & HY_LOW_MASK));
        int cr4 = readReg(REG_CTRL_REG4);
        writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_LOW_HY_SHIFT)) | (((code >> 3) & 0x03) << CR4_LOW_HY_SHIFT));
    }

    private void writeHighHy(float hysteresisG) throws IOException {
        int code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f);
        if (code < 0)  code = 0;
        if (code > 31) code = 31;
        int hy = readReg(REG_HY);
        writeReg(REG_HY, (hy & 0x07) | ((code & 0x1F) << HY_HIGH_SHIFT));
    }

    private void writeLowAxes(int axes) throws IOException {
        int hli = readReg(REG_HIGH_LOW_INFO);
        writeReg(REG_HIGH_LOW_INFO, (hli & 0xF1) | ((axes & 0x07) << HLI_LOW_AXIS_SHIFT));
    }

    private void writeHighAxes(int axes) throws IOException {
        int hli = readReg(REG_HIGH_LOW_INFO);
        writeReg(REG_HIGH_LOW_INFO, (hli & 0x0F) | ((axes & 0x07) << HLI_HIGH_AXIS_SHIFT));
    }

    private void writeSlopeAxes(int axes) throws IOException {
        int st = readReg(REG_SLOPE_TAPSENS);
        writeReg(REG_SLOPE_TAPSENS, (st & 0x0F) | ((axes & 0x07) << STI_SLOPE_AXIS_SHIFT));
    }

    private void writeTapAxes(int axes) throws IOException {
        int st = readReg(REG_SLOPE_TAPSENS);
        writeReg(REG_SLOPE_TAPSENS, (st & 0xF1) | ((axes & 0x07) << STI_TAP_AXIS_SHIFT));
    }

    private void writeFiltBit(int reg, int bit, boolean enabled) throws IOException {
        int v = readReg(reg);
        if (enabled) v |= bit;
        else         v &= ~bit & 0xFF;
        writeReg(reg, v);
    }

    private void writeDebounce(char kind, int counter) throws IOException {
        if (counter > 3) throw new IOException("counter must be 0..3");
        int code = (counter & 0x03) << 2;
        int cr4 = readReg(REG_CTRL_REG4);
        if (kind == 'l') {
            writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_FF_CD_SHIFT)) | (code & (0x03 << CR4_FF_CD_SHIFT)));
        } else {
            writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_MOT_CD_SHIFT)) | ((code << 2) & (0x03 << CR4_MOT_CD_SHIFT)));
        }
    }

    private void writeSlopeDur(int samples) throws IOException {
        int code;
        switch (samples) {
            case 3:  code = 0x01; break;
            case 5:  code = 0x02; break;
            case 7:  code = 0x03; break;
            default: code = 0x00; break;
        }
        int tcox = readReg(REG_TCO_X);
        writeReg(REG_TCO_X, (tcox & 0xFC) | code);
    }

    private void writeTapDur(int windowMs) throws IOException {
        int code = nearestTapDur(windowMs);
        int gt = readReg(REG_GAIN_T);
        writeReg(REG_GAIN_T, (gt & 0xF8) | code);
    }

    private void writeCR3Bit(int bit, boolean enabled) throws IOException {
        if (sleeping) return;
        int cr3 = readReg(REG_CTRL_REG3);
        if (enabled) cr3 |= bit;
        else         cr3 &= ~bit & 0xFF;
        writeReg(REG_CTRL_REG3, cr3);
    }

    private void enableSource(int source) {
        if (sleeping) return;
        enabledSources |= source;
    }

    private void disableSource(int source) {
        enabledSources &= ~source & 0xFF;
    }

    private static int nearestBandwidth(int bandwidthHz) {
        int[] hz = { 10, 20, 40, 75, 150, 300, 600, 1200 };
        int[] codes = { 0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70 };
        int best = codes[3];
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < hz.length; i++) {
            int d = Math.abs(hz[i] - bandwidthHz);
            if (d < bestDiff) {
                bestDiff = d;
                best = codes[i];
            }
        }
        return best;
    }

    private static int nearestTapDur(int windowMs) {
        int[] ms = { 50, 75, 100, 150, 250, 500, 750, 1000 };
        int[] codes = { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 };
        int best = codes[4];
        int bestDiff = Integer.MAX_VALUE;
        // Snap to the smallest table entry >= windowMs; fall back to nearest below.
        for (int i = 0; i < ms.length; i++) {
            if (ms[i] >= windowMs) {
                int d = ms[i] - windowMs;
                if (d < bestDiff) {
                    bestDiff = d;
                    best = codes[i];
                }
            }
        }
        if (bestDiff == Integer.MAX_VALUE) {
            bestDiff = Integer.MAX_VALUE;
            for (int i = ms.length - 1; i >= 0; i--) {
                int d = windowMs - ms[i];
                if (d < bestDiff) {
                    bestDiff = d;
                    best = codes[i];
                }
            }
        }
        return best;
    }
}