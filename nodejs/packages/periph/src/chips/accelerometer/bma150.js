'use strict';

const _REG_CHIP_ID          = 0x00;
const _REG_VERSION          = 0x01;
const _REG_ACC_X_LSB        = 0x02;
const _REG_ACC_X_MSB        = 0x03;
const _REG_ACC_Y_LSB        = 0x04;
const _REG_ACC_Y_MSB        = 0x05;
const _REG_ACC_Z_LSB        = 0x06;
const _REG_ACC_Z_MSB        = 0x07;
const _REG_TEMP             = 0x08;
const _REG_STATUS           = 0x09;
const _REG_CTRL             = 0x0A;
const _REG_INT_CTRL         = 0x0B;
const _REG_LG_THRES         = 0x0C;
const _REG_LG_DUR           = 0x0D;
const _REG_HG_THRES         = 0x0E;
const _REG_HG_DUR           = 0x0F;
const _REG_ANY_MOTION_THRES = 0x10;
const _REG_HYST_DUR         = 0x11;
const _REG_CUSTOMER_1       = 0x12;
const _REG_CUSTOMER_2       = 0x13;
const _REG_RANGE_BW         = 0x14;
const _REG_CONFIG           = 0x15;

const _CHIP_ID_VALUE = 0x02;
const _CHIP_ID_MASK  = 0x07;

const _RANGE_BITS = { 2: 0x00, 4: 0x08, 8: 0x10 };
const _BW_CODES = [25, 50, 100, 190, 375, 750, 1500].map((hz, i) => [hz, i]);
const _RANGE_SCALE = { 2: 256, 4: 128, 8: 64 };

const _WAKEUP_PAUSE_CODES = { 20: 0x00, 80: 0x02, 320: 0x04, 2560: 0x06 };
const _ANY_MOTION_DUR_CODES = { 1: 0x00, 3: 0x40, 5: 0x80, 7: 0xC0 };

function _nearestBandwidth(bwHz) {
    let best = _BW_CODES[0];
    let bestDiff = Math.abs(_BW_CODES[0][0] - bwHz);
    for (const entry of _BW_CODES) {
        const diff = Math.abs(entry[0] - bwHz);
        if (diff < bestDiff) {
            best = entry;
            bestDiff = diff;
        }
    }
    return best[1];
}

