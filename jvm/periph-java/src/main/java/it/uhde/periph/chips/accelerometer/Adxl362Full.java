package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * ADXL362 — 3-axis MEMS accelerometer (Analog Devices) — full interface (SPI).
 *
 * <p>Extends {@link Adxl362Minimal} with the chip's full API: device-ID
 * triple read, soft-reset, range/ODR/antialiasing/noise configuration,
 * wake-up mode, external clock and external sample sync, 8-bit low-resolution
 * reads, on-chip temperature, STATUS accessors, 512-sample FIFO configuration
 * and draining, activity/inactivity thresholds and timers (referenced or
 * absolute, default/linked/loop modes), per-pin (INT1/INT2) source mapping and
 * active-high/active-low polarity, and self-test.
 *
 * <p>All interrupt source / noise mode / link-loop mode / FIFO mode / axis
 * constants are public so callers can name the bit positions symbolically.
 */
public class Adxl362Full extends Adxl362Minimal {

    // Interrupt source constants (used by {@link #setInterrupt}).
    public static final int SOURCE_DATA_READY    = 0;
    public static final int SOURCE_FIFO_READY    = 1;
    public static final int SOURCE_FIFO_WATERMARK = 2;
    public static final int SOURCE_FIFO_OVERRUN  = 3;
    public static final int SOURCE_ACT           = 4;
    public static final int SOURCE_INACT         = 5;
    public static final int SOURCE_AWAKE         = 6;

    // Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
    public static final int NOISE_NORMAL   = 0;
    public static final int NOISE_LOW      = 1;
    public static final int NOISE_ULTRALOW = 2;

    // Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
    public static final int LINKLOOP_DEFAULT = 0;
    public static final int LINKLOOP_LINKED  = 1;
    public static final int LINKLOOP_LOOP    = 3;

    // FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
    public static final int FIFO_DISABLED     = 0;
    public static final int FIFO_OLDEST_SAVED = 1;
    public static final int FIFO_STREAM       = 2;
    public static final int FIFO_TRIGGERED    = 3;

    // FIFO entry-axis constants (top 2 bits of each 16-bit FIFO entry).
    public static final int AXIS_X    = 0;
    public static final int AXIS_Y    = 1;
    public static final int AXIS_Z    = 2;
    public static final int AXIS_TEMP = 3;

    protected static final int INTMAP_DATA_READY    = 0x01;
    protected static final int INTMAP_FIFO_READY    = 0x02;
    protected static final int INTMAP_FIFO_WATERMARK = 0x04;
    protected static final int INTMAP_FIFO_OVERRUN  = 0x08;
    protected static final int INTMAP_ACT           = 0x10;
    protected static final int INTMAP_INACT         = 0x20;
    protected static final int INTMAP_AWAKE         = 0x40;
    protected static final int INTMAP_INT_LOW       = 0x80;

    /** Construct the full driver over SPI. */
    public Adxl362Full(Connection connection) throws IOException {
        super(connection);
    }

    /** Return raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID). */
    public int[] deviceId() throws IOException {
        byte[] ids = readBurst(REG_DEVID_AD, 4);
        return new int[] { ids[0] & 0xFF, ids[1] & 0xFF, ids[2] & 0xFF, ids[3] & 0xFF };
    }

    /** Soft-reset the chip (writes 0x52 to SOFT_RESET, waits ≥0.5 ms). */
    public void softReset() throws IOException {
        writeReg(REG_SOFT_RESET, SOFT_RESET_KEY);
        sleepMs(1);
        rangeBits = 0x00;
        odrHz = 100.0f;
    }

    /** Set the measurement range to ±2/±4/±8 g. */
    public void setRange(int rangeG) throws IOException {
        int code;
        switch (rangeG) {
            case 2:  code = 0x00; break;
            case 4:  code = 0x40; break;
            case 8:  code = 0x80; break;
            default: return;
        }
        int f = readReg(REG_FILTER_CTL);
        writeReg(REG_FILTER_CTL, (f & 0x3F) | (code & 0xC0));
        rangeBits = code;
        if (odrHz > 0) sleepMs((int) (1000.0f / odrHz + 1));
    }

    /**
     * Set the output data rate to the nearest supported value (12.5–400 Hz).
     */
    public void setOdr(float odrHz) throws IOException {
        int[] codes = { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 };
        float[] rates = { 12.5f, 25.0f, 50.0f, 100.0f, 200.0f, 400.0f, 400.0f, 400.0f };
        int bestCode = codes[0];
        float bestRate = rates[0];
        float bestDiff = Math.abs(bestRate - odrHz);
        for (int i = 1; i < codes.length; i++) {
            float d = Math.abs(rates[i] - odrHz);
            if (d < bestDiff) { bestCode = codes[i]; bestRate = rates[i]; bestDiff = d; }
        }
        int f = readReg(REG_FILTER_CTL);
        writeReg(REG_FILTER_CTL, (f & 0xF8) | (bestCode & 0x07));
        this.odrHz = bestRate;
    }

