'use strict';

const _REG_ZMCO        = 0x00;
const _REG_ZPOS_H      = 0x01;
const _REG_ZPOS_L      = 0x02;
const _REG_MPOS_H      = 0x03;
const _REG_MPOS_L      = 0x04;
const _REG_MANG_H      = 0x05;
const _REG_MANG_L      = 0x06;
const _REG_CONF_H      = 0x07;
const _REG_CONF_L      = 0x08;
const _REG_STATUS      = 0x0B;
const _REG_RAW_ANGLE_H = 0x0C;
const _REG_RAW_ANGLE_L = 0x0D;
const _REG_ANGLE_H     = 0x0E;
const _REG_ANGLE_L     = 0x0F;
const _REG_AGC         = 0x1A;
const _REG_MAGNITUDE_H = 0x1B;
const _REG_MAGNITUDE_L = 0x1C;
const _REG_BURN        = 0xFF;

const _STATUS_MD = 0x08;
const _STATUS_ML = 0x10;
const _STATUS_MH = 0x20;

/**
 * AS5600 12-bit programmable contactless rotary position sensor — minimal interface.
 *
 * Reads the absolute angle in degrees with no configuration required beyond the
 * connection. Verifies magnet presence at construction; throws if no magnet is detected.
 *
 * Constructor caveat: JS constructors cannot be async, but the original
 * synchronous constructor validated magnet presence and threw synchronously
 * on mismatch — that guarantee cannot be preserved exactly. The check is
 * fired off unawaited (matching the fire-and-forget convention used
 * throughout this port); "magnet not detected" now surfaces as an
 * *unhandled promise rejection* shortly after construction rather than a
 * synchronous throw from `new AS5600Minimal(...)`. Callers that need to
 * detect this deterministically should call `await sensor.isMagnetDetected()`
 * right after construction.
 */
