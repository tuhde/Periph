'use strict';

const _REG_ENABLE   = 0x00;
const _REG_ATIME    = 0x01;
const _REG_PTIME    = 0x02;
const _REG_WTIME    = 0x03;
const _REG_AILTL    = 0x04;
const _REG_AILTH    = 0x05;
const _REG_AIHTL    = 0x06;
const _REG_AIHTH    = 0x07;
const _REG_PILTL    = 0x08;
const _REG_PILTH    = 0x09;
const _REG_PIHTL    = 0x0A;
const _REG_PIHTH    = 0x0B;
const _REG_PERS     = 0x0C;
const _REG_CONFIG   = 0x0D;
const _REG_PPULSE   = 0x0E;
const _REG_CONTROL  = 0x0F;
const _REG_ID       = 0x12;
const _REG_STATUS   = 0x13;
const _REG_CH0DATAL = 0x14;
const _REG_CH0DATAH = 0x15;
const _REG_CH1DATAL = 0x16;
const _REG_CH1DATAH = 0x17;
const _REG_PDATAL   = 0x18;
const _REG_PDATAH   = 0x19;
const _REG_POFFSET  = 0x1E;

const _CFN_CLEAR_PROXIMITY = 0x05;
const _CFN_CLEAR_ALS       = 0x06;
const _CFN_CLEAR_BOTH      = 0x07;
const _CMD_SPECIAL         = 0xE0;

const _ATIME_DEFAULT   = 0xDB;
const _PTIME_DEFAULT   = 0xFF;
const _PPULSE_DEFAULT  = 0x08;
const _CONTROL_DEFAULT = 0x20;
const _ENABLE_DEFAULT  = 0x07;

const _CMD_WRITE = 0x80;
const _CMD_READ  = 0xA0;

function _cmdWrite(reg) { return _CMD_WRITE | (reg & 0x1F); }
function _cmdRead(reg)  { return _CMD_READ  | (reg & 0x1F); }
function _cmdSpecial(f) { return _CMD_SPECIAL | (f & 0x1F); }

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

function _againFactor(againIdx, agl) {
    if (!agl) return [1, 8, 16, 120][againIdx & 0x03];
    return [1/6, 8/6, 16/6, 20][againIdx & 0x03];
}

/**
 * APDS-9930 digital ambient light and proximity sensor — minimal interface.
 *
 * Provides illuminance (lux, IR-compensated) and raw proximity count with
 * no configuration required beyond the connection. Both engines are
 * enabled at construction with sensible defaults that give stable
 * readings under typical indoor/outdoor lighting.
 *
 * Default configuration (written at construction):
 * - ATIME   = 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)
 * - PTIME   = 0xFF (2.73 ms proximity ADC time, datasheet default)
 * - PPULSE  = 0x08 (8 LED pulses — factory-calibrated for 100 mm range)
 * - CONTROL = 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)
 * - ENABLE  = 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)
 *
 * Constructor caveat: JS constructors cannot be async, so the original
 * synchronous ID-validation throw cannot be preserved exactly. The whole
 * init sequence is fired off unawaited (matching the fire-and-forget
 * convention used throughout this port); an ID mismatch now surfaces as
 * an *unhandled promise rejection* shortly after construction rather
 * than a synchronous throw from `new APDS9930Minimal(...)`. Callers
 * that need to detect "wrong or absent device" deterministically should
 * call an async method (e.g. `await sensor.lux()`) right after
 * construction and handle its rejection.
 */
