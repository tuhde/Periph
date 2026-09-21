package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * ADXL362 — 3-axis MEMS accelerometer (Analog Devices) — full interface (SPI).
 *
 * Extends {@link Adxl362Minimal} with the chip's full API: device-ID
 * triple read, soft-reset, range/ODR/antialiasing/noise configuration,
 * wake-up mode, external clock and external sample sync, 8-bit low-resolution
 * reads, on-chip temperature, STATUS accessors, 512-sample FIFO configuration
 * and draining, activity/inactivity thresholds and timers (referenced or
 * absolute, default/linked/loop modes), per-pin (INT1/INT2) source mapping and
 * active-high/active-low polarity, and self-test.
 */
@CompileStatic
class Adxl362Full extends Adxl362Minimal {

    static final int SOURCE_DATA_READY    = 0
    static final int SOURCE_FIFO_READY    = 1
    static final int SOURCE_FIFO_WATERMARK = 2
    static final int SOURCE_FIFO_OVERRUN  = 3
    static final int SOURCE_ACT           = 4
    static final int SOURCE_INACT         = 5
    static final int SOURCE_AWAKE         = 6

    static final int NOISE_NORMAL   = 0
    static final int NOISE_LOW      = 1
    static final int NOISE_ULTRALOW = 2

    static final int LINKLOOP_DEFAULT = 0
    static final int LINKLOOP_LINKED  = 1
    static final int LINKLOOP_LOOP    = 3

    static final int FIFO_DISABLED     = 0
    static final int FIFO_OLDEST_SAVED = 1
    static final int FIFO_STREAM       = 2
    static final int FIFO_TRIGGERED    = 3

    static final int AXIS_X    = 0
    static final int AXIS_Y    = 1
    static final int AXIS_Z    = 2
    static final int AXIS_TEMP = 3

    protected static final int INTMAP_DATA_READY    = 0x01
    protected static final int INTMAP_FIFO_READY    = 0x02
    protected static final int INTMAP_FIFO_WATERMARK = 0x04
    protected static final int INTMAP_FIFO_OVERRUN  = 0x08
    protected static final int INTMAP_ACT           = 0x10
    protected static final int INTMAP_INACT         = 0x20
    protected static final int INTMAP_AWAKE         = 0x40
    protected static final int INTMAP_INT_LOW       = 0x80

    Adxl362Full(Connection connection) {
        super(connection)
    }

    int[] deviceId() {
        byte[] ids = readBurst(REG_DEVID_AD, 4)
        return new int[] { ids[0] & 0xFF, ids[1] & 0xFF, ids[2] & 0xFF, ids[3] & 0xFF } as int[]
    }

    void softReset() {
        writeReg(REG_SOFT_RESET, SOFT_RESET_KEY)
        sleepMs(1)
        rangeBits = 0x00
        odrHz = 100.0f
    }

