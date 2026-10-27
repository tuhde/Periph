'use strict';

const { toSigned } = require('../../connection/register');

// ---------------------------------------------------------------------------
// Accelerometer registers
// ---------------------------------------------------------------------------
const _REG_CTRL_REG1 = 0x20;
const _REG_CTRL_REG2 = 0x21;
const _REG_CTRL_REG3 = 0x22;
const _REG_CTRL_REG4 = 0x23;
const _REG_CTRL_REG5 = 0x24;
const _REG_CTRL_REG6 = 0x25;
const _REG_REFERENCE = 0x26;
const _REG_STATUS = 0x27;
const _REG_OUT_X_L = 0x28;
const _REG_OUT_X_H = 0x29;
const _REG_OUT_Y_L = 0x2A;
const _REG_OUT_Y_H = 0x2B;
const _REG_OUT_Z_L = 0x2C;
const _REG_OUT_Z_H = 0x2D;
const _REG_FIFO_CTRL = 0x2E;
const _REG_FIFO_SRC = 0x2F;
const _REG_INT1_CFG = 0x30;
const _REG_INT1_SRC = 0x31;
const _REG_INT1_THS = 0x32;
const _REG_INT1_DUR = 0x33;
const _REG_INT2_CFG = 0x34;
const _REG_INT2_SRC = 0x35;
const _REG_INT2_THS = 0x36;
const _REG_INT2_DUR = 0x37;
const _REG_CLICK_CFG = 0x38;
const _REG_CLICK_SRC = 0x39;
const _REG_CLICK_THS = 0x3A;
const _REG_TIME_LIMIT = 0x3B;
const _REG_TIME_LATENCY = 0x3C;
const _REG_TIME_WINDOW = 0x3D;

// ---------------------------------------------------------------------------
// Magnetometer registers
// ---------------------------------------------------------------------------
const _MAG_REG_CRA = 0x00;
const _MAG_REG_CRB = 0x01;
const _MAG_REG_MR = 0x02;
const _MAG_REG_OUT_X_H = 0x03;
const _MAG_REG_OUT_X_L = 0x04;
const _MAG_REG_OUT_Z_H = 0x05;
const _MAG_REG_OUT_Z_L = 0x06;
const _MAG_REG_OUT_Y_H = 0x07;
const _MAG_REG_OUT_Y_L = 0x08;
const _MAG_REG_SR = 0x09;
const _MAG_REG_IRA = 0x0A;
const _MAG_REG_TEMP_H = 0x31;
const _MAG_REG_TEMP_L = 0x32;

const _MAG_ID = [0x48, 0x34, 0x33];

const _CTRL_REG4_DEFAULT = 0x88;
const _CTRL_REG1_DEFAULT = 0x57;

const _DEFAULT_RANGE_G = 2;
const _DEFAULT_RATE_HZ = 100;
const _DEFAULT_MODE = 2;

const _RANGES = [2, 4, 8, 16];
const _MODE_LP = 0;
const _MODE_NORMAL = 1;
const _MODE_HR = 2;

const _RATE_CODES = new Map([
    [0, 0x00], [1, 0x10], [10, 0x20], [25, 0x30], [50, 0x40], [100, 0x50],
    [200, 0x60], [400, 0x70], [1620, 0x80], [1344, 0x90], [5376, 0x90],
]);
const _LP_ONLY_RATES = [1620, 5376];
const _NON_LP_ONLY_RATES = [1344];

const _SENSITIVITY_MG = {
    [_MODE_HR]: {2: 1, 4: 2, 8: 4, 16: 12},
    [_MODE_NORMAL]: {2: 4, 4: 8, 8: 16, 16: 48},
    [_MODE_LP]: {2: 16, 4: 32, 8: 64, 16: 192},
};
const _SHIFT = {[_MODE_HR]: 4, [_MODE_NORMAL]: 6, [_MODE_LP]: 8};

const _MAG_GAINS = new Map([
    [0x20, {xy: 1100, z: 980, rangeUt: 130}],
    [0x40, {xy: 855, z: 760, rangeUt: 190}],
    [0x60, {xy: 670, z: 600, rangeUt: 250}],
    [0x80, {xy: 450, z: 400, rangeUt: 400}],
    [0xA0, {xy: 400, z: 355, rangeUt: 470}],
    [0xC0, {xy: 330, z: 295, rangeUt: 560}],
    [0xE0, {xy: 230, z: 205, rangeUt: 810}],
]);

