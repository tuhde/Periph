package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * BMA150 full interface — extends Bma150Minimal with configuration, interrupt
 * sources, low-g / high-g / any-motion / alert logic, sleep, soft reset, and
 * self-test.
 */
@CompileStatic
class Bma150Full extends Bma150Minimal {

    // Interrupt source bits.
    static final int SOURCE_LOW_G      = 0x01
    static final int SOURCE_HIGH_G     = 0x02
    static final int SOURCE_ANY_MOTION = 0x04
    static final int SOURCE_ALERT      = 0x08
    static final int SOURCE_NEW_DATA   = 0x10

    // STATUS register bits.
    static final int STATUS_ST_RESULT    = 0x80
    static final int STATUS_ALERT_PHASE  = 0x10
    static final int STATUS_LG_LATCHED   = 0x08
    static final int STATUS_HG_LATCHED   = 0x04
    static final int STATUS_LG           = 0x02
    static final int STATUS_HG           = 0x01

    private int enabledSources = 0
    private boolean sleeping = false

    Bma150Full(RegisterConnection connection) {
        super(connection)
    }

    void setRange(int rangeG) {
        int rangeMask
        int newRange
        switch (rangeG) {
            case 4:  rangeMask = RANGE_4G_MASK; newRange = 4; break
            case 8:  rangeMask = RANGE_8G_MASK; newRange = 8; break
            default: rangeMask = RANGE_2G_MASK; newRange = 2; break
        }
        this.rangeG = newRange
        int rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb & 0xE0) | rangeMask | (rb & 0x07))
    }

    void setBandwidth(int bandwidthHz) {
        int bwCode = nearestBandwidth(bandwidthHz)
        int rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb & 0xF8) | bwCode)
    }

    int[] readRaw() {
        byte[] raw = readBurst(REG_ACC_X_LSB, 6)
        int rx = it.uhde.periph.connection.Register.toSigned(((raw[1] & 0xFF) << 2) | ((raw[0] & 0xC0) >> 6), 10)
        int ry = it.uhde.periph.connection.Register.toSigned(((raw[3] & 0xFF) << 2) | ((raw[2] & 0xC0) >> 6), 10)
        int rz = it.uhde.periph.connection.Register.toSigned(((raw[5] & 0xFF) << 2) | ((raw[4] & 0xC0) >> 6), 10)
        return [rx, ry, rz] as int[]
    }

    double readTemperature() {
        int raw = readReg(REG_TEMP)
        return raw * 0.5 - 30.0d
    }

    boolean newDataAvailable() {
        int x = readReg(REG_ACC_X_LSB)
        int y = readReg(REG_ACC_Y_LSB)
        int z = readReg(REG_ACC_Z_LSB)
        return (x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0
    }

    void setShadow(boolean enabled) {
        int cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, enabled ? (cfg | 0x08) : (cfg & ~0x08 & 0xFF))
    }

    void setLowG(double thresholdG, int durationMs, double hysteresisG = 0, int counter = 0) {
        writeThreshold(REG_LG_THRES, thresholdG)
        writeReg(REG_LG_DUR, Math.min(255, Math.max(0, durationMs)))
        writeHyst('lg', hysteresisG)
        writeIntCounter('lg', counter)
        enableSource(SOURCE_LOW_G)
    }

    void setHighG(double thresholdG, int durationMs, double hysteresisG = 0, int counter = 0) {
        writeThreshold(REG_HG_THRES, thresholdG)
        writeReg(REG_HG_DUR, Math.min(255, Math.max(0, durationMs)))
        writeHyst('hg', hysteresisG)
        writeIntCounter('hg', counter)
        enableSource(SOURCE_HIGH_G)
    }

    void setAnyMotion(double thresholdG, int samples = 1) {
        double scale
        switch (rangeG) {
            case 4:  scale = FULL_SCALE_4G / 256.0d; break
            case 8:  scale = FULL_SCALE_8G / 256.0d; break
            default: scale = FULL_SCALE_2G / 256.0d; break
        }
        int code = (int) Math.round(thresholdG / (0.0156d * scale))
        code = Math.min(255, Math.max(0, code))
        writeReg(REG_ANY_MOTION_THRES, code)
        int durCode
        switch (samples) {
            case 3:  durCode = 0x40; break
            case 5:  durCode = 0x80; break
            case 7:  durCode = 0xC0; break
            default: durCode = 0x00; break
        }
        int hd = readReg(REG_HYST_DUR)
        writeReg(REG_HYST_DUR, (hd & 0x3F) | durCode)
        int cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, cfg | 0x40)
        enableSource(SOURCE_ANY_MOTION)
    }

    void setAlert(boolean enabled) {
        if (enabled) {
            enabledSources = enabledSources & ~SOURCE_ANY_MOTION
            int cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg | 0x40)
            enableSource(SOURCE_ALERT)
        } else {
            disableSource(SOURCE_ALERT)
        }
    }

    void setLatch(boolean enabled) {
        int cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, enabled ? (cfg | 0x10) : (cfg & ~0x10 & 0xFF))
    }

    void clearInterrupt() {
        if (sleeping) return
        int ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl | 0x40)
    }

    void enableInterrupt(int source) {
        if (source == SOURCE_NEW_DATA) {
            enabledSources = enabledSources & 0x0F
        } else {
            enabledSources = enabledSources & ~SOURCE_NEW_DATA
            if (source == SOURCE_ANY_MOTION) {
                enabledSources = enabledSources & ~SOURCE_ALERT
            } else if (source == SOURCE_ALERT) {
                enabledSources = enabledSources & ~SOURCE_ANY_MOTION
            }
        }
        enableSource(source)
    }

    void disableInterrupt(int source) {
        disableSource(source)
    }

    int pollInterrupt() { return readReg(REG_STATUS) }

    void setWakeUp(boolean enabled, int pauseMs = 20) {
        int pauseCode
        switch (pauseMs) {
            case 80:   pauseCode = 0x02; break
            case 320:  pauseCode = 0x04; break
            case 2560: pauseCode = 0x06; break
            default:   pauseCode = 0x00; break
        }
        int cfg = readReg(REG_CONFIG)
        int out = (cfg & 0xF8) | pauseCode | (enabled ? 0x01 : 0x00)
        writeReg(REG_CONFIG, out)
    }

    void sleep() {
        if (sleeping) return
        int ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl | 0x01)
        sleeping = true
    }

    void wake() {
        if (!sleeping) return
        int ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl & ~0x01 & 0xFF)
        Thread.sleep(2)
        sleeping = false
    }

    void softReset() {
        int ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl | 0x02)
        Thread.sleep(30)
        int rangeMask
        switch (rangeG) {
            case 4:  rangeMask = RANGE_4G_MASK; break
            case 8:  rangeMask = RANGE_8G_MASK; break
            default: rangeMask = RANGE_2G_MASK; break
        }
        int rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb & 0xE0) | rangeMask | BW_100)
        sleeping = false
    }

    boolean selfTest() {
        int ctrl = readReg(REG_CTRL)
        writeReg(REG_CTRL, ctrl | 0x04)
        Thread.sleep(100)
        int status = readReg(REG_STATUS)
        writeReg(REG_CTRL, ctrl)
        return (status & STATUS_ST_RESULT) != 0
    }

    int readStatus() { return readReg(REG_STATUS) }

    int[] readVersion() {
        int raw = readReg(REG_VERSION)
        return [(raw >> 4) & 0x0F, raw & 0x0F] as int[]
    }

    int readCustomer(int index) {
        return readReg(index == 0 ? REG_CUSTOMER_1 : REG_CUSTOMER_2)
    }

    void writeCustomer(int index, int value) {
        writeReg(index == 0 ? REG_CUSTOMER_1 : REG_CUSTOMER_2, value & 0xFF)
    }

    private void writeThreshold(int reg, double thresholdG) {
        int code = (int) Math.round(thresholdG * 255.0d / rangeG)
        code = Math.min(255, Math.max(0, code))
        writeReg(reg, code)
    }

    private void writeHyst(String kind, double hysteresisG) {
        if (hysteresisG < 0) return
        int code = (int) Math.round(hysteresisG * 255.0d / rangeG / 32.0d)
        code = Math.min(7, Math.max(0, code))
        int hd = readReg(REG_HYST_DUR)
        int out = kind == 'lg' ? (hd & 0xF8) | code : (hd & 0xC7) | (code << 3)
        writeReg(REG_HYST_DUR, out)
    }

    private void writeIntCounter(String kind, int counter) {
        if (counter < 0 || counter > 3) return
        int code = (counter & 0x03) << 2  // LG bits 3:2; HG shifts 2 more (bits 5:4)
        int ic = readReg(REG_INT_CTRL)
        int out = kind == 'lg' ? (ic & 0xF3) | code : (ic & 0xCF) | (code << 2)
        writeReg(REG_INT_CTRL, out)
    }

    private void enableSource(int source) {
        if (sleeping) return
        enabledSources = enabledSources | source
        if (source == SOURCE_NEW_DATA) {
            int cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg | 0x20)
            return
        }
        int ic = readReg(REG_INT_CTRL)
        int out = ic
        if (source == SOURCE_LOW_G)      out = out | 0x01
        if (source == SOURCE_HIGH_G)     out = out | 0x02
        if (source == SOURCE_ANY_MOTION) out = out | 0x40
        if (source == SOURCE_ALERT)     out = out | 0x80
        writeReg(REG_INT_CTRL, out)
    }

    private void disableSource(int source) {
        enabledSources = enabledSources & ~source
        if (source == SOURCE_NEW_DATA) {
            int cfg = readReg(REG_CONFIG)
            writeReg(REG_CONFIG, cfg & ~0x20 & 0xFF)
            return
        }
        int ic = readReg(REG_INT_CTRL)
        int out = ic
        if (source == SOURCE_LOW_G)      out = out & ~0x01 & 0xFF
        if (source == SOURCE_HIGH_G)     out = out & ~0x02 & 0xFF
        if (source == SOURCE_ANY_MOTION) out = out & ~0x40 & 0xFF
        if (source == SOURCE_ALERT)     out = out & ~0x80 & 0xFF
        writeReg(REG_INT_CTRL, out)
    }

    private static int nearestBandwidth(int bwHz) {
        int[] hz = [25, 50, 100, 190, 375, 750, 1500] as int[]
        int[] bw = [BW_25, BW_50, BW_100, BW_190, BW_375, BW_750, BW_1500] as int[]
        int bestIdx = 2
        int bestDiff = Math.abs(hz[2] - bwHz)
        for (int i = 0; i < hz.length; i++) {
            int diff = Math.abs(hz[i] - bwHz)
            if (diff < bestDiff) {
                bestIdx = i
                bestDiff = diff
            }
        }
        return bw[bestIdx]
    }
}
