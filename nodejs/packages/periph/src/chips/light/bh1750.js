'use strict';

const _OP_POWER_DOWN = 0x00;
const _OP_POWER_ON = 0x01;
const _OP_RESET = 0x07;
const _OP_CONTINUOUS = [0x10, 0x11, 0x13];
const _OP_ONE_TIME = [0x20, 0x21, 0x23];
const _OP_MT_HIGH = 0x40;
const _OP_MT_LOW = 0x60;
const _DEFAULT_MTREG = 69;
// maximum measurement time in milliseconds at MTreg = 69, indexed by mode
const _T_MAX_MS = [180, 180, 24];

const MODE_H = 0;
const MODE_H2 = 1;
const MODE_L = 2;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _checkMode(mode) {
    if (mode !== MODE_H && mode !== MODE_H2 && mode !== MODE_L) {
        throw new RangeError('BH1750 mode must be MODE_H, MODE_H2 or MODE_L');
    }
}

/**
 * BH1750FVI ambient light sensor — minimal interface.
 *
 * Each `read()` starts one one-time H-resolution measurement (opcode 0x20, 1 lx steps), waits
 * the datasheet maximum of 180 ms, reads the 16-bit result and returns the illuminance in lux
 * (raw / 1.2). The chip powers down by itself after the measurement.
 *
 * The device is command driven (no register pointer), so a plain I²C connection is used.
 * The constructor does not touch the bus; `init()` powers the chip on.
 */
class BH1750Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x23 or 0x5C).
     */
    constructor(connection) {
        this._conn = connection;
        this._mtreg = _DEFAULT_MTREG;
        this._mode = MODE_H;
    }

    /** Power the chip on (opcode 0x01). */
    async init() {
        await this._conn.write(Buffer.from([_OP_POWER_ON]));
    }

    _waitMs(mode) {
        return _T_MAX_MS[mode] * this._mtreg / _DEFAULT_MTREG;
    }

    _toLux(raw, mode) {
        const lux = raw / 1.2 * (_DEFAULT_MTREG / this._mtreg);
        return mode === MODE_H2 ? lux / 2.0 : lux;
    }

    async _readWord() {
        const data = Buffer.from(await this._conn.read(2));
        return (data[0] << 8) | data[1];
    }

    async _measureRaw(mode) {
        await this._conn.write(Buffer.from([_OP_ONE_TIME[mode]]));
        await _sleep(this._waitMs(mode));
        return this._readWord();
    }

    /**
     * Measure the illuminance once.
     * @returns {Promise<number>} Illuminance in lux (one-time measurement in the current mode, H-resolution by default).
     */
    async read() {
        return this._toLux(await this._measureRaw(this._mode), this._mode);
    }
}

/**
 * BH1750FVI full interface — all measurement modes, continuous mode, sensitivity, power control.
 *
 * Extends BH1750Minimal with raw codes, one-time measurements in each mode, continuous
 * measurement, power-down/power-on, reset, the measurement-time register (MTreg) and sensitivity
 * adjustment. `read()` uses the mode of the last `measureOnce()` or `startContinuous()` call
 * (H-resolution by default).
 */
class BH1750Full extends BH1750Minimal {
    /**
     * Run one one-time measurement in the current mode and return the raw count.
     * @returns {Promise<number>} 16-bit unsigned raw count.
     */
    async readRaw() {
        return this._measureRaw(this._mode);
    }

    /**
     * Run one one-time measurement.
     * @param {number} [mode=MODE_H] - MODE_H (1 lx), MODE_H2 (0.5 lx) or MODE_L (4 lx).
     * @returns {Promise<number>} Illuminance in lux.
     */
    async measureOnce(mode = MODE_H) {
        _checkMode(mode);
        this._mode = mode;
        return this._toLux(await this._measureRaw(mode), mode);
    }

    /**
     * Start continuous measurements; the first result is ready after `measurementTime(mode)`.
     * @param {number} [mode=MODE_H] - MODE_H (1 lx), MODE_H2 (0.5 lx) or MODE_L (4 lx).
     */
    async startContinuous(mode = MODE_H) {
        _checkMode(mode);
        this._mode = mode;
        await this._conn.write(Buffer.from([_OP_CONTINUOUS[mode]]));
    }

    /**
     * Read the latest continuous-mode result without waiting.
     * @returns {Promise<number>} Illuminance in lux.
     */
    async readContinuous() {
        return this._toLux(await this._readWord(), this._mode);
    }

    /** Enter power-down (opcode 0x00). */
    async powerDown() {
        await this._conn.write(Buffer.from([_OP_POWER_DOWN]));
    }

    /** Leave power-down and wait for a measurement command (opcode 0x01). */
    async powerOn() {
        await this._conn.write(Buffer.from([_OP_POWER_ON]));
    }

    /** Clear the illuminance data register (opcode 0x07; powers on first, as reset is ignored in power-down). */
    async reset() {
        await this._conn.write(Buffer.from([_OP_POWER_ON]));
        await this._conn.write(Buffer.from([_OP_RESET]));
    }

    /**
     * Write the measurement-time register (two opcodes, high bits then low bits).
     * @param {number} mtreg - Register value, 31-254 (default 69).
     * @throws {RangeError} If mtreg is outside 31-254.
     */
    async setMeasurementTime(mtreg) {
        if (!Number.isInteger(mtreg) || mtreg < 31 || mtreg > 254) throw new RangeError('BH1750 MTreg must be 31-254');
        await this._conn.write(Buffer.from([_OP_MT_HIGH | (mtreg >> 5)]));
        await this._conn.write(Buffer.from([_OP_MT_LOW | (mtreg & 0x1F)]));
        this._mtreg = mtreg;
    }

    /**
     * The last written MTreg (the chip has no read-back; 69 after init).
     * @returns {number}
     */
    getMeasurementTimeRegister() {
        return this._mtreg;
    }

    /**
     * Set the sensitivity relative to the default (MTreg = round(69 * factor)).
     * @param {number} factor - 0.45-3.68.
     * @throws {RangeError} If the resulting MTreg is outside 31-254.
     */
    async setSensitivity(factor) {
        await this.setMeasurementTime(Math.round(_DEFAULT_MTREG * factor));
    }

    /**
     * Maximum measurement time of a mode at the current MTreg.
     * @param {number} [mode=MODE_H]
     * @returns {number} Seconds.
     */
    measurementTime(mode = MODE_H) {
        _checkMode(mode);
        return this._waitMs(mode) / 1000.0;
    }

    /**
     * Illuminance represented by one raw count at the current MTreg.
     * @param {number} [mode=MODE_H]
     * @returns {number} Lux per count.
     */
    luxPerCount(mode = MODE_H) {
        _checkMode(mode);
        return this._toLux(1, mode);
    }
}

BH1750Minimal.MODE_H = MODE_H;
BH1750Minimal.MODE_H2 = MODE_H2;
BH1750Minimal.MODE_L = MODE_L;

module.exports = { BH1750Minimal, BH1750Full, MODE_H, MODE_H2, MODE_L };