function _magCodeForRange(rangeUt) {
    for (const [code, gain] of _MAG_GAINS) {
        if (gain.rangeUt === rangeUt) return code;
    }
    return 0x20;
}

const _MAG_RATE_CODES = new Map([
    [0.75, 0x00], [1.5, 0x04], [3, 0x08], [7.5, 0x0C],
    [15, 0x10], [30, 0x14], [75, 0x18], [220, 0x1C],
]);

function _round(x) {
    return x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5);
}

function _ceilMs(x) {
    return x > 0 ? Math.ceil(x) : 0;
}

function _sleep(ms) {
    if (!(ms > 0)) return Promise.resolve();
    return new Promise(resolve => setTimeout(resolve, ms));
}

function _hex2(value) {
    return '0x' + (value & 0xFF).toString(16).toUpperCase().padStart(2, '0');
}

/**
 * LSM303 accelerometer + magnetometer e-compass — minimal interface.
 *
 * The LSM303DLHC combines a three-axis accelerometer (0x19) and a
 * three-axis magnetometer (0x1E) behind two independent I²C slave
 * addresses on one bus. The accelerometer has four full-scale ranges,
 * left-justified 16-bit output, three operating modes, a 32-level FIFO, a
 * high-pass filter, two inertial interrupt generators and click detection.
 * The magnetometer has seven gain settings, 12-bit output, data rates from
 * 0.75 Hz to 220 Hz and a relative-only temperature sensor.
 *
 * Constructor caveat: JS constructors cannot be async, so the Initialization
 * Sequence starts in the constructor and every public method awaits it first.
 * Call `await compass.init()` right after construction to surface an identity
 * failure at a predictable point.
 */
