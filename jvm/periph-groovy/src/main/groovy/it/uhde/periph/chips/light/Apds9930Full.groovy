package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * APDS-9930 — full driver. Extends {@link Apds9930Minimal} with ALS/proximity
 * configuration, raw channel reads, interrupt thresholds with persistence,
 * status decoding, sleep-after-interrupt, and proximity offset compensation.
 */
@CompileStatic
class Apds9930Full extends Apds9930Minimal {

    static class Status {
        final boolean avalid
        final boolean pvalid
        final boolean psat
        final boolean aint
        final boolean pint

        Status(boolean avalid, boolean pvalid, boolean psat, boolean aint, boolean pint) {
            this.avalid = avalid; this.pvalid = pvalid; this.psat = psat
            this.aint = aint; this.pint = pint
        }
    }

    Apds9930Full(Connection connection) {
        super(connection)
    }

    /** Configure ALS integration time, AGAIN index, and AGL flag. */
    void configureAls(int atime, int again, boolean agl) {
        writeReg(REG_ATIME, atime & 0xFF)
        int ctrl = (readReg(REG_CONTROL) & 0xFC) | (again & 0x03)
        writeReg(REG_CONTROL, ctrl)
        int cfg = readReg(REG_CONFIG)
        if (agl) cfg |= 0x04 else cfg &= ~0x04
        cfg &= ~0x06
        writeReg(REG_CONFIG, cfg)
    }

    /** Configure proximity LED pulses, gain, drive, and ADC integration time. */
    void configureProximity(int ppulse, int pgain, int pdrive, boolean pdl, int ptime) {
        writeReg(REG_PPULSE, ppulse & 0xFF)
        writeReg(REG_PTIME, ptime & 0xFF)
        int ctrl = (readReg(REG_CONTROL) & 0x03) | ((pdrive & 0x03) << 6) | 0x20 | ((pgain & 0x03) << 2)
        writeReg(REG_CONTROL, ctrl)
        int cfg = readReg(REG_CONFIG)
        if (pdl) cfg |= 0x01 else cfg &= ~0x01
        cfg &= ~0x06
        writeReg(REG_CONFIG, cfg)
    }

    /** Configure wait time and enable the wait timer. */
    void configureWait(int wtime, boolean wlong) {
        writeReg(REG_WTIME, wtime & 0xFF)
        int cfg = readReg(REG_CONFIG)
        if (wlong) cfg |= 0x02 else cfg &= ~0x02
        cfg &= ~0x04
        writeReg(REG_CONFIG, cfg)
        int en = readReg(REG_ENABLE)
        en |= 0x08
        writeReg(REG_ENABLE, en)
    }

    /** Clear WEN in ENABLE (disable the wait timer). */
    void disableWait() {
        int en = readReg(REG_ENABLE)
        en &= ~0x08
        writeReg(REG_ENABLE, en)
    }

    /** Read the raw Ch0 (visible + IR) ADC count. */
    int ch0() { readReg16(REG_CH0DATAL) }

    /** Read the raw Ch1 (IR-only) ADC count. */
    int ch1() { readReg16(REG_CH1DATAL) }

    /** Read the STATUS register decoded into named fields. */
    Status status() {
        int s = readReg(REG_STATUS)
        return new Status(
            (s & 0x01) != 0,
            (s & 0x02) != 0,
            (s & 0x40) != 0,
            (s & 0x10) != 0,
            (s & 0x20) != 0
        )
    }

    /** Set ALS interrupt thresholds and enable AIEN. */
    void setAlsThresholds(int low, int high, int persistence) {
        if (low > high) high = low
        writeReg(REG_AILTL, low & 0xFF)
        writeReg(REG_AILTH, (low >> 8) & 0xFF)
        writeReg(REG_AIHTL, high & 0xFF)
        writeReg(REG_AIHTH, (high >> 8) & 0xFF)
        int pers = (readReg(REG_PERS) & 0xF0) | (persistence & 0x0F)
        writeReg(REG_PERS, pers)
        int en = readReg(REG_ENABLE)
        en |= 0x10
        writeReg(REG_ENABLE, en)
    }

    /** Set proximity interrupt thresholds and enable PIEN. */
    void setProximityThresholds(int low, int high, int persistence) {
        if (low > high) high = low
        writeReg(REG_PILTL, low & 0xFF)
        writeReg(REG_PILTH, (low >> 8) & 0xFF)
        writeReg(REG_PIHTL, high & 0xFF)
        writeReg(REG_PIHTH, (high >> 8) & 0xFF)
        int pers = (readReg(REG_PERS) & 0x0F) | ((persistence & 0x0F) << 4)
        writeReg(REG_PERS, pers)
        int en = readReg(REG_ENABLE)
        en |= 0x20
        writeReg(REG_ENABLE, en)
    }

    /** Clear pending interrupt(s). */
    void clearInterrupt(int channel) {
        switch (channel) {
            case 1:  special(CFN_CLEAR_ALS); break
            case 2:  special(CFN_CLEAR_PROXIMITY); break
            default: special(CFN_CLEAR_BOTH); break
        }
    }

    /** Set the proximity offset (sign-magnitude). */
    void setProximityOffset(int offset) {
        int enc
        if (offset >= 0) enc = 0x80 | (offset & 0x7F)
        else            enc = (-offset) & 0x7F
        writeReg(REG_POFFSET, enc)
    }

    /** Enable or disable SAI (sleep after interrupt). */
    void sleepAfterInterrupt(boolean enable) {
        int en = readReg(REG_ENABLE)
        if (enable) en |= 0x40 else en &= ~0x40
        writeReg(REG_ENABLE, en)
    }
}