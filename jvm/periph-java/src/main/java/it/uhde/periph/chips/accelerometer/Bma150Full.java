package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * BMA150 full interface — extends {@link Bma150Minimal} with configuration,
 * interrupt sources, low-g / high-g / any-motion / alert logic, sleep, soft
 * reset, and self-test.
 *
 * <p>Adds range (±2/±4/±8 *g*) and bandwidth (25..1500 Hz) selection, raw
 * reading, temperature, low-g (free-fall), high-g (shock), any-motion and
 * alert thresholds with duration, hysteresis and debounce counters, latched
 * or self-resetting interrupts, self-wake-up mode, sleep and soft reset,
 * electrostatic self-test, version register, and the two CUSTOMER scratch
 * bytes.
 */
public class Bma150Full extends Bma150Minimal {

    // Interrupt source bits.
    public static final int SOURCE_LOW_G      = 0x01;
    public static final int SOURCE_HIGH_G     = 0x02;
    public static final int SOURCE_ANY_MOTION = 0x04;
    public static final int SOURCE_ALERT      = 0x08;
    public static final int SOURCE_NEW_DATA   = 0x10;

    // STATUS register bits.
    public static final int STATUS_ST_RESULT    = 0x80;
    public static final int STATUS_ALERT_PHASE  = 0x10;
    public static final int STATUS_LG_LATCHED   = 0x08;
    public static final int STATUS_HG_LATCHED   = 0x04;
    public static final int STATUS_LG           = 0x02;
    public static final int STATUS_HG           = 0x01;

    private int enabledSources = 0;
    private boolean sleeping = false;

    /**
     * @param connection configured I²C, SMBus, or SPI register connection
     * @throws IOException on bus error or wrong CHIP_ID
     */
    public Bma150Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /** Set the measurement range to ±2/±4/±8 *g*. */
    public void setRange(int rangeG) throws IOException {
        int rangeMask;
        switch (rangeG) {
            case 4:  rangeMask = RANGE_4G_MASK; this.rangeG = 4; break;
            case 8:  rangeMask = RANGE_8G_MASK; this.rangeG = 8; break;
            default: rangeMask = RANGE_2G_MASK; this.rangeG = 2; break;
        }
        int rb = readReg(REG_RANGE_BW);
        int out = (rb & 0xE0) | rangeMask | (rb & 0x07);
        writeReg(REG_RANGE_BW, out);
    }

    /** Set the digital low-pass bandwidth to the nearest supported value (25..1500 Hz). */
    public void setBandwidth(int bandwidthHz) throws IOException {
        int bwCode = nearestBandwidth(bandwidthHz);
        int rb = readReg(REG_RANGE_BW);
        int out = (rb & 0xF8) | bwCode;
        writeReg(REG_RANGE_BW, out);
    }

    /** Read raw 10-bit two's-complement acceleration counts. */
    public int[] readRaw() throws IOException {
        byte[] raw = readBurst(REG_ACC_X_LSB, 6);
        int rx = Register.toSigned(((raw[1] & 0xFF) << 2) | ((raw[0] & 0xC0) >> 6), 10);
        int ry = Register.toSigned(((raw[3] & 0xFF) << 2) | ((raw[2] & 0xC0) >> 6), 10);
        int rz = Register.toSigned(((raw[5] & 0xFF) << 2) | ((raw[4] & 0xC0) >> 6), 10);
        return new int[]{rx, ry, rz};
    }

    /** Read on-chip temperature in °C. */
    public double readTemperature() throws IOException {
        int raw = readReg(REG_TEMP);
        return raw * 0.5 - 30.0;
    }