class APDS9930Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device (address 0x39).
     */
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        _sleep(6);
        const id = await this._readReg(_REG_ID);
        if (id !== 0x39) throw new Error('APDS-9930 not found (ID=0x' + id.toString(16) + ', expected 0x39)');
        await this._writeReg(_REG_ENABLE, 0x00);
        await this._writeReg(_REG_ATIME, _ATIME_DEFAULT);
        await this._writeReg(_REG_PTIME, _PTIME_DEFAULT);
        await this._writeReg(_REG_PPULSE, _PPULSE_DEFAULT);
        await this._writeReg(_REG_CONTROL, _CONTROL_DEFAULT);
        await this._writeReg(_REG_ENABLE, _ENABLE_DEFAULT);
        _sleep(12);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([_cmdWrite(reg), value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._conn.writeRead(Buffer.from([_cmdRead(reg)]), 1))[0];
    }

    async _readReg16(reg) {
        const buf = await this._conn.writeRead(Buffer.from([_cmdRead(reg)]), 2);
        return (buf[1] << 8) | buf[0];
    }

    async _special(functionCode) {
        await this._conn.write(Buffer.from([_cmdSpecial(functionCode)]));
    }

    /**
     * Read the ambient illuminance.
     *
     * Uses Ch0 (visible + IR) and Ch1 (IR-only) to compensate for the
     * IR component of ambient light, then applies the open-air lux
     * coefficients from the datasheet.
     *
     * @returns {Promise<number>} Illuminance in lux.
     */
    async lux() {
        const ch0 = await this._readReg16(_REG_CH0DATAL);
        const ch1 = await this._readReg16(_REG_CH1DATAL);
        const ctrl = await this._readReg(_REG_CONTROL);
        const cfg  = await this._readReg(_REG_CONFIG);
        const atime = await this._readReg(_REG_ATIME);
        const alsitMs = 2.73 * (256 - atime);
        const againX = _againFactor(ctrl & 0x03, (cfg & 0x04) !== 0);
        let iac1 = ch0 - 1.862 * ch1;
        let iac2 = 0.746 * ch0 - 1.291 * ch1;
        let iac = iac1;
        if (iac2 > iac) iac = iac2;
        if (iac < 0) iac = 0;
        const lpc = (0.49 * 52.0) / (alsitMs * againX);
        return iac * lpc;
    }

    /**
     * Read the proximity ADC count.
     *
     * Higher counts mean a closer object. Realistically limited to
     * 10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).
     *
     * @returns {Promise<number>} Raw 16-bit proximity count.
     */
    async proximity() {
        return this._readReg16(_REG_PDATAL);
    }
}

/**
 * APDS-9930 full interface — extends APDS9930Minimal.
 *
 * Adds ALS/proximity integration-time and gain configuration, raw
 * channel reads, interrupt thresholds with persistence, status decoding,
 * sleep-after-interrupt, and proximity offset compensation.
 */