    /** Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2). */
    public void setHalfBandwidth(boolean enabled) throws IOException {
        int f = readReg(REG_FILTER_CTL);
        writeReg(REG_FILTER_CTL, enabled ? (f | 0x10) : (f & ~0x10));
    }

    /** Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise). */
    public void setNoiseMode(int mode) throws IOException {
        int p = readReg(REG_POWER_CTL);
        writeReg(REG_POWER_CTL, (p & 0xCF) | ((mode << 4) & 0x30));
    }

    /** Set POWER_CTL.WAKEUP (270 nA idle mode). */
    public void setWakeupMode(boolean enabled) throws IOException {
        int p = readReg(REG_POWER_CTL);
        writeReg(REG_POWER_CTL, enabled ? (p | 0x08) : (p & ~0x08));
    }

    /** Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode. */
    public void setAutosleep(boolean enabled) throws IOException {
        int p = readReg(REG_POWER_CTL);
        writeReg(REG_POWER_CTL, enabled ? (p | 0x04) : (p & ~0x04));
    }

    /** Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input. */
    public void setExternalClock(boolean enabled) throws IOException {
        int p = readReg(REG_POWER_CTL);
        writeReg(REG_POWER_CTL, enabled ? (p | 0x40) : (p & ~0x40));
    }

    /** Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input. */
    public void setExternalSampleTrigger(boolean enabled) throws IOException {
        int f = readReg(REG_FILTER_CTL);
        writeReg(REG_FILTER_CTL, enabled ? (f | 0x08) : (f & ~0x08));
    }

    /**
     * Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers.
     * @return (x, y, z) acceleration in <i>g</i>, ~16-LSB resolution.
     */
    public float[] read8bit() throws IOException {
        byte[] raw = readBurst(REG_XDATA, 3);
        int sx = ((raw[0] & 0x80) != 0) ? ((raw[0] & 0xFF) - 256) : (raw[0] & 0xFF);
        int sy = ((raw[1] & 0x80) != 0) ? ((raw[1] & 0xFF) - 256) : (raw[1] & 0xFF);
        int sz = ((raw[2] & 0x80) != 0) ? ((raw[2] & 0xFF) - 256) : (raw[2] & 0xFF);
        float sens = sensitivity() * 16.0f;
        return new float[] { sx * sens, sy * sens, sz * sens };
    }

    /**
     * Read the on-chip temperature sensor (typical bias/sensitivity).
     * @return temperature in °C.
     */
    public float temperature() throws IOException {
        byte[] raw = readBurst(REG_TEMP_L, 2);
        int raw12 = signExtend12(((raw[1] & 0x0F) << 8) | (raw[0] & 0xFF));
        return 25.0f + (raw12 - 350) * 0.065f;
    }

    /** Read the raw STATUS register byte. */
    public int status() throws IOException {
        return readReg(REG_STATUS);
    }

    /** Return STATUS.AWAKE. */
    public boolean awake() throws IOException {
        return (status() & STATUS_AWAKE) != 0;
    }

    /** Return STATUS.DATA_READY. */
    public boolean dataReady() throws IOException {
        return (status() & STATUS_DATA_READY) != 0;
    }

    /** Return the 10-bit FIFO entry count (0–512). */
    public int fifoEntries() throws IOException {
        return readFifoEntries();
    }