    /** Return true if all three new_data_X/Y/Z bits are set. */
    public boolean newDataAvailable() throws IOException {
        int x = readReg(REG_ACC_X_LSB);
        int y = readReg(REG_ACC_Y_LSB);
        int z = readReg(REG_ACC_Z_LSB);
        return (x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0;
    }

    /** Enable or disable MSB-only reads. */
    public void setShadow(boolean enabled) throws IOException {
        int cfg = readReg(REG_CONFIG);
        int out = enabled ? (cfg | 0x08) : (cfg & ~0x08);
        writeReg(REG_CONFIG, out);
    }

    /** Configure the low-g (free-fall) interrupt and enable it. */
    public void setLowG(double thresholdG, int durationMs, double hysteresisG, int counter) throws IOException {
        writeThreshold(REG_LG_THRES, thresholdG);
        writeReg(REG_LG_DUR, Math.min(255, Math.max(0, durationMs)));
        writeHyst("lg", hysteresisG);
        writeIntCounter("lg", counter);
        enableSource(SOURCE_LOW_G);
    }

    /** Configure the high-g (shock) interrupt and enable it. */
    public void setHighG(double thresholdG, int durationMs, double hysteresisG, int counter) throws IOException {
        writeThreshold(REG_HG_THRES, thresholdG);
        writeReg(REG_HG_DUR, Math.min(255, Math.max(0, durationMs)));
        writeHyst("hg", hysteresisG);
        writeIntCounter("hg", counter);
        enableSource(SOURCE_HIGH_G);
    }

    /** Configure the any-motion interrupt and enable it. */
    public void setAnyMotion(double thresholdG, int samples) throws IOException {
        double scale;
        switch (rangeG) {
            case 4:  scale = FULL_SCALE_4G / 256.0; break;
            case 8:  scale = FULL_SCALE_8G / 256.0; break;
            default: scale = FULL_SCALE_2G / 256.0; break;
        }
        int code = (int) Math.round(thresholdG / (0.0156 * scale));
        code = Math.min(255, Math.max(0, code));
        writeReg(REG_ANY_MOTION_THRES, code);
        int durCode;
        switch (samples) {
            case 3:  durCode = 0x40; break;
            case 5:  durCode = 0x80; break;
            case 7:  durCode = 0xC0; break;
            default: durCode = 0x00; break;
        }
        int hd = readReg(REG_HYST_DUR);
        writeReg(REG_HYST_DUR, (hd & 0x3F) | durCode);
        int cfg = readReg(REG_CONFIG);
        writeReg(REG_CONFIG, cfg | 0x40);
        enableSource(SOURCE_ANY_MOTION);
    }

    /** Toggle alert mode (mutually exclusive with any-motion). */
    public void setAlert(boolean enabled) throws IOException {
        if (enabled) {
            enabledSources &= ~SOURCE_ANY_MOTION;
            int cfg = readReg(REG_CONFIG);
            writeReg(REG_CONFIG, cfg | 0x40);
            enableSource(SOURCE_ALERT);
        } else {
            disableSource(SOURCE_ALERT);
        }
    }

    /** Enable latched interrupts. */
    public void setLatch(boolean enabled) throws IOException {
        int cfg = readReg(REG_CONFIG);
        int out = enabled ? (cfg | 0x10) : (cfg & ~0x10);
        writeReg(REG_CONFIG, out);
    }

    /** Clear latched interrupts (writes reset_INT). */
    public void clearInterrupt() throws IOException {
        if (sleeping) return;
        int ctrl = readReg(REG_CTRL);
        writeReg(REG_CTRL, ctrl | 0x40);
    }

    /** Enable one interrupt source. */
    public void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_NEW_DATA) {
            enabledSources &= 0x0F;
        } else {
            enabledSources &= ~SOURCE_NEW_DATA;
            if (source == SOURCE_ANY_MOTION) {
                enabledSources &= ~SOURCE_ALERT;
            } else if (source == SOURCE_ALERT) {
                enabledSources &= ~SOURCE_ANY_MOTION;
            }
        }
        enableSource(source);
    }

    /** Disable one interrupt source. */
    public void disableInterrupt(int source) throws IOException {
        disableSource(source);
    }

    /** Read STATUS without clearing latched bits. */
    public int pollInterrupt() throws IOException {
        return readReg(REG_STATUS);
    }

    /** Configure self-wake-up mode. */
    public void setWakeUp(boolean enabled, int pauseMs) throws IOException {
        int pauseCode;
        switch (pauseMs) {
            case 80:   pauseCode = 0x02; break;
            case 320:  pauseCode = 0x04; break;
            case 2560: pauseCode = 0x06; break;
            default:   pauseCode = 0x00; break;
        }
        int cfg = readReg(REG_CONFIG);
        int out = (cfg & 0xF9) | pauseCode | (enabled ? 0x01 : 0x00);
        writeReg(REG_CONFIG, out);
    }

    /** Enter sleep mode. */
    public void sleep() throws IOException {
        if (sleeping) return;
        int ctrl = readReg(REG_CTRL);
        writeReg(REG_CTRL, ctrl | 0x01);
        sleeping = true;
    }

    /** Leave sleep mode. */
    public void wake() throws IOException {
        if (!sleeping) return;
        int ctrl = readReg(REG_CTRL);
        writeReg(REG_CTRL, ctrl & ~0x01);
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        sleeping = false;
    }

    /** Issue a power-on-equivalent reset; range/bandwidth restored. */
    public void softReset() throws IOException {
        int ctrl = readReg(REG_CTRL);
        writeReg(REG_CTRL, ctrl | 0x02);
        try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int rangeMask;
        switch (rangeG) {
            case 4:  rangeMask = RANGE_4G_MASK; break;
            case 8:  rangeMask = RANGE_8G_MASK; break;
            default: rangeMask = RANGE_2G_MASK; break;
        }
        int rb = readReg(REG_RANGE_BW);
        writeReg(REG_RANGE_BW, (rb & 0xE0) | rangeMask | BW_100);
        sleeping = false;
    }

    /** Run the electrostatic self-test, return true on pass. */
    public boolean selfTest() throws IOException {
        int ctrl = readReg(REG_CTRL);
        writeReg(REG_CTRL, ctrl | 0x04);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int status = readReg(REG_STATUS);
        writeReg(REG_CTRL, ctrl);
        return (status & STATUS_ST_RESULT) != 0;
    }

    /** Read the STATUS register. */
    public int readStatus() throws IOException {
        return readReg(REG_STATUS);
    }

    /** Read VERSION split into (al_version, ml_version). */
    public int[] readVersion() throws IOException {
        int raw = readReg(REG_VERSION);
        return new int[]{(raw >> 4) & 0x0F, raw & 0x0F};
    }

    /** Read one of the two CUSTOMER scratch bytes. */
    public int readCustomer(int index) throws IOException {
        return readReg(index == 0 ? REG_CUSTOMER_1 : REG_CUSTOMER_2);
    }

    /** Write one of the two CUSTOMER scratch bytes. */
    public void writeCustomer(int index, int value) throws IOException {
        writeReg(index == 0 ? REG_CUSTOMER_1 : REG_CUSTOMER_2, value & 0xFF);
    }

    private void writeThreshold(int reg, double thresholdG) throws IOException {
        int code = (int) Math.round(thresholdG * 255.0 / rangeG);
        code = Math.min(255, Math.max(0, code));
        writeReg(reg, code);
    }

    private void writeHyst(String kind, double hysteresisG) throws IOException {
        if (hysteresisG < 0) return;
        int code = (int) Math.round(hysteresisG * 255.0 / rangeG / 32.0);
        code = Math.min(7, Math.max(0, code));
        int hd = readReg(REG_HYST_DUR);
        int out = kind.equals("lg") ? (hd & 0xF8) | code : (hd & 0xC7) | (code << 3);
        writeReg(REG_HYST_DUR, out);
    }

    private void writeIntCounter(String kind, int counter) throws IOException {
        if (counter < 0 || counter > 3) return;
        int code = (counter & 0x03) << 4;
        int ic = readReg(REG_INT_CTRL);
        int out = kind.equals("lg") ? (ic & 0xF3) | code : (ic & 0xCF) | (code << 2);
        writeReg(REG_INT_CTRL, out);
    }

    private void enableSource(int source) throws IOException {
        if (sleeping) return;
        enabledSources |= source;
        if (source == SOURCE_NEW_DATA) {
            int cfg = readReg(REG_CONFIG);
            writeReg(REG_CONFIG, cfg | 0x20);
            return;
        }
        int ic = readReg(REG_INT_CTRL);
        int out = ic;
        if (source == SOURCE_LOW_G)      out |= 0x01;
        if (source == SOURCE_HIGH_G)     out |= 0x02;
        if (source == SOURCE_ANY_MOTION) out |= 0x40;
        if (source == SOURCE_ALERT)     out |= 0x80;
        writeReg(REG_INT_CTRL, out);
    }

    private void disableSource(int source) throws IOException {
        enabledSources &= ~source;
        if (source == SOURCE_NEW_DATA) {
            int cfg = readReg(REG_CONFIG);
            writeReg(REG_CONFIG, cfg & ~0x20);
            return;
        }
        int ic = readReg(REG_INT_CTRL);
        int out = ic;
        if (source == SOURCE_LOW_G)      out &= ~0x01;
        if (source == SOURCE_HIGH_G)     out &= ~0x02;
        if (source == SOURCE_ANY_MOTION) out &= ~0x40;
        if (source == SOURCE_ALERT)     out &= ~0x80;
        writeReg(REG_INT_CTRL, out);
    }

    private static int nearestBandwidth(int bwHz) {
        int[] hz = {25, 50, 100, 190, 375, 750, 1500};
        int[] bw = {BW_25, BW_50, BW_100, BW_190, BW_375, BW_750, BW_1500};
        int bestIdx = 2;
        int bestDiff = Math.abs(hz[2] - bwHz);
        for (int i = 0; i < hz.length; i++) {
            int diff = Math.abs(hz[i] - bwHz);
            if (diff < bestDiff) {
                bestIdx = i;
                bestDiff = diff;
            }
        }
        return bw[bestIdx];
    }
}