function _delayMs(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * BMA150 3-axis MEMS accelerometer (Bosch Sensortec) — minimal interface.
 *
 * Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
 * selectable full-scale range. Communicates over I²C at the fixed address
 * 0x38 (the chip also supports 3-/4-wire SPI; out of scope here).
 *
 * Default configuration (written at construction):
 * - Range ±2 *g* (256 LSB/g)
 * - Bandwidth 100 Hz
 * - Calibration bits 7:5 of `RANGE_BW` (0x14) preserved
 * - `shadow_dis` = 0 (LSB-then-MSB ordering enforced)
 *
 * Constructor caveat: JS constructors cannot be async, but the original
 * synchronous constructor validated CHIP_ID and threw synchronously on
 * mismatch — that guarantee cannot be preserved exactly. The whole init
 * sequence is fired off unawaited; a CHIP_ID mismatch now surfaces as an
 * *unhandled promise rejection* shortly after construction rather than a
 * synchronous throw from `new BMA150Minimal(...)`. Callers that need to
 * detect "wrong or absent device" deterministically should call an async
 * method (e.g. `await accel.read()`) right after construction and handle
 * its rejection.
 */
class BMA150Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._rangeG = 2;
        this._init();
    }

    async _init() {
        const chipId = await this._readReg(_REG_CHIP_ID);
        if ((chipId & _CHIP_ID_MASK) !== _CHIP_ID_VALUE) {
            throw new Error('BMA150 CHIP_ID: expected 0x' + _CHIP_ID_VALUE.toString(16) +
                            ', got 0x' + (chipId & _CHIP_ID_MASK).toString(16));
        }
        const bwCode = _nearestBandwidth(100);
        const rb = await this._readReg(_REG_RANGE_BW);
        const out = (rb & 0xE0) | _RANGE_BITS[2] | bwCode;
        await this._writeReg(_REG_RANGE_BW, out);
        _delayMs(5);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Reads all six LSB-then-MSB data bytes (0x02..0x07) in one burst so the
     * X, Y, Z samples are guaranteed to come from a single measurement with
     * `shadow_dis=0` enforcing correct ordering.
     *
     * @returns {Promise<number[]>} [x, y, z] acceleration in *g*.
     */
    async read() {
        const raw = await this._conn.readReg(_REG_ACC_X_LSB, 6);
        const rx = ((((raw[1] << 2) | (raw[0] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        const ry = ((((raw[3] << 2) | (raw[2] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        const rz = ((((raw[5] << 2) | (raw[4] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        const scale = _RANGE_SCALE[this._rangeG];
        return [rx / scale, ry / scale, rz / scale];
    }
}

/**
 * BMA150 full interface — extends BMA150Minimal with configuration,
 * interrupt sources, low-g / high-g / any-motion / alert logic, sleep,
 * soft reset, and self-test.
 *
 * Adds range (±2/±4/±8 *g*) and bandwidth (25..1500 Hz) selection, raw
 * reading, temperature, low-g (free-fall), high-g (shock), any-motion and
 * alert thresholds with duration, hysteresis and debounce counters,
 * latched or self-resetting interrupts, self-wake-up mode, sleep and soft
 * reset, electrostatic self-test, version register, and the two CUSTOMER
 * scratch bytes.
 */
class BMA150Full extends BMA150Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     */
    constructor(connection) {
        super(connection);
        this._enabledSources = 0;
        this._sleeping = false;
    }

    /**
     * Read 3-axis linear acceleration in *g*.
     *
     * Delegates to {@link BMA150Minimal#read}.
     * @returns {Promise<number[]>}
     */
    async read() {
        return super.read();
    }

    /** @param {number} rangeG - One of 2, 4, 8. */
    async setRange(rangeG) {
        if (!(rangeG in _RANGE_BITS)) {
            throw new Error('rangeG must be one of 2, 4, 8');
        }
        this._rangeG = rangeG;
        const rb = await this._readReg(_REG_RANGE_BW);
        const out = (rb & 0xE0) | _RANGE_BITS[rangeG] | (rb & 0x07);
        await this._writeReg(_REG_RANGE_BW, out);
    }

    /** @param {number} bandwidthHz - 25..1500 Hz. */
    async setBandwidth(bandwidthHz) {
        const bwCode = _nearestBandwidth(bandwidthHz);
        const rb = await this._readReg(_REG_RANGE_BW);
        const out = (rb & 0xF8) | bwCode;
        await this._writeReg(_REG_RANGE_BW, out);
    }

    /** @returns {Promise<number[]>} Signed 10-bit counts. */
    async readRaw() {
        const raw = await this._conn.readReg(_REG_ACC_X_LSB, 6);
        const rx = ((((raw[1] << 2) | (raw[0] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        const ry = ((((raw[3] << 2) | (raw[2] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        const rz = ((((raw[5] << 2) | (raw[4] >> 6)) & 0x3FF) + 512) % 1024 - 512;
        return [rx, ry, rz];
    }

    /** @returns {Promise<number>} Temperature in °C. */
    async readTemperature() {
        const raw = await this._readReg(_REG_TEMP);
        return raw * 0.5 - 30.0;
    }

    /** @returns {Promise<boolean>} True if all three new_data_X/Y/Z bits are set. */
    async newDataAvailable() {
        const x = await this._readReg(_REG_ACC_X_LSB);
        const y = await this._readReg(_REG_ACC_Y_LSB);
        const z = await this._readReg(_REG_ACC_Z_LSB);
        return (x & 0x01) && (y & 0x01) && (z & 0x01);
    }

    /** @param {boolean} enabled - True to allow MSB-only reads. */
    async setShadow(enabled) {
        const cfg = await this._readReg(_REG_CONFIG);
        const out = enabled ? (cfg | 0x08) : (cfg & ~0x08 & 0xFF);
        await this._writeReg(_REG_CONFIG, out);
    }

    async setLowG(thresholdG, durationMs, hysteresisG = 0, counter = 0) {
        await this._writeThreshold(_REG_LG_THRES, thresholdG);
        await this._writeReg(_REG_LG_DUR, Math.max(0, Math.min(255, Math.round(durationMs))) & 0xFF);
        await this._writeHyst('lg', hysteresisG);
        await this._writeIntCounter('lg', counter);
        await this._enableSource(BMA150Full.SOURCE_LOW_G);
    }

    async setHighG(thresholdG, durationMs, hysteresisG = 0, counter = 0) {
        await this._writeThreshold(_REG_HG_THRES, thresholdG);
        await this._writeReg(_REG_HG_DUR, Math.max(0, Math.min(255, Math.round(durationMs))) & 0xFF);
        await this._writeHyst('hg', hysteresisG);
        await this._writeIntCounter('hg', counter);
        await this._enableSource(BMA150Full.SOURCE_HIGH_G);
    }

    async setAnyMotion(thresholdG, samples = 1) {
        const scale = _RANGE_SCALE[this._rangeG] / 256.0;
        const code = Math.max(0, Math.min(255, Math.round(thresholdG / (0.0156 * scale))));
        await this._writeReg(_REG_ANY_MOTION_THRES, code);
        if (!(samples in _ANY_MOTION_DUR_CODES)) {
            throw new Error('samples must be 1, 3, 5, or 7');
        }
        const hd = await this._readReg(_REG_HYST_DUR);
        await this._writeReg(_REG_HYST_DUR, (hd & 0x3F) | _ANY_MOTION_DUR_CODES[samples]);
        const cfg = await this._readReg(_REG_CONFIG);
        await this._writeReg(_REG_CONFIG, cfg | 0x40);
        await this._enableSource(BMA150Full.SOURCE_ANY_MOTION);
    }

    async setAlert(enabled) {
        if (enabled) {
            this._enabledSources &= ~BMA150Full.SOURCE_ANY_MOTION;
            const cfg = await this._readReg(_REG_CONFIG);
            await this._writeReg(_REG_CONFIG, cfg | 0x40);
            await this._enableSource(BMA150Full.SOURCE_ALERT);
        } else {
            await this._disableSource(BMA150Full.SOURCE_ALERT);
        }
    }

    async setLatch(enabled) {
        const cfg = await this._readReg(_REG_CONFIG);
        const out = enabled ? (cfg | 0x10) : (cfg & ~0x10 & 0xFF);
        await this._writeReg(_REG_CONFIG, out);
    }

    async clearInterrupt() {
        if (this._sleeping) return;
        const ctrl = await this._readReg(_REG_CTRL);
        await this._writeReg(_REG_CTRL, ctrl | 0x40);
    }

    async enableInterrupt(source) {
        if (source === BMA150Full.SOURCE_NEW_DATA) {
            this._enabledSources &= 0x0F;
        } else {
            this._enabledSources &= ~BMA150Full.SOURCE_NEW_DATA;
            if (source === BMA150Full.SOURCE_ANY_MOTION) {
                this._enabledSources &= ~BMA150Full.SOURCE_ALERT;
            } else if (source === BMA150Full.SOURCE_ALERT) {
                this._enabledSources &= ~BMA150Full.SOURCE_ANY_MOTION;
            }
        }
        await this._enableSource(source);
    }

    async disableInterrupt(source) {
        await this._disableSource(source);
    }

    /** @returns {Promise<number>} STATUS byte. */
    async pollInterrupt() {
        return this._readReg(_REG_STATUS);
    }

    async setWakeUp(enabled, pauseMs = 20) {
        if (!(pauseMs in _WAKEUP_PAUSE_CODES)) {
            throw new Error('pauseMs must be 20, 80, 320, or 2560');
        }
        const cfg = await this._readReg(_REG_CONFIG);
        const out = (cfg & 0xF9) | _WAKEUP_PAUSE_CODES[pauseMs] | (enabled ? 0x01 : 0x00);
        await this._writeReg(_REG_CONFIG, out);
    }

    async sleep() {
        if (this._sleeping) return;
        const ctrl = await this._readReg(_REG_CTRL);
        await this._writeReg(_REG_CTRL, ctrl | 0x01);
        this._sleeping = true;
    }

    async wake() {
        if (!this._sleeping) return;
        const ctrl = await this._readReg(_REG_CTRL);
        await this._writeReg(_REG_CTRL, ctrl & ~0x01 & 0xFF);
        _delayMs(2);
        this._sleeping = false;
    }

    async softReset() {
        const ctrl = await this._readReg(_REG_CTRL);
        await this._writeReg(_REG_CTRL, ctrl | 0x02);
        _delayMs(30);
        const bwCode = _nearestBandwidth(100);
        const rb = await this._readReg(_REG_RANGE_BW);
        await this._writeReg(_REG_RANGE_BW, (rb & 0xE0) | _RANGE_BITS[this._rangeG] | bwCode);
        this._sleeping = false;
    }

    /** @returns {Promise<boolean>} True if self-test passed. */
    async selfTest() {
        const ctrl = await this._readReg(_REG_CTRL);
        await this._writeReg(_REG_CTRL, ctrl | 0x04);
        _delayMs(100);
        const status = await this._readReg(_REG_STATUS);
        await this._writeReg(_REG_CTRL, ctrl);
        return (status & 0x80) !== 0;
    }

    /** @returns {Promise<number>} STATUS byte. */
    async readStatus() {
        return this._readReg(_REG_STATUS);
    }

    /** @returns {Promise<[number, number]>} (al_version, ml_version) from VERSION. */
    async readVersion() {
        const raw = await this._readReg(_REG_VERSION);
        return [(raw >> 4) & 0x0F, raw & 0x0F];
    }

    /** @param {number} index - 0 or 1. */
    async readCustomer(index) {
        const reg = index === 0 ? _REG_CUSTOMER_1 : _REG_CUSTOMER_2;
        return this._readReg(reg);
    }

    async writeCustomer(index, value) {
        const reg = index === 0 ? _REG_CUSTOMER_1 : _REG_CUSTOMER_2;
        await this._writeReg(reg, value & 0xFF);
    }

    async _writeThreshold(reg, thresholdG) {
        let code = Math.round(thresholdG * 255.0 / this._rangeG);
        if (code < 0) code = 0;
        if (code > 255) code = 255;
        await this._writeReg(reg, code);
    }

    async _writeHyst(kind, hysteresisG) {
        if (hysteresisG < 0) return;
        let code = Math.round(hysteresisG * 255.0 / this._rangeG / 32.0);
        if (code < 0) code = 0;
        if (code > 7) code = 7;
        const hd = await this._readReg(_REG_HYST_DUR);
        const out = kind === 'lg'
            ? (hd & 0xF8) | code
            : (hd & 0xC7) | ((code & 0x07) << 3);
        await this._writeReg(_REG_HYST_DUR, out);
    }

    async _writeIntCounter(kind, counter) {
        if (counter < 0 || counter > 3) return;
        const code = (counter & 0x03) << 4;
        const ic = await this._readReg(_REG_INT_CTRL);
        const out = kind === 'lg'
            ? (ic & 0xF3) | code
            : (ic & 0xCF) | (code << 2);
        await this._writeReg(_REG_INT_CTRL, out);
    }

    async _enableSource(source) {
        if (this._sleeping) return;
        this._enabledSources |= source;
        if (source === BMA150Full.SOURCE_NEW_DATA) {
            const cfg = await this._readReg(_REG_CONFIG);
            await this._writeReg(_REG_CONFIG, cfg | 0x20);
            return;
        }
        const ic = await this._readReg(_REG_INT_CTRL);
        let out = ic;
        if (source === BMA150Full.SOURCE_LOW_G) out |= 0x01;
        if (source === BMA150Full.SOURCE_HIGH_G) out |= 0x02;
        if (source === BMA150Full.SOURCE_ANY_MOTION) out |= 0x40;
        if (source === BMA150Full.SOURCE_ALERT) out |= 0x80;
        await this._writeReg(_REG_INT_CTRL, out);
    }

    async _disableSource(source) {
        this._enabledSources &= ~source & 0xFF;
        if (source === BMA150Full.SOURCE_NEW_DATA) {
            const cfg = await this._readReg(_REG_CONFIG);
            await this._writeReg(_REG_CONFIG, cfg & ~0x20 & 0xFF);
            return;
        }
        const ic = await this._readReg(_REG_INT_CTRL);
        let out = ic;
        if (source === BMA150Full.SOURCE_LOW_G) out &= ~0x01 & 0xFF;
        if (source === BMA150Full.SOURCE_HIGH_G) out &= ~0x02 & 0xFF;
        if (source === BMA150Full.SOURCE_ANY_MOTION) out &= ~0x40 & 0xFF;
        if (source === BMA150Full.SOURCE_ALERT) out &= ~0x80 & 0xFF;
        await this._writeReg(_REG_INT_CTRL, out);
    }
}

BMA150Full.SOURCE_LOW_G     = 0x01;
BMA150Full.SOURCE_HIGH_G    = 0x02;
BMA150Full.SOURCE_ANY_MOTION = 0x04;
BMA150Full.SOURCE_ALERT     = 0x08;
BMA150Full.SOURCE_NEW_DATA  = 0x10;

module.exports = { BMA150Minimal, BMA150Full };
