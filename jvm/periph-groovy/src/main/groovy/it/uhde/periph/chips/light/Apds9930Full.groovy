package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

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

    Apds9930Full(RegisterConnection connection) {
        super(connection)
    }

    /** Configure ALS integration time, AGAIN index, and AGL flag. */
    void configureAls(int atime, int again, boolean agl) {
        connection.write(cmdWrite(REG_ATIME), [(byte) (atime & 0xFF)] as byte[])
        int ctrl = (readReg(REG_CONTROL) & 0xFC) | (again & 0x03)
        connection.write(cmdWrite(REG_CONTROL), [(byte) (ctrl)] as byte[])
        int cfg = readReg(REG_CONFIG)
        if (agl) cfg |= 0x04 else cfg &= ~0x04
        cfg &= ~0x06
        connection.write(cmdWrite(REG_CONFIG), [(byte) (cfg)] as byte[])
    }

    /** Configure proximity LED pulses, gain, drive, and ADC integration time. */
    void configureProximity(int ppulse, int pgain, int pdrive, boolean pdl, int ptime) {
        connection.write(cmdWrite(REG_PPULSE), [(byte) (ppulse & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_PTIME), [(byte) (ptime & 0xFF)] as byte[])
        int ctrl = (readReg(REG_CONTROL) & 0x03) | ((pdrive & 0x03) << 6) | 0x20 | ((pgain & 0x03) << 2)
        connection.write(cmdWrite(REG_CONTROL), [(byte) (ctrl)] as byte[])
        int cfg = readReg(REG_CONFIG)
        if (pdl) cfg |= 0x01 else cfg &= ~0x01
        cfg &= ~0x06
        connection.write(cmdWrite(REG_CONFIG), [(byte) (cfg)] as byte[])
    }

    /** Configure wait time and enable the wait timer. */
    void configureWait(int wtime, boolean wlong) {
        connection.write(cmdWrite(REG_WTIME), [(byte) (wtime & 0xFF)] as byte[])
        int cfg = readReg(REG_CONFIG)
        if (wlong) cfg |= 0x02 else cfg &= ~0x02
        cfg &= ~0x04
        connection.write(cmdWrite(REG_CONFIG), [(byte) (cfg)] as byte[])
        int en = readReg(REG_ENABLE)
        en |= 0x08
        connection.write(cmdWrite(REG_ENABLE), [(byte) (en)] as byte[])
    }

    /** Clear WEN in ENABLE (disable the wait timer). */
    void disableWait() {
        int en = readReg(REG_ENABLE)
        en &= ~0x08
        connection.write(cmdWrite(REG_ENABLE), [(byte) (en)] as byte[])
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
        connection.write(cmdWrite(REG_AILTL), [(byte) (low & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_AILTH), [(byte) ((low >> 8) & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_AIHTL), [(byte) (high & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_AIHTH), [(byte) ((high >> 8) & 0xFF)] as byte[])
        int pers = (readReg(REG_PERS) & 0xF0) | (persistence & 0x0F)
        connection.write(cmdWrite(REG_PERS), [(byte) (pers)] as byte[])
        int en = readReg(REG_ENABLE)
        en |= 0x10
        connection.write(cmdWrite(REG_ENABLE), [(byte) (en)] as byte[])
    }

    /** Set proximity interrupt thresholds and enable PIEN. */
    void setProximityThresholds(int low, int high, int persistence) {
        if (low > high) high = low
        connection.write(cmdWrite(REG_PILTL), [(byte) (low & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_PILTH), [(byte) ((low >> 8) & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_PIHTL), [(byte) (high & 0xFF)] as byte[])
        connection.write(cmdWrite(REG_PIHTH), [(byte) ((high >> 8) & 0xFF)] as byte[])
        int pers = (readReg(REG_PERS) & 0x0F) | ((persistence & 0x0F) << 4)
        connection.write(cmdWrite(REG_PERS), [(byte) (pers)] as byte[])
        int en = readReg(REG_ENABLE)
        en |= 0x20
        connection.write(cmdWrite(REG_ENABLE), [(byte) (en)] as byte[])
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
        connection.write(cmdWrite(REG_POFFSET), [(byte) (enc)] as byte[])
    }

    /** Enable or disable SAI (sleep after interrupt). */
    void sleepAfterInterrupt(boolean enable) {
        int en = readReg(REG_ENABLE)
        if (enable) en |= 0x40 else en &= ~0x40
        connection.write(cmdWrite(REG_ENABLE), [(byte) (en)] as byte[])
    }
}