class LSM303Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Configured I²C connection bound to 0x19.
     * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Configured I²C connection bound to 0x1E.
     */
    constructor(accelConnection, magConnection) {
        this._accel = accelConnection;
        this._mag = magConnection;
        this._rangeG = _DEFAULT_RANGE_G;
        this._mode = _DEFAULT_MODE;
        this._rateHz = _DEFAULT_RATE_HZ;
        this._magGainCode = 0x20;
        this._magRateHz = 15;
        this._ready = this._runInitSequence();
        this._ready.catch(() => {});
    }

    /** Wait for the Initialization Sequence. @returns {Promise<void>} */
    async init() {
        await this._ready;
    }

    /** @private */
    async _runInitSequence() {
        await _sleep(5);
        const id = await this._magReadBurst(_MAG_REG_IRA, 3);
        if (id[0] !== _MAG_ID[0] || id[1] !== _MAG_ID[1] || id[2] !== _MAG_ID[2]) {
            throw new Error('LSM303 magnetometer identity: expected 48 34 33, got ' +
                            _hex2(id[0]) + ' ' + _hex2(id[1]) + ' ' + _hex2(id[2]));
        }

        await this._writeReg(_REG_CTRL_REG2, 0x00);
        await this._writeReg(_REG_CTRL_REG3, 0x00);
        await this._writeReg(_REG_CTRL_REG5, 0x00);
        await this._writeReg(_REG_CTRL_REG6, 0x00);
        await this._writeReg(_REG_FIFO_CTRL, 0x00);
        await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT);
        await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);

        await this._magWriteReg(_MAG_REG_CRA, 0x10);
        await this._magWriteReg(_MAG_REG_CRB, this._magGainCode);
        await this._magWriteReg(_MAG_REG_MR, 0x00);

        await _sleep(Math.max(this._settleMs(), _ceilMs(1000 / this._magRateHz)));
    }

    /** @private */
    async _writeReg(reg, value) { await this._accel.writeReg(reg, value & 0xFF); }

    /** @private */
    async _readReg(reg) { return (await this._accel.readReg(reg, 1))[0]; }

    /** @private */
    async _readBurst(reg, length) {
        return this._accel.readReg(length > 1 ? (reg | 0x80) : reg, length);
    }

    /** @private */
    async _magWriteReg(reg, value) { await this._mag.writeReg(reg, value & 0xFF); }

    /** @private */
    async _magReadReg(reg) { return (await this._mag.readReg(reg, 1))[0]; }

    /** @private */
    async _magReadBurst(reg, length) { return this._mag.readReg(reg, length); }

    /** @private */
    _settleMs() {
        if (this._rateHz <= 0) return 0;
        const periodMs = 1000 / this._rateHz;
        let turnOnMs;
        if (this._mode === _MODE_LP) turnOnMs = 1;
        else if (this._mode === _MODE_NORMAL) turnOnMs = 1.6;
        else turnOnMs = 7 * periodMs;
        return _ceilMs(Math.max(turnOnMs, periodMs));
    }

    /** @private */
    _decodeAxis(low, high) {
        return toSigned(low | (high << 8), 16) >> _SHIFT[this._mode];
    }

    /** @private */
    _sensitivityMg() { return _SENSITIVITY_MG[this._mode][this._rangeG]; }

    /**
     * Read 3-axis linear acceleration.
     * @returns {Promise<{x: number, y: number, z: number}>} Acceleration in *g*.
     */
    async readAcceleration() {
        await this._ready;
        const raw = await this._readBurst(_REG_OUT_X_L, 6);
        const scale = this._sensitivityMg() / 1000;
        return {
            x: this._decodeAxis(raw[0], raw[1]) * scale,
            y: this._decodeAxis(raw[2], raw[3]) * scale,
            z: this._decodeAxis(raw[4], raw[5]) * scale,
        };
    }

    /**
     * Read 3-axis magnetic flux density.
     * @returns {Promise<{x: number, y: number, z: number}>} Flux density in µT.
     */
    async readMagnetic() {
        await this._ready;
        const raw = await this._magReadBurst(_MAG_REG_OUT_X_H, 6);
        const gain = _MAG_GAINS.get(this._magGainCode);
        const x = toSigned((raw[0] << 8) | raw[1], 16) / gain.xy * 100;
        const z = toSigned((raw[2] << 8) | raw[3], 16) / gain.z * 100;
        const y = toSigned((raw[4] << 8) | raw[5], 16) / gain.xy * 100;
        return {x, y, z};
    }
}

/** Interrupt source constants for the LSM303 Full driver. */
class LSM303Source {
    static SOURCE_DATA_READY = 0x0001;
    static SOURCE_WATERMARK = 0x0002;
    static SOURCE_OVERRUN = 0x0004;
    static SOURCE_IA1 = 0x0008;
    static SOURCE_IA2 = 0x0010;
    static SOURCE_CLICK = 0x0020;
    static SOURCE_BOOT = 0x0040;
    static SOURCE_MAG_DATA_READY = 0x0080;
}