    void setRange(int rangeG) {
        int code
        switch (rangeG) {
            case 2:  code = 0x00; break
            case 4:  code = 0x40; break
            case 8:  code = 0x80; break
            default: return
        }
        int f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, (f & 0x3F) | (code & 0xC0))
        rangeBits = code
        if (odrHz > 0) sleepMs((int) (1000.0f / odrHz + 1))
    }

    void setOdr(float odrHz) {
        int[] codes = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07] as int[]
        float[] rates = [12.5f, 25.0f, 50.0f, 100.0f, 200.0f, 400.0f, 400.0f, 400.0f] as float[]
        int bestCode = codes[0]
        float bestRate = rates[0]
        float bestDiff = Math.abs(bestRate - odrHz)
        for (int i = 1; i < codes.length; i++) {
            float d = Math.abs(rates[i] - odrHz)
            if (d < bestDiff) { bestCode = codes[i]; bestRate = rates[i]; bestDiff = d }
        }
        int f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, (f & 0xF8) | (bestCode & 0x07))
        this.odrHz = bestRate
    }

    void setHalfBandwidth(boolean enabled) {
        int f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, enabled ? (f | 0x10) : (f & ~0x10))
    }

    void setNoiseMode(int mode) {
        int p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, (p & 0xCF) | ((mode << 4) & 0x30))
    }

    void setWakeupMode(boolean enabled) {
        int p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, enabled ? (p | 0x08) : (p & ~0x08))
    }

    void setAutosleep(boolean enabled) {
        int p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, enabled ? (p | 0x04) : (p & ~0x04))
    }

    void setExternalClock(boolean enabled) {
        int p = readReg(REG_POWER_CTL)
        writeReg(REG_POWER_CTL, enabled ? (p | 0x40) : (p & ~0x40))
    }

    void setExternalSampleTrigger(boolean enabled) {
        int f = readReg(REG_FILTER_CTL)
        writeReg(REG_FILTER_CTL, enabled ? (f | 0x08) : (f & ~0x08))
    }

    float[] read8bit() {
        byte[] raw = readBurst(REG_XDATA, 3)
        int sx = ((raw[0] & 0x80) != 0) ? ((raw[0] & 0xFF) - 256) : (raw[0] & 0xFF)
        int sy = ((raw[1] & 0x80) != 0) ? ((raw[1] & 0xFF) - 256) : (raw[1] & 0xFF)
        int sz = ((raw[2] & 0x80) != 0) ? ((raw[2] & 0xFF) - 256) : (raw[2] & 0xFF)
        float sens = sensitivity() * 16.0f
        return new float[] { sx * sens, sy * sens, sz * sens } as float[]
    }

    float temperature() {
        byte[] raw = readBurst(REG_TEMP_L, 2)
        int raw12 = signExtend12(((raw[1] & 0x0F) << 8) | (raw[0] & 0xFF))
        return 25.0f + (raw12 - 350) * 0.065f
    }

    int status() {
        return readReg(REG_STATUS)
    }

    boolean awake() {
        return (status() & STATUS_AWAKE) != 0
    }

    boolean dataReady() {
        return (status() & STATUS_DATA_READY) != 0
    }

    int fifoEntries() {
        return readFifoEntries()
    }

    void configureFifo(int mode, boolean storeTemp, int watermark) {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode must be 0..3")
        if (watermark < 0 || watermark > 0x1FF) throw new IllegalArgumentException("watermark must be 0..511")
        int fc = (mode & 0x03) | (((watermark >> 8) & 0x01) << 3) | (storeTemp ? 0x04 : 0x00)
        writeReg(REG_FIFO_CONTROL, fc)
        writeReg(REG_FIFO_SAMPLES, watermark & 0xFF)
    }

    float[][] readFifo() {
        int n = fifoEntries()
        if (n == 0) return new float[0][0]
        byte[] raw = readFifo(n * 2)
        float sens = sensitivity()
        float[][] out = new float[n][2]
        for (int i = 0; i < n; i++) {
            int lo = raw[2 * i] & 0xFF
            int hi = raw[2 * i + 1] & 0xFF
            int raw16 = (hi << 8) | lo
            int axis = (raw16 >> 14) & 0x03
            int raw12 = signExtend12(raw16 & 0x0FFF)
            out[i][0] = (float) axis
            out[i][1] = (axis == AXIS_TEMP) ? (25.0f + (raw12 - 350) * 0.065f) : raw12 * sens
        }
        return out
    }

    void setActivityThreshold(float thresholdG, boolean referenced = false) {
        int raw = (int) Math.round(thresholdG / sensitivity())
        if (raw < 0) raw = 0
        if (raw > 0x3FF) raw = 0x3FF
        writeReg(REG_THRESH_ACT_L, raw & 0xFF)
        writeReg(REG_THRESH_ACT_H, (raw >> 8) & 0x03)
        int aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x02) : (aic & ~0x02))
    }

    void setActivityTime(int samples) {
        writeReg(REG_TIME_ACT, samples & 0xFF)
    }

    void setInactivityThreshold(float thresholdG, boolean referenced = false) {
        int raw = (int) Math.round(thresholdG / sensitivity())
        if (raw < 0) raw = 0
        if (raw > 0x3FF) raw = 0x3FF
        writeReg(REG_THRESH_INACT_L, raw & 0xFF)
        writeReg(REG_THRESH_INACT_H, (raw >> 8) & 0x03)
        int aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x08) : (aic & ~0x08))
    }

    void setInactivityTime(int samples) {
        writeReg(REG_TIME_INACT_L, samples & 0xFF)
        writeReg(REG_TIME_INACT_H, (samples >> 8) & 0xFF)
    }

    void enableActivityDetection(boolean enabled) {
        int aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x01) : (aic & ~0x01))
    }

    void enableInactivityDetection(boolean enabled) {
        int aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x04) : (aic & ~0x04))
    }

    void setLinkLoopMode(int mode) {
        if (mode != LINKLOOP_DEFAULT && mode != LINKLOOP_LINKED && mode != LINKLOOP_LOOP) {
            throw new IllegalArgumentException("mode must be 0 (default), 1 (linked), or 3 (loop)")
        }
        int aic = readReg(REG_ACT_INACT_CTL)
        writeReg(REG_ACT_INACT_CTL, (aic & 0xCF) | ((mode << 4) & 0x30))
    }

    void setInterrupt(int pin, int source, boolean enabled) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("pin must be 1 or 2")
        int reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2
        int cur = readReg(reg)
        int bit = intmapBit(source)
        writeReg(reg, enabled ? (cur | bit) : (cur & ~bit))
    }

    void setInterruptPolarity(int pin, boolean activeLow) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("pin must be 1 or 2")
        int reg = (pin == 1) ? REG_INTMAP1 : REG_INTMAP2
        int cur = readReg(reg)
        writeReg(reg, activeLow ? (cur | INTMAP_INT_LOW) : (cur & ~INTMAP_INT_LOW))
    }

    void selfTest(boolean enabled) {
        int st = readReg(REG_SELF_TEST)
        writeReg(REG_SELF_TEST, enabled ? (st | 0x01) : (st & ~0x01))
        if (enabled && odrHz > 0) sleepMs((int) (4000.0f / odrHz + 1))
    }

    private static int intmapBit(int source) {
        switch (source) {
            case SOURCE_DATA_READY:    return INTMAP_DATA_READY
            case SOURCE_FIFO_READY:    return INTMAP_FIFO_READY
            case SOURCE_FIFO_WATERMARK: return INTMAP_FIFO_WATERMARK
            case SOURCE_FIFO_OVERRUN:  return INTMAP_FIFO_OVERRUN
            case SOURCE_ACT:           return INTMAP_ACT
            case SOURCE_INACT:         return INTMAP_INACT
            case SOURCE_AWAKE:         return INTMAP_AWAKE
            default: throw new IllegalArgumentException("invalid source: " + source)
        }
    }
}