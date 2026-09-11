package it.uhde.periph.chips.light;

import java.io.IOException;

/**
 * APDS-9930 — full driver. Extends {@link Apds9930Minimal} with ALS/proximity
 * configuration, raw channel reads, interrupt thresholds with persistence,
 * status decoding, sleep-after-interrupt, and proximity offset compensation.
 */
public class Apds9930Full extends Apds9930Minimal {

    /**
     * Construct the full driver.
     *
     * @param connection I²C connection bound to the APDS-9930 device address (0x39)
     * @throws IOException on I²C error
     */
    public Apds9930Full(it.uhde.periph.connection.Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Configure ALS integration time, AGAIN index, and AGL flag.
     *
     * @param atime ATIME register value 0-255
     * @param again ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x)
     * @param agl true to enable the AGL divide-by-6 gain-level bit
     * @throws IOException on I²C error
     */
    public void configureAls(int atime, int again, boolean agl) throws IOException {
        writeReg(REG_ATIME, atime & 0xFF);
        int ctrl = readReg(REG_CONTROL);
        ctrl = (ctrl & 0xFC) | (again & 0x03);
        writeReg(REG_CONTROL, ctrl);
        int cfg = readReg(REG_CONFIG);
        if (agl) cfg |= 0x04; else cfg &= ~0x04;
        cfg &= ~0x06;
        writeReg(REG_CONFIG, cfg);
    }

    /**
     * Configure proximity LED pulses, gain, drive, and ADC integration time.
     *
     * @param ppulse number of LED pulses 1-255
     * @param pgain  proximity gain index 0-3 (0=1x, 1=2x, 2=4x, 3=8x)
     * @param pdrive LED drive current index 0-3 (0=100 mA, 1=50 mA, 2=25 mA, 3=12.5 mA)
     * @param pdl    true to enable PDL (reduces drive to 1/9 of PDRIVE)
     * @param ptime  PTIME register value 0-255
     * @throws IOException on I²C error
     */
    public void configureProximity(int ppulse, int pgain, int pdrive, boolean pdl, int ptime) throws IOException {
        writeReg(REG_PPULSE, ppulse & 0xFF);
        writeReg(REG_PTIME, ptime & 0xFF);
        int ctrl = readReg(REG_CONTROL);
        ctrl = (ctrl & 0x03) | ((pdrive & 0x03) << 6) | 0x20 | ((pgain & 0x03) << 2);
        writeReg(REG_CONTROL, ctrl);
        int cfg = readReg(REG_CONFIG);
        if (pdl) cfg |= 0x01; else cfg &= ~0x01;
        cfg &= ~0x06;
        writeReg(REG_CONFIG, cfg);
    }

    /**
     * Configure wait time and enable the wait timer.
     *
     * @param wtime WTIME register value 0-255
     * @param wlong true to enable WLONG (multiplies wait by 12x)
     * @throws IOException on I²C error
     */
    public void configureWait(int wtime, boolean wlong) throws IOException {
        writeReg(REG_WTIME, wtime & 0xFF);
        int cfg = readReg(REG_CONFIG);
        if (wlong) cfg |= 0x02; else cfg &= ~0x02;
        cfg &= ~0x04;
        writeReg(REG_CONFIG, cfg);
        int en = readReg(REG_ENABLE);
        en |= 0x08;
        writeReg(REG_ENABLE, en);
    }

    /** Clear WEN in ENABLE (disable the wait timer). */
    public void disableWait() throws IOException {
        int en = readReg(REG_ENABLE);
        en &= ~0x08;
        writeReg(REG_ENABLE, en);
    }

    /** Read the raw Ch0 (visible + IR) ADC count. */
    public int ch0() throws IOException {
        return readReg16(REG_CH0DATAL);
    }

    /** Read the raw Ch1 (IR-only) ADC count. */
    public int ch1() throws IOException {
        return readReg16(REG_CH1DATAL);
    }