class AS5600Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (fixed address 0x36).
     */
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        const status = await this._readReg8(_REG_STATUS);
        if (!(status & _STATUS_MD)) {
            throw new Error('AS5600: magnet not detected (MD=0)');
        }
    }

    async _readReg8(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 1))[0];
    }

    async _readReg16(reg) {
        const raw = await this._conn.writeRead(Buffer.from([reg]), 2);
        return (raw[0] << 8) | raw[1];
    }

    async _writeReg8(reg, value) {
        const buf = Buffer.alloc(2);
        buf[0] = reg;
        buf[1] = value;
        await this._conn.write(buf);
    }

    async _writeReg16(reg, value) {
        const buf = Buffer.alloc(3);
        buf[0] = reg;
        buf.writeUInt16BE(value, 1);
        await this._conn.write(buf);
    }

    /**
     * Read the scaled absolute angle.
     * @returns {Promise<number>} Angle in degrees, 0.0–360.0 (exclusive).
     */
    async angle() {
        return (await this.angleRaw()) * 360.0 / 4096;
    }

    /**
     * Read the scaled 12-bit angle count.
     * @returns {Promise<number>} Scaled angle count, 0–4095 (respects ZPOS/MPOS if programmed).
     */
    async angleRaw() {
        const raw = await this._readReg16(_REG_ANGLE_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Check if a magnet is detected.
     * @returns {Promise<boolean>} True if STATUS.MD=1 (magnetic field >= 8 mT).
     */
    async isMagnetDetected() {
        return !!((await this._readReg8(_REG_STATUS)) & _STATUS_MD);
    }

    /**
     * Check if the magnet is too strong.
     * @returns {Promise<boolean>} True if STATUS.MH=1 (AGC minimum gain overflow, Bz > 90 mT).
     */
    async isMagnetTooStrong() {
        return !!((await this._readReg8(_REG_STATUS)) & _STATUS_MH);
    }

    /**
     * Check if the magnet is too weak.
     * @returns {Promise<boolean>} True if STATUS.ML=1 (AGC maximum gain overflow, Bz < 30 mT).
     */
    async isMagnetTooWeak() {
        return !!((await this._readReg8(_REG_STATUS)) & _STATUS_ML);
    }
}

/**
 * AS5600 full interface — extends AS5600Minimal with complete chip functionality.
 *
 * Adds raw angle readings, AGC/magnitude/status access, configuration,
 * ZPOS/MPOS/MANG programming, and OTP burn commands.
 */
class AS5600Full extends AS5600Minimal {
    static PM_NOM  = 0;
    static PM_LPM1 = 1;
    static PM_LPM2 = 2;
    static PM_LPM3 = 3;

    static OUTS_ANALOG  = 0;
    static OUTS_ANALOG2 = 1;
    static OUTS_PWM     = 2;

    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (fixed address 0x36).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Read the unscaled raw 12-bit angle count.
     * @returns {Promise<number>} Raw angle count, 0–4095 (unaffected by ZPOS/MPOS).
     */
    async rawAngle() {
        const raw = await this._readReg16(_REG_RAW_ANGLE_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Read the unscaled raw angle in degrees.
     * @returns {Promise<number>} Raw angle in degrees, 0.0–360.0.
     */
    async rawAngleDegrees() {
        return (await this.rawAngle()) * 360.0 / 4096;
    }

    /**
     * Read the automatic gain control value.
     * @returns {Promise<number>} AGC value (0–255 in 5 V mode; 0–127 in 3.3 V mode).
     */
    async agc() {
        return this._readReg8(_REG_AGC);
    }

    /**
     * Read the CORDIC magnitude value.
     * @returns {Promise<number>} 12-bit CORDIC magnitude value.
     */
    async magnitude() {
        const raw = await this._readReg16(_REG_MAGNITUDE_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Read the raw STATUS register byte.
     * @returns {Promise<number>} Raw STATUS register (bits MH, ML, MD in positions 5, 4, 3).
     */
    async statusByte() {
        return this._readReg8(_REG_STATUS);
    }

    /**
     * Write the CONF_H and CONF_L registers.
     *
     * Reads the current CONF_H/CONF_L values first to preserve the reserved
     * bits in CONF_H[7:6].
     *
     * @param {number} [pm=0]    - Power mode 0–3 (0=NOM, 1=LPM1, 2=LPM2, 3=LPM3).
     * @param {number} [hyst=0]  - Hysteresis 0–3 (0=off, 1=1 LSB, 2=2 LSBs, 3=3 LSBs).
     * @param {number} [outs=0]  - Output stage 0–2 (0=analog 0–VDD, 1=analog 10–90%, 2=PWM).
     * @param {number} [pwmf=0]  - PWM frequency 0–3 (0=115 Hz, 1=230 Hz, 2=460 Hz, 3=920 Hz).
     * @param {number} [sf=0]    - Slow filter 0–3 (0=16x, 1=8x, 2=4x, 3=2x).
     * @param {number} [fth=0]   - Fast filter threshold 0–7.
     * @param {boolean} [wd=false] - Watchdog enable.
     * @returns {Promise<void>}
     */
    async configure(pm = 0, hyst = 0, outs = 0, pwmf = 0, sf = 0, fth = 0, wd = false) {
        let confH = await this._readReg8(_REG_CONF_H);
        let confL = await this._readReg8(_REG_CONF_L);
        confH = (confH & 0xC0) | ((wd ? 1 : 0) << 5) | ((fth & 0x07) << 2) | (sf & 0x03);
        confL = ((pwmf & 0x03) << 6) | ((outs & 0x03) << 4) | ((hyst & 0x03) << 2) | (pm & 0x03);
        await this._writeReg16(_REG_CONF_H, (confH << 8) | confL);
    }

    /**
     * Write the zero position (start angle) to volatile RAM.
     * @param {number} pos - Zero position 0–4095. Lost on power cycle unless burned.
     * @returns {Promise<void>}
     */
    async setZeroPosition(pos) {
        await this._writeReg8(_REG_ZPOS_H, (pos >> 8) & 0x0F);
        await this._writeReg8(_REG_ZPOS_L, pos & 0xFF);
    }

    /**
     * Write the maximum position (stop angle) to volatile RAM.
     * @param {number} pos - Maximum position 0–4095. Lost on power cycle unless burned.
     * @returns {Promise<void>}
     */
    async setMaxPosition(pos) {
        await this._writeReg8(_REG_MPOS_H, (pos >> 8) & 0x0F);
        await this._writeReg8(_REG_MPOS_L, pos & 0xFF);
    }

    /**
     * Write the maximum angle span to volatile RAM.
     * @param {number} span - Angle span 0–4095 (must correspond to >= 18 degrees).
     * @returns {Promise<void>}
     */
    async setMaxAngle(span) {
        await this._writeReg8(_REG_MANG_H, (span >> 8) & 0x0F);
        await this._writeReg8(_REG_MANG_L, span & 0xFF);
    }

    /**
     * Read the zero position (start angle).
     * @returns {Promise<number>} ZPOS value 0–4095.
     */
    async zeroPosition() {
        const raw = await this._readReg16(_REG_ZPOS_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Read the maximum position (stop angle).
     * @returns {Promise<number>} MPOS value 0–4095.
     */
    async maxPosition() {
        const raw = await this._readReg16(_REG_MPOS_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Read the maximum angle span.
     * @returns {Promise<number>} MANG value 0–4095.
     */
    async maxAngle() {
        const raw = await this._readReg16(_REG_MANG_H);
        return (raw >> 4) & 0x0FFF;
    }

    /**
     * Read the number of permanent ZPOS/MPOS burns already performed.
     * @returns {Promise<number>} ZMCO value 0–3. Remaining permanent writes = 3 - ZMCO.
     */
    async burnCount() {
        return (await this._readReg8(_REG_ZMCO)) & 0x03;
    }

    /**
     * Permanently burn ZPOS and MPOS to OTP.
     *
     * Requires MD=1 (magnet present) and ZMCO < 3.
     *
     * @throws {Error} If magnet not detected or ZMCO >= 3.
     * @returns {Promise<void>}
     */
    async burnAngle() {
        const status = await this._readReg8(_REG_STATUS);
        if (!(status & _STATUS_MD)) {
            throw new Error('AS5600: cannot burn angle — magnet not detected');
        }
        const zmco = (await this._readReg8(_REG_ZMCO)) & 0x03;
        if (zmco >= 3) {
            throw new Error('AS5600: cannot burn angle — ZMCO limit reached (3)');
        }
        await this._writeReg8(_REG_BURN, 0x80);
    }

    /**
     * Permanently burn MANG and CONF to OTP.
     *
     * Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.
     *
     * @throws {Error} If ZMCO != 0.
     * @returns {Promise<void>}
     */
    async burnSetting() {
        const zmco = (await this._readReg8(_REG_ZMCO)) & 0x03;
        if (zmco !== 0) {
            throw new Error('AS5600: cannot burn setting — ZMCO must be 0');
        }
        await this._writeReg8(_REG_BURN, 0x40);
    }
}

module.exports = { AS5600Minimal, AS5600Full };