    /**
     * Configure the FIFO mode, optional temperature storage, and watermark.
     */
    public void configureFifo(int mode, boolean storeTemp, int watermark) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode must be 0..3");
        if (watermark < 0 || watermark > 0x1FF) throw new IllegalArgumentException("watermark must be 0..511");
        int fc = (mode & 0x03) | (((watermark >> 8) & 0x01) << 3) | (storeTemp ? 0x04 : 0x00);
        writeReg(REG_FIFO_CONTROL, fc);
        writeReg(REG_FIFO_SAMPLES, watermark & 0xFF);
    }

    /**
     * Read all available FIFO entries.
     * @return pairs of (axis, value) where axis is one of AXIS_* and value
     *         is in <i>g</i> (axes 0–2) or °C (axis 3).
     */
    public float[][] readFifo() throws IOException {
        int n = fifoEntries();
        if (n == 0) return new float[0][0];
        byte[] raw = readFifo(n * 2);
        float sens = sensitivity();
        float[][] out = new float[n][2];
        for (int i = 0; i < n; i++) {
            int lo = raw[2 * i] & 0xFF;
            int hi = raw[2 * i + 1] & 0xFF;
            int raw16 = (hi << 8) | lo;
            int axis = (raw16 >> 14) & 0x03;
            int raw12 = signExtend12(raw16 & 0x0FFF);
            float value = (axis == AXIS_TEMP) ? (25.0f + (raw12 - 350) * 0.065f) : raw12 * sens;
            out[i][0] = axis;
            out[i][1] = value;
        }
        return out;
    }

    /** Set the activity threshold in <i>g</i> (clamped to 10-bit range). */
    public void setActivityThreshold(float thresholdG, boolean referenced) throws IOException {
        int raw = Math.round(thresholdG / sensitivity());
        if (raw < 0) raw = 0;
        if (raw > 0x3FF) raw = 0x3FF;
        writeReg(REG_THRESH_ACT_L, raw & 0xFF);
        writeReg(REG_THRESH_ACT_H, (raw >> 8) & 0x03);
        int aic = readReg(REG_ACT_INACT_CTL);
        writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x02) : (aic & ~0x02));
    }

    /** Set the activity-time filter (0–255 samples). */
    public void setActivityTime(int samples) throws IOException {
        writeReg(REG_TIME_ACT, samples & 0xFF);
    }

    /** Set the inactivity threshold in <i>g</i> (clamped to 10-bit range). */
    public void setInactivityThreshold(float thresholdG, boolean referenced) throws IOException {
        int raw = Math.round(thresholdG / sensitivity());
        if (raw < 0) raw = 0;
        if (raw > 0x3FF) raw = 0x3FF;
        writeReg(REG_THRESH_INACT_L, raw & 0xFF);
        writeReg(REG_THRESH_INACT_H, (raw >> 8) & 0x03);
        int aic = readReg(REG_ACT_INACT_CTL);
        writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x08) : (aic & ~0x08));
    }

    /** Set the inactivity-time filter (0–65535 samples). */
    public void setInactivityTime(int samples) throws IOException {
        writeReg(REG_TIME_INACT_L, samples & 0xFF);
        writeReg(REG_TIME_INACT_H, (samples >> 8) & 0xFF);
    }

    /** Set ACT_INACT_CTL.ACT_EN. */
    public void enableActivityDetection(boolean enabled) throws IOException {
        int aic = readReg(REG_ACT_INACT_CTL);
        writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x01) : (aic & ~0x01));
    }

    /** Set ACT_INACT_CTL.INACT_EN. */
    public void enableInactivityDetection(boolean enabled) throws IOException {
        int aic = readReg(REG_ACT_INACT_CTL);
        writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x04) : (aic & ~0x04));
    }

    /** Set ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop). */
    public void setLinkLoopMode(int mode) throws IOException {
        if (mode != LINKLOOP_DEFAULT && mode != LINKLOOP_LINKED && mode != LINKLOOP_LOOP) {
            throw new IllegalArgumentException("mode must be 0 (default), 1 (linked), or 3 (loop)");
        }
        int aic = readReg(REG_ACT_INACT_CTL);
        writeReg(REG_ACT_INACT_CTL, (aic & 0xCF) | ((mode << 4) & 0x30));
    }

    /** Map one interrupt source to the named INT pin (1 or 2). */
    public void setInterrupt(int pin, int source, boolean enabled) throws IOException {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("pin must be 1 or 2");
        int reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2;
        int cur = readReg(reg);
        int bit = intmapBit(source);
        writeReg(reg, enabled ? (cur | bit) : (cur & ~bit));
    }

    /** Set the active-low polarity for one INT pin. */
    public void setInterruptPolarity(int pin, boolean activeLow) throws IOException {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("pin must be 1 or 2");
        int reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2;
        int cur = readReg(reg);
        writeReg(reg, activeLow ? (cur | INTMAP_INT_LOW) : (cur & ~INTMAP_INT_LOW));
    }

    /** Enable or disable the electrostatic self-test force on all axes. */
    public void selfTest(boolean enabled) throws IOException {
        int st = readReg(REG_SELF_TEST);
        writeReg(REG_SELF_TEST, enabled ? (st | 0x01) : (st & ~0x01));
        if (enabled && odrHz > 0) sleepMs((int) (4000.0f / odrHz + 1));
    }

    private static int intmapBit(int source) {
        switch (source) {
            case SOURCE_DATA_READY:    return INTMAP_DATA_READY;
            case SOURCE_FIFO_READY:    return INTMAP_FIFO_READY;
            case SOURCE_FIFO_WATERMARK: return INTMAP_FIFO_WATERMARK;
            case SOURCE_FIFO_OVERRUN:  return INTMAP_FIFO_OVERRUN;
            case SOURCE_ACT:           return INTMAP_ACT;
            case SOURCE_INACT:         return INTMAP_INACT;
            case SOURCE_AWAKE:         return INTMAP_AWAKE;
            default: throw new IllegalArgumentException("invalid source: " + source);
        }
    }
}