    /** Decode STATUS register into named boolean fields. */
    public static class Status {
        /** @see Apds9930Minimal#REG_STATUS bit 0 */
        public final boolean avalid;
        /** @see Apds9930Minimal#REG_STATUS bit 1 */
        public final boolean pvalid;
        /** @see Apds9930Minimal#REG_STATUS bit 6 */
        public final boolean psat;
        /** @see Apds9930Minimal#REG_STATUS bit 4 */
        public final boolean aint;
        /** @see Apds9930Minimal#REG_STATUS bit 5 */
        public final boolean pint;

        public Status(boolean avalid, boolean pvalid, boolean psat, boolean aint, boolean pint) {
            this.avalid = avalid; this.pvalid = pvalid; this.psat = psat;
            this.aint = aint; this.pint = pint;
        }
    }

    /** Read the STATUS register decoded into named fields. */
    public Status status() throws IOException {
        int s = readReg(REG_STATUS);
        return new Status(
            (s & 0x01) != 0,
            (s & 0x02) != 0,
            (s & 0x40) != 0,
            (s & 0x10) != 0,
            (s & 0x20) != 0
        );
    }

    /**
     * Set ALS interrupt thresholds and enable AIEN. Thresholds are
     * evaluated against raw Ch0 counts, not lux.
     *
     * @param low  16-bit low threshold
     * @param high 16-bit high threshold
     * @param persistence APERS value 0-15 (0=every, 1, 2, 3, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
     * @throws IOException on I²C error
     */
    public void setAlsThresholds(int low, int high, int persistence) throws IOException {
        if (low > high) high = low;
        writeReg(REG_AILTL, low & 0xFF);
        writeReg(REG_AILTH, (low >> 8) & 0xFF);
        writeReg(REG_AIHTL, high & 0xFF);
        writeReg(REG_AIHTH, (high >> 8) & 0xFF);
        int pers = readReg(REG_PERS);
        pers = (pers & 0xF0) | (persistence & 0x0F);
        writeReg(REG_PERS, pers);
        int en = readReg(REG_ENABLE);
        en |= 0x10;
        writeReg(REG_ENABLE, en);
    }

    /**
     * Set proximity interrupt thresholds and enable PIEN.
     *
     * @param low  16-bit low threshold
     * @param high 16-bit high threshold
     * @param persistence PPERS value 0-15 (0=every, 1-15=N consecutive)
     * @throws IOException on I²C error
     */
    public void setProximityThresholds(int low, int high, int persistence) throws IOException {
        if (low > high) high = low;
        writeReg(REG_PILTL, low & 0xFF);
        writeReg(REG_PILTH, (low >> 8) & 0xFF);
        writeReg(REG_PIHTL, high & 0xFF);
        writeReg(REG_PIHTH, (high >> 8) & 0xFF);
        int pers = readReg(REG_PERS);
        pers = (pers & 0x0F) | ((persistence & 0x0F) << 4);
        writeReg(REG_PERS, pers);
        int en = readReg(REG_ENABLE);
        en |= 0x20;
        writeReg(REG_ENABLE, en);
    }

    /**
     * Clear pending interrupt(s).
     *
     * @param channel 0=both, 1=ALS, 2=proximity
     * @throws IOException on I²C error
     */
    public void clearInterrupt(int channel) throws IOException {
        switch (channel) {
            case 1:  special(CFN_CLEAR_ALS); break;
            case 2:  special(CFN_CLEAR_PROXIMITY); break;
            default: special(CFN_CLEAR_BOTH); break;
        }
    }

    /**
     * Set the proximity offset (sign-magnitude).
     *
     * @param offset signed integer -127..+127 (positive shifts data up)
     * @throws IOException on I²C error
     */
    public void setProximityOffset(int offset) throws IOException {
        int enc;
        if (offset >= 0) enc = 0x80 | (offset & 0x7F);
        else             enc = (-offset) & 0x7F;
        writeReg(REG_POFFSET, enc);
    }

    /** Enable or disable SAI (sleep after interrupt). */
    public void sleepAfterInterrupt(boolean enable) throws IOException {
        int en = readReg(REG_ENABLE);
        if (enable) en |= 0x40; else en &= ~0x40;
        writeReg(REG_ENABLE, en);
    }
}