/** LSM303 full interface — extends {@link LSM303Minimal}. */
class LSM303Full extends LSM303Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Bound to 0x19.
     * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Bound to 0x1E.
     */
    constructor(accelConnection, magConnection) {
        super(accelConnection, magConnection);
        this._enabled1 = 0;
        this._enabled2 = 0;
        this._intCallback = null;
        this._pollTimer = null;
    }

    /** Set the accelerometer full-scale range. @param {number} rangeG @returns {Promise<void>} */
    async setRange(rangeG) {
        await this._ready;
        if (!_RANGES.includes(rangeG)) throw new Error('rangeG must be one of 2, 4, 8, 16');
        const fs = _RANGES.indexOf(rangeG) << 4;
        const ctrl4 = await this._readReg(_REG_CTRL_REG4);
        await this._writeReg(_REG_CTRL_REG4, (ctrl4 & ~0x30 & 0xFF) | fs);
        this._rangeG = rangeG;
    }

    /** Set the accelerometer output data rate (or power down). @param {number} rateHz @returns {Promise<void>} */
    async setDataRate(rateHz) {
        await this._ready;
        const code = _RATE_CODES.get(rateHz);
        if (code === undefined) throw new Error('rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376');
        if (_LP_ONLY_RATES.includes(rateHz) && this._mode !== _MODE_LP) throw new Error('rate ' + rateHz + ' Hz is low-power mode only');
        if (_NON_LP_ONLY_RATES.includes(rateHz) && this._mode === _MODE_LP) throw new Error('rate ' + rateHz + ' Hz is not available in low-power mode');
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        await this._writeReg(_REG_CTRL_REG1, (ctrl1 & 0x0F) | code);
        this._rateHz = rateHz;
        await _sleep(this._settleMs());
    }

    /** Set the accelerometer operating mode. @param {number} mode @returns {Promise<void>} */
    async setMode(mode) {
        await this._ready;
        if (mode !== _MODE_LP && mode !== _MODE_NORMAL && mode !== _MODE_HR) throw new Error('mode must be 0, 1 or 2');
        if (mode === _MODE_LP && _NON_LP_ONLY_RATES.includes(this._rateHz)) throw new Error('rate ' + this._rateHz + ' Hz is not available in low-power mode');
        if (mode !== _MODE_LP && _LP_ONLY_RATES.includes(this._rateHz)) throw new Error('rate ' + this._rateHz + ' Hz is low-power mode only');
        let ctrl1 = await this._readReg(_REG_CTRL_REG1);
        let ctrl4 = await this._readReg(_REG_CTRL_REG4);
        if (mode === _MODE_LP) {
            ctrl1 |= 0x08;
            ctrl4 &= ~0x08 & 0xFF;
        } else {
            ctrl1 &= ~0x08 & 0xFF;
            if (mode === _MODE_HR) ctrl4 |= 0x08;
            else ctrl4 &= ~0x08 & 0xFF;
        }
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        await this._writeReg(_REG_CTRL_REG4, ctrl4);
        this._mode = mode;
        await _sleep(this._settleMs());
    }

    /** Enable or disable the accelerometer axes. @returns {Promise<void>} */
    async setAxes(x = true, y = true, z = true) {
        await this._ready;
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        const axes = (x ? 1 : 0) | ((y ? 1 : 0) << 1) | ((z ? 1 : 0) << 2);
        await this._writeReg(_REG_CTRL_REG1, (ctrl1 & 0xF8) | axes);
    }

    /** Stop accelerometer conversions. @returns {Promise<void>} */
    async powerDown() {
        await this._ready;
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        await this._writeReg(_REG_CTRL_REG1, ctrl1 & 0x0F);
        this._rateHz = 0;
    }

    /** Read raw acceleration counts. @returns {Promise<{x: number, y: number, z: number}>} */
    async readAccelerationRaw() {
        await this._ready;
        const raw = await this._readBurst(_REG_OUT_X_L, 6);
        return {
            x: this._decodeAxis(raw[0], raw[1]),
            y: this._decodeAxis(raw[2], raw[3]),
            z: this._decodeAxis(raw[4], raw[5]),
        };
    }

    /** @returns {Promise<boolean>} True when STATUS_REG_A.ZYXDA is set. */
    async newDataAvailable() {
        await this._ready;
        return ((await this._readReg(_REG_STATUS)) & 0x08) !== 0;
    }

    /** @returns {Promise<number>} STATUS_REG_A. */
    async readStatus() { await this._ready; return this._readReg(_REG_STATUS); }

    /** Configure the high-pass filter. @returns {Promise<void>} */
    async setHighPass(mode = 0, cutoff = 0, filterOutput = false, onIa1 = false, onIa2 = false, onClick = false) {
        await this._ready;
        if (mode < 0 || mode > 3) throw new Error('mode must be 0-3');
        if (cutoff < 0 || cutoff > 3) throw new Error('cutoff must be 0-3');
        let value = (mode << 6) | (cutoff << 4);
        if (filterOutput) value |= 0x08;
        if (onClick) value |= 0x04;
        if (onIa2) value |= 0x02;
        if (onIa1) value |= 0x01;
        await this._writeReg(_REG_CTRL_REG2, value);
    }

    /** Reset the high-pass filter. @returns {Promise<number>} */
    async resetHighPass() { await this._ready; return this._readReg(_REG_REFERENCE); }

    /** Write the high-pass reference. @returns {Promise<void>} */
    async setReference(value) { await this._ready; await this._writeReg(_REG_REFERENCE, value & 0xFF); }

    /** Configure the FIFO. @returns {Promise<void>} */
    async setFifo(mode, watermark = 0, triggerInt2 = false) {
        await this._ready;
        if (mode < 0 || mode > 3) throw new Error('mode must be 0-3');
        if (watermark < 0 || watermark > 31) throw new Error('watermark must be 0-31');
        const value = (mode << 6) | (triggerInt2 ? 0x20 : 0) | watermark;
        await this._writeReg(_REG_FIFO_CTRL, value);
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, mode === 0 ? (ctrl5 & ~0x40 & 0xFF) : (ctrl5 | 0x40));
    }

    /** @returns {Promise<number>} FIFO level. */
    async fifoLevel() { await this._ready; return (await this._readReg(_REG_FIFO_SRC)) & 0x1F; }

    /** Drain FIFO samples. @returns {Promise<Array<{x: number, y: number, z: number}>>} */
    async readFifo(count = null) {
        await this._ready;
        const level = await this.fifoLevel();
        const n = count === null ? level : Math.min(count, level);
        const scale = this._sensitivityMg() / 1000;
        const samples = [];
        for (let i = 0; i < n; i++) {
            const raw = await this._readBurst(_REG_OUT_X_L, 6);
            samples.push({
                x: this._decodeAxis(raw[0], raw[1]) * scale,
                y: this._decodeAxis(raw[2], raw[3]) * scale,
                z: this._decodeAxis(raw[4], raw[5]) * scale,
            });
        }
        return samples;
    }

    /** @returns {Promise<{watermark: boolean, overrun: boolean, empty: boolean, level: number}>} */
    async readFifoStatus() {
        await this._ready;
        const raw = await this._readReg(_REG_FIFO_SRC);
        return {watermark: (raw & 0x80) !== 0, overrun: (raw & 0x40) !== 0,
                empty: (raw & 0x20) !== 0, level: raw & 0x1F};
    }

    /** @private */
    _generatorRegs(generator) {
        if (generator === 1) return {cfg: _REG_INT1_CFG, ths: _REG_INT1_THS, dur: _REG_INT1_DUR};
        if (generator === 2) return {cfg: _REG_INT2_CFG, ths: _REG_INT2_THS, dur: _REG_INT2_DUR};
        throw new Error('generator must be 1 or 2');
    }

    /** @private */
    _thresholdCode(thresholdG) { return Math.max(0, Math.min(127, _round(thresholdG / (this._rangeG / 128)))); }

    /** @private */
    _durationCode(durationMs, maximum = 127) {
        if (durationMs <= 0 || this._rateHz <= 0) return 0;
        return Math.max(0, Math.min(maximum, _round(durationMs * this._rateHz / 1000)));
    }

    /** Configure an inertial interrupt generator. @returns {Promise<void>} */
    async setMotionInterrupt(generator, events, thresholdG, durationMs = 0, andMode = false) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        if (events < 0 || events > 0x3F) throw new Error('events must be a 6-bit mask (0-0x3F)');
        await this._writeReg(regs.cfg, (andMode ? 0x80 : 0x00) | (events & 0x3F));
        await this._writeReg(regs.ths, this._thresholdCode(thresholdG));
        await this._writeReg(regs.dur, this._durationCode(durationMs));
    }

    /** Configure the free-fall detector. @returns {Promise<void>} */
    async setFreeFall(generator = 1, thresholdG = 0.35, durationMs = 30) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        await this._writeReg(regs.cfg, 0x95);
        await this._writeReg(regs.ths, this._thresholdCode(thresholdG));
        await this._writeReg(regs.dur, this._durationCode(durationMs));
    }

    /** Configure 6D / 4D orientation detection. @returns {Promise<void>} */
    async setOrientation(generator = 1, position = false, fourD = false) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        await this._writeReg(regs.cfg, (position ? 0xC0 : 0x40) | (fourD ? 0x0F : 0x3F));
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        const bit = generator === 1 ? 0x04 : 0x01;
        await this._writeReg(_REG_CTRL_REG5, fourD ? (ctrl5 | bit) : (ctrl5 & ~bit & 0xFF));
    }

    /** Latch a generator's interrupt. @returns {Promise<void>} */
    async setLatch(generator, enabled) {
        await this._ready;
        if (generator !== 1 && generator !== 2) throw new Error('generator must be 1 or 2');
        const bit = generator === 1 ? 0x08 : 0x02;
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, enabled ? (ctrl5 | bit) : (ctrl5 & ~bit & 0xFF));
    }

    /** Read a generator's source register — clears the interrupt. @returns {Promise<number>} */
    async readInterruptSource(generator) {
        await this._ready;
        if (generator === 1) return this._readReg(_REG_INT1_SRC);
        if (generator === 2) return this._readReg(_REG_INT2_SRC);
        throw new Error('generator must be 1 or 2');
    }

    /** Configure single / double click detection. @returns {Promise<void>} */
    async setClick(single = 0, doubleClick = 0, thresholdG = 0.5, timeLimitMs = 10, latencyMs = 20, windowMs = 50) {
        await this._ready;
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) throw new Error('single / double must be axis masks (0-7)');
        const cfg = ((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                     ((doubleClick & 0x01) << 1) | ((doubleClick & 0x02) << 2) | ((doubleClick & 0x04) << 3));
        await this._writeReg(_REG_CLICK_CFG, cfg);
        await this._writeReg(_REG_CLICK_THS, this._thresholdCode(thresholdG));
        await this._writeReg(_REG_TIME_LIMIT, this._durationCode(timeLimitMs, 127));
        await this._writeReg(_REG_TIME_LATENCY, this._durationCode(latencyMs, 255));
        await this._writeReg(_REG_TIME_WINDOW, this._durationCode(windowMs, 255));
    }

    /** Read CLICK_SRC_A — clears the interrupt. @returns {Promise<number>} */
    async readClickSource() { await this._ready; return this._readReg(_REG_CLICK_SRC); }

    /** Set the shared INT1 / INT2 polarity. @returns {Promise<void>} */
    async setInterruptPolarity(activeLow) {
        await this._ready;
        const ctrl6 = await this._readReg(_REG_CTRL_REG6);
        await this._writeReg(_REG_CTRL_REG6, activeLow ? (ctrl6 | 0x02) : (ctrl6 & ~0x02 & 0xFF));
    }

    /** @private */
    _sourceBit(source) {
        const map = {
            [LSM303Source.SOURCE_DATA_READY]: 0, [LSM303Source.SOURCE_WATERMARK]: 1,
            [LSM303Source.SOURCE_OVERRUN]: 2, [LSM303Source.SOURCE_IA1]: 3,
            [LSM303Source.SOURCE_IA2]: 4, [LSM303Source.SOURCE_CLICK]: 5,
            [LSM303Source.SOURCE_BOOT]: 6, [LSM303Source.SOURCE_MAG_DATA_READY]: 7,
        };
        return map[source];
    }

    /** @private */
    _route(source, pin) {
        if (pin !== 1 && pin !== 2) throw new Error('pin must be 1 or 2');
        switch (source) {
            case LSM303Source.SOURCE_DATA_READY: return {reg: _REG_CTRL_REG3, mask: 0x10, track: 1};
            case LSM303Source.SOURCE_WATERMARK: return {reg: _REG_CTRL_REG3, mask: 0x04, track: 1};
            case LSM303Source.SOURCE_OVERRUN: return {reg: _REG_CTRL_REG3, mask: 0x02, track: 1};
            case LSM303Source.SOURCE_IA1: return {reg: pin === 2 ? _REG_CTRL_REG6 : _REG_CTRL_REG3, mask: 0x40, track: pin};
            case LSM303Source.SOURCE_IA2: return {reg: pin === 2 ? _REG_CTRL_REG6 : _REG_CTRL_REG3, mask: 0x20, track: pin};
            case LSM303Source.SOURCE_CLICK: return {reg: pin === 2 ? _REG_CTRL_REG6 : _REG_CTRL_REG3, mask: 0x80, track: pin};
            case LSM303Source.SOURCE_BOOT: return {reg: _REG_CTRL_REG6, mask: 0x10, track: 2};
            default: throw new Error('unknown interrupt source ' + source);
        }
    }

    /** Route one interrupt source to INT pin. @returns {Promise<void>} */
    async enableInterrupt(source, pin = 1) {
        await this._ready;
        if (source === LSM303Source.SOURCE_MAG_DATA_READY) throw new Error('magnetometer DRDY cannot be routed');
        const route = this._route(source, pin);
        await this._writeReg(route.reg, (await this._readReg(route.reg)) | route.mask);
        if (route.track === 1) this._enabled1 |= 1 << this._sourceBit(source);
        else this._enabled2 |= 1 << this._sourceBit(source);
    }

    /** Remove one interrupt source from INT pin. @returns {Promise<void>} */
    async disableInterrupt(source, pin = 1) {
        await this._ready;
        if (source === LSM303Source.SOURCE_MAG_DATA_READY) throw new Error('magnetometer DRDY cannot be routed');
        const route = this._route(source, pin);
        await this._writeReg(route.reg, (await this._readReg(route.reg)) & ~route.mask & 0xFF);
        if (route.track === 1) this._enabled1 &= ~(1 << this._sourceBit(source));
        else this._enabled2 &= ~(1 << this._sourceBit(source));
    }

    /** Read every enabled source and return the combined status. @returns {Promise<number>} */
    async pollInterrupt() {
        await this._ready;
        const enabled = this._enabled1 | this._enabled2;
        let status = 0;
        if ((enabled & 0x01) && ((await this._readReg(_REG_STATUS)) & 0x08)) status |= LSM303Source.SOURCE_DATA_READY;
        if (enabled & 0x06) {
            const fifo = await this._readReg(_REG_FIFO_SRC);
            if ((enabled & 0x02) && (fifo & 0x80)) status |= LSM303Source.SOURCE_WATERMARK;
            if ((enabled & 0x04) && (fifo & 0x40)) status |= LSM303Source.SOURCE_OVERRUN;
        }
        if ((enabled & 0x08) && ((await this._readReg(_REG_INT1_SRC)) & 0x40)) status |= LSM303Source.SOURCE_IA1;
        if ((enabled & 0x10) && ((await this._readReg(_REG_INT2_SRC)) & 0x40)) status |= LSM303Source.SOURCE_IA2;
        if ((enabled & 0x20) && ((await this._readReg(_REG_CLICK_SRC)) & 0x40)) status |= LSM303Source.SOURCE_CLICK;
        if ((await this._magReadReg(_MAG_REG_SR)) & 0x01) status |= LSM303Source.SOURCE_MAG_DATA_READY;
        return status;
    }

    /** Subscribe to an INT pin. @returns {Promise<void>} */
    async onInterrupt(callback, pin = 1) {
        await this._ready;
        if (pin !== 1 && pin !== 2) throw new Error('pin must be 1 or 2');
        this._intCallback = callback;
        const intPin = this._accel.intPin;
        if (intPin && typeof intPin.onEdge === 'function') {
            intPin.onEdge(async () => {
                const status = await this.pollInterrupt();
                if (status && this._intCallback) this._intCallback(status);
            });
        } else {
            this._pollTimer = setInterval(async () => {
                const status = await this.pollInterrupt();
                if (status && this._intCallback) this._intCallback(status);
            }, 5);
        }
    }

    /** Unsubscribe from an INT pin. @returns {Promise<void>} */
    async offInterrupt() {
        this._intCallback = null;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    }

    /** Set the magnetometer gain / full-scale range. @returns {Promise<void>} */
    async setMagRange(rangeUt) {
        await this._ready;
        const code = _magCodeForRange(rangeUt);
        if (!_MAG_GAINS.has(code) || _MAG_GAINS.get(code).rangeUt !== rangeUt) {
            throw new Error('rangeUt must be one of 130, 190, 250, 400, 470, 560, 810');
        }
        this._magGainCode = code;
        await this._magWriteReg(_MAG_REG_CRB, code);
    }

    /** Set the magnetometer output data rate. @returns {Promise<void>} */
    async setMagDataRate(rateHz) {
        await this._ready;
        const code = _MAG_RATE_CODES.get(rateHz);
        if (code === undefined) throw new Error('rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220');
        const cra = await this._magReadReg(_MAG_REG_CRA);
        await this._magWriteReg(_MAG_REG_CRA, (cra & 0xE3) | code);
        this._magRateHz = rateHz;
    }

    /** Set the magnetometer operating mode. @returns {Promise<void>} */
    async setMagMode(mode) {
        await this._ready;
        if (mode < 0 || mode > 2) throw new Error('mode must be 0 (continuous), 1 (single) or 2 (sleep)');
        const code = mode === 0 ? 0x00 : (mode === 1 ? 0x01 : 0x03);
        await this._magWriteReg(_MAG_REG_MR, code);
        if (mode === 1) await _sleep(_ceilMs(1000 / this._magRateHz));
    }

    /** Read raw magnetometer counts, reordered to X, Y, Z. @returns {Promise<{x: number, y: number, z: number}>} */
    async readMagneticRaw() {
        await this._ready;
        const raw = await this._magReadBurst(_MAG_REG_OUT_X_H, 6);
        return {
            x: toSigned((raw[0] << 8) | raw[1], 16),
            z: toSigned((raw[2] << 8) | raw[3], 16),
            y: toSigned((raw[4] << 8) | raw[5], 16),
        };
    }

    /** @returns {Promise<boolean>} True when SR_REG_M.DRDY is set. */
    async magDataReady() { await this._ready; return ((await this._magReadReg(_MAG_REG_SR)) & 0x01) !== 0; }

    /** @returns {Promise<{drdy: boolean, lock: boolean}>} */
    async readMagStatus() {
        await this._ready;
        const raw = await this._magReadReg(_MAG_REG_SR);
        return {drdy: (raw & 0x01) !== 0, lock: (raw & 0x02) !== 0};
    }

    /** @returns {Promise<Buffer>} IRA/IRB/IRC. */
    async readMagIdentity() { await this._ready; return this._magReadBurst(_MAG_REG_IRA, 3); }

    /** Enable or disable the temperature sensor. @returns {Promise<void>} */
    async enableTemperature(enabled) {
        await this._ready;
        const cra = await this._magReadReg(_MAG_REG_CRA);
        await this._magWriteReg(_MAG_REG_CRA, enabled ? (cra | 0x80) : (cra & ~0x80 & 0xFF));
    }

    /** Read the relative temperature. @returns {Promise<number>} °C. */
    async readTemperatureDelta() {
        await this._ready;
        if (!((await this._magReadReg(_MAG_REG_CRA)) & 0x80)) {
            await this.enableTemperature(true);
            await _sleep(_ceilMs(1000 / this._magRateHz));
        }
        const raw = toSigned(((await this._magReadReg(_MAG_REG_TEMP_H)) << 8) |
                             (await this._magReadReg(_MAG_REG_TEMP_L)), 16);
        return (raw >> 4) / 8;
    }

    /** Compute the compass heading. @returns {Promise<number>} Degrees 0-360. */
    async heading(tiltCompensate = true) {
        await this._ready;
        const m = await this.readMagnetic();
        let angle;
        if (tiltCompensate) {
            const a = await this.readAcceleration();
            const roll = Math.atan2(a.y, a.z);
            const pitch = Math.atan2(-a.x, a.y * Math.sin(roll) + a.z * Math.cos(roll));
            const xh = m.x * Math.cos(pitch) + m.z * Math.sin(pitch);
            const yh = m.x * Math.sin(roll) * Math.sin(pitch) + m.y * Math.cos(roll) -
                       m.z * Math.sin(roll) * Math.cos(pitch);
            angle = Math.atan2(yh, xh);
        } else {
            angle = Math.atan2(m.y, m.x);
        }
        let degrees = angle * 180 / Math.PI;
        if (degrees < 0) degrees += 360;
        return degrees;
    }

    /** Reboot the accelerometer memory and re-run the init sequence. @returns {Promise<void>} */
    async reboot() {
        await this._ready;
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, ctrl5 | 0x80);
        await _sleep(5);
        this._rangeG = _DEFAULT_RANGE_G;
        this._mode = _DEFAULT_MODE;
        this._rateHz = _DEFAULT_RATE_HZ;
        this._enabled1 = 0;
        this._enabled2 = 0;
        this._ready = this._runInitSequence();
        this._ready.catch(() => {});
    }
}

module.exports = { LSM303Minimal, LSM303Full, LSM303Source };