class APDS9930Full extends APDS9930Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device (address 0x39).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Configure ALS integration time, AGAIN index, and AGL flag.
     * @param {number} [atime=0xDB] ATIME register value 0-255.
     * @param {number} [again=0] ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x).
     * @param {boolean} [agl=false] True to enable the AGL divide-by-6 gain-level bit.
     * @returns {Promise<void>}
     */
    async configureAls(atime = 0xDB, again = 0, agl = false) {
        await this._writeReg(_REG_ATIME, atime & 0xFF);
        const ctrl = (await this._readReg(_REG_CONTROL) & 0xFC) | (again & 0x03);
        await this._writeReg(_REG_CONTROL, ctrl);
        let cfg = await this._readReg(_REG_CONFIG);
        if (agl) cfg |= 0x04; else cfg &= ~0x04;
        cfg &= ~0x06;
        await this._writeReg(_REG_CONFIG, cfg);
    }

    /**
     * Configure proximity LED pulses, gain, drive, and ADC integration time.
     * @param {number} [ppulse=8] Number of LED pulses 1-255.
     * @param {number} [pgain=0] Proximity gain index 0-3.
     * @param {number} [pdrive=0] LED drive current index 0-3.
     * @param {boolean} [pdl=false] True to enable PDL (reduces drive to 1/9).
     * @param {number} [ptime=0xFF] PTIME register value 0-255.
     * @returns {Promise<void>}
     */
    async configureProximity(ppulse = 8, pgain = 0, pdrive = 0, pdl = false, ptime = 0xFF) {
        await this._writeReg(_REG_PPULSE, ppulse & 0xFF);
        await this._writeReg(_REG_PTIME, ptime & 0xFF);
        const ctrl = ((await this._readReg(_REG_CONTROL)) & 0x03)
                   | ((pdrive & 0x03) << 6)
                   | 0x20
                   | ((pgain & 0x03) << 2);
        await this._writeReg(_REG_CONTROL, ctrl);
        let cfg = await this._readReg(_REG_CONFIG);
        if (pdl) cfg |= 0x01; else cfg &= ~0x01;
        cfg &= ~0x06;
        await this._writeReg(_REG_CONFIG, cfg);
    }

    /**
     * Configure wait time and enable the wait timer.
     * @param {number} [wtime=0xFF] WTIME register value 0-255.
     * @param {boolean} [wlong=false] True to enable WLONG (multiplies wait by 12x).
     * @returns {Promise<void>}
     */
    async configureWait(wtime = 0xFF, wlong = false) {
        await this._writeReg(_REG_WTIME, wtime & 0xFF);
        let cfg = await this._readReg(_REG_CONFIG);
        if (wlong) cfg |= 0x02; else cfg &= ~0x02;
        cfg &= ~0x04;
        await this._writeReg(_REG_CONFIG, cfg);
        const en = (await this._readReg(_REG_ENABLE)) | 0x08;
        await this._writeReg(_REG_ENABLE, en);
    }

    /**
     * Clear WEN in ENABLE (disable the wait timer).
     * @returns {Promise<void>}
     */
    async disableWait() {
        const en = (await this._readReg(_REG_ENABLE)) & ~0x08;
        await this._writeReg(_REG_ENABLE, en);
    }

    /**
     * Read the raw Ch0 (visible + IR) ADC count.
     * @returns {Promise<number>} 16-bit ADC count.
     */
    async ch0() { return this._readReg16(_REG_CH0DATAL); }

    /**
     * Read the raw Ch1 (IR-only) ADC count.
     * @returns {Promise<number>} 16-bit ADC count.
     */
    async ch1() { return this._readReg16(_REG_CH1DATAL); }

    /**
     * Read the STATUS register decoded into named fields.
     * @returns {Promise<{avalid: boolean, pvalid: boolean, psat: boolean, aint: boolean, pint: boolean}>}
     */
    async status() {
        const s = await this._readReg(_REG_STATUS);
        return {
            avalid: (s & 0x01) !== 0,
            pvalid: (s & 0x02) !== 0,
            psat:   (s & 0x40) !== 0,
            aint:   (s & 0x10) !== 0,
            pint:   (s & 0x20) !== 0
        };
    }

    /**
     * Set ALS interrupt thresholds and enable AIEN. Thresholds are evaluated
     * against raw Ch0 counts, not lux.
     * @param {number} low 16-bit low threshold.
     * @param {number} high 16-bit high threshold.
     * @param {number} [persistence=1] APERS value 0-15.
     * @returns {Promise<void>}
     */
    async setAlsThresholds(low, high, persistence = 1) {
        if (low > high) high = low;
        await this._writeReg(_REG_AILTL, low & 0xFF);
        await this._writeReg(_REG_AILTH, (low >> 8) & 0xFF);
        await this._writeReg(_REG_AIHTL, high & 0xFF);
        await this._writeReg(_REG_AIHTH, (high >> 8) & 0xFF);
        const pers = (await this._readReg(_REG_PERS) & 0xF0) | (persistence & 0x0F);
        await this._writeReg(_REG_PERS, pers);
        const en = (await this._readReg(_REG_ENABLE)) | 0x10;
        await this._writeReg(_REG_ENABLE, en);
    }

    /**
     * Set proximity interrupt thresholds and enable PIEN.
     * @param {number} low 16-bit low threshold.
     * @param {number} high 16-bit high threshold.
     * @param {number} [persistence=1] PPERS value 0-15.
     * @returns {Promise<void>}
     */
    async setProximityThresholds(low, high, persistence = 1) {
        if (low > high) high = low;
        await this._writeReg(_REG_PILTL, low & 0xFF);
        await this._writeReg(_REG_PILTH, (low >> 8) & 0xFF);
        await this._writeReg(_REG_PIHTL, high & 0xFF);
        await this._writeReg(_REG_PIHTH, (high >> 8) & 0xFF);
        const pers = (await this._readReg(_REG_PERS) & 0x0F) | ((persistence & 0x0F) << 4);
        await this._writeReg(_REG_PERS, pers);
        const en = (await this._readReg(_REG_ENABLE)) | 0x20;
        await this._writeReg(_REG_ENABLE, en);
    }

    /**
     * Clear pending interrupt(s).
     * @param {('als'|'proximity'|'both')} [channel='both']
     * @returns {Promise<void>}
     */
    async clearInterrupt(channel = 'both') {
        if (channel === 'als') await this._special(_CFN_CLEAR_ALS);
        else if (channel === 'proximity') await this._special(_CFN_CLEAR_PROXIMITY);
        else await this._special(_CFN_CLEAR_BOTH);
    }

    /**
     * Set the proximity offset (sign-magnitude).
     * @param {number} offset Signed integer -127..+127 (positive shifts data up).
     * @returns {Promise<void>}
     */
    async setProximityOffset(offset) {
        const enc = offset >= 0
            ? (0x80 | (offset & 0x7F))
            : ((-offset) & 0x7F);
        await this._writeReg(_REG_POFFSET, enc);
    }

    /**
     * Enable or disable SAI (sleep after interrupt).
     * @param {boolean} enable True to set SAI, false to clear it.
     * @returns {Promise<void>}
     */
    async sleepAfterInterrupt(enable) {
        const en = enable
            ? ((await this._readReg(_REG_ENABLE)) | 0x40)
            : ((await this._readReg(_REG_ENABLE)) & ~0x40);
        await this._writeReg(_REG_ENABLE, en);
    }
}

module.exports = { APDS9930Minimal, APDS9930Full };