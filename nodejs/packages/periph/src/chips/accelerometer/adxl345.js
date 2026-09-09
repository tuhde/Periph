'use strict';

const _REG_DEVID          = 0x00;
const _REG_THRESH_TAP     = 0x1D;
const _REG_OFSX           = 0x1E;
const _REG_OFSY           = 0x1F;
const _REG_OFSZ           = 0x20;
const _REG_DUR            = 0x21;
const _REG_LATENT         = 0x22;
const _REG_WINDOW         = 0x23;
const _REG_THRESH_ACT     = 0x24;
const _REG_THRESH_INACT   = 0x25;
const _REG_TIME_INACT     = 0x26;
const _REG_ACT_INACT_CTL  = 0x27;
const _REG_THRESH_FF      = 0x28;
const _REG_TIME_FF        = 0x29;
const _REG_TAP_AXES       = 0x2A;
const _REG_BW_RATE        = 0x2C;
const _REG_POWER_CTL      = 0x2D;
const _REG_INT_ENABLE     = 0x2E;
const _REG_INT_MAP        = 0x2F;
const _REG_INT_SOURCE     = 0x30;
const _REG_DATA_FORMAT    = 0x31;
const _REG_DATAX0         = 0x32;
const _REG_FIFO_CTL       = 0x38;
const _REG_FIFO_STATUS    = 0x39;

const _DEVID_VALUE        = 0xE5;
const _DATA_FORMAT_DEFAULT = 0x08;
const _BW_RATE_DEFAULT     = 0x0A;
const _POWER_CTL_DEFAULT   = 0x08;
const _FULL_RES_SCALE_G_PER_LSB = 3.9e-3;

const _RATE_CODES = [
    [0x0F, 3200.0],
    [0x0E, 1600.0],
    [0x0D, 800.0],
    [0x0C, 400.0],
    [0x0B, 200.0],
    [0x0A, 100.0],
    [0x09, 50.0],
    [0x08, 25.0],
    [0x07, 12.5],
    [0x06, 6.25],
];

function _delayMs(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * ADXL345 3-axis MEMS accelerometer (Analog Devices) — minimal interface.
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. Supports both I²C and SPI.
 *
 * Default configuration (written at construction):
 * - Full-resolution mode (3.9 mg/LSB at any range)
 * - ±2 g measurement range
 * - 100 Hz output data rate, normal power
 * - FIFO bypass, all interrupts disabled, no offsets
 *
 * Constructor caveat: JS constructors cannot be async, but the original
 * synchronous constructor validated DEVID and threw synchronously on
 * mismatch — that guarantee cannot be preserved exactly. The whole init
 * sequence is fired off unawaited; a DEVID mismatch now surfaces as an
 * *unhandled promise rejection* shortly after construction rather than a
 * synchronous throw from `new ADXL345Minimal(...)`. Callers that need to
 * detect "wrong or absent device" deterministically should call an async
 * method (e.g. `await accel.read()`) right after construction and handle
 * its rejection.
 */
class ADXL345Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
     */
    constructor(connection, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._rangeBits = 0;
        this._fullRes = true;
        this._init();
    }

    async _init() {
        await this._writeReg(_REG_DATA_FORMAT, _DATA_FORMAT_DEFAULT);
        await this._writeReg(_REG_BW_RATE, _BW_RATE_DEFAULT);
        await this._writeReg(_REG_POWER_CTL, _POWER_CTL_DEFAULT);
        const devid = await this._readReg(_REG_DEVID);
        if (devid !== _DEVID_VALUE) {
            throw new Error('ADXL345 DEVID: expected 0x' + _DEVID_VALUE.toString(16) +
                            ', got 0x' + devid.toString(16));
        }
        _delayMs(11);
    }

    _cmdByte(reg, read, multi) {
        let addr = reg & 0x3F;
        if (multi) addr |= 0x40;
        if (read)  addr |= 0x80;
        return addr;
    }

    async _writeReg(reg, value) {
        if (this._busType === 'spi') {
            const cmd = this._cmdByte(reg, false, false);
            await this._conn.write(Buffer.from([cmd, value & 0xFF]));
        } else {
            await this._conn.write(Buffer.from([reg & 0xFF, value & 0xFF]));
        }
    }

    async _readReg(reg) {
        if (this._busType === 'spi') {
            const cmd = this._cmdByte(reg, true, false);
            return (await this._conn.writeRead(Buffer.from([cmd]), 1))[0];
        }
        return (await this._conn.writeRead(Buffer.from([reg & 0xFF]), 1))[0];
    }

    async _readBurst(reg, n) {
        if (this._busType === 'spi') {
            const cmd = this._cmdByte(reg, true, n > 1);
            return this._conn.writeRead(Buffer.from([cmd]), n);
        }
        return this._conn.writeRead(Buffer.from([reg & 0xFF]), n);
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Reads all six data bytes (DATAX0..DATAZ1) in one burst so the X, Y,
     * Z samples are guaranteed to come from a single measurement.
     *
     * @returns {Promise<number[]>} [x, y, z] acceleration in *g*.
     */
    async read() {
        const raw = await this._readBurst(_REG_DATAX0, 6);
        const rx = raw.readInt16LE(0);
        const ry = raw.readInt16LE(2);
        const rz = raw.readInt16LE(4);
        const scale = this._fullRes
            ? _FULL_RES_SCALE_G_PER_LSB
            : [3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3][this._rangeBits & 0x03];
        return [rx * scale, ry * scale, rz * scale];
    }
}

/**
 * ADXL345 full interface — extends ADXL345Minimal with configuration, FIFO,
 * tap / activity / inactivity / free-fall detection, and interrupt routing.
 *
 * Adds range and data-rate selection, low-power mode, self-test,
 * per-axis offset calibration (in *g*), single/double-tap detection,
 * activity and inactivity detection, free-fall detection, 32-level FIFO,
 * interrupt routing (INT1 / INT2), and sleep / auto-sleep / link mode.
 */
class ADXL345Full extends ADXL345Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
     */
    constructor(connection, busType = 'i2c') {
        super(connection, busType);
    }

    /**
     * Set the measurement range to ±2/±4/±8/±16 g.
     * @param {number} rangeG - One of 2, 4, 8, 16. FULL_RES is preserved.
     * @returns {Promise<void>}
     */
    async setRange(rangeG) {
        const code = {2: 0, 4: 1, 8: 2, 16: 3}[rangeG];
        if (code === undefined) {
            throw new Error('rangeG must be one of 2, 4, 8, 16');
        }
        this._rangeBits = code;
        let df = await this._readReg(_REG_DATA_FORMAT);
        df = (df & ~0x03) | (code & 0x03);
        if (this._fullRes) df |= 0x08;
        await this._writeReg(_REG_DATA_FORMAT, df);
    }

    /**
     * Set the output data rate to the nearest supported value.
     * @param {number} rateHz - Requested ODR in Hz.
     * @returns {Promise<void>}
     */
    async setDataRate(rateHz) {
        let bestCode = _RATE_CODES[_RATE_CODES.length - 1][0];
        let bestRate = _RATE_CODES[_RATE_CODES.length - 1][1];
        for (const [code, actual] of _RATE_CODES) {
            if (Math.abs(actual - rateHz) < Math.abs(bestRate - rateHz)) {
                bestCode = code; bestRate = actual;
            }
        }
        let bw = await this._readReg(_REG_BW_RATE);
        bw = (bw & ~0x0F) | (bestCode & 0x0F);
        await this._writeReg(_REG_BW_RATE, bw);
    }

    /**
     * Enable or disable low-power mode (higher noise).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setLowPower(enabled) {
        let bw = await this._readReg(_REG_BW_RATE);
        if (enabled) bw |= 0x10;
        else         bw &= ~0x10;
        await this._writeReg(_REG_BW_RATE, bw);
    }

    /**
     * Set per-axis offset in *g*.
     * @param {number} x - X offset in *g*.
     * @param {number} y - Y offset in *g*.
     * @param {number} z - Z offset in *g*.
     * @returns {Promise<void>}
     */
    async setOffset(x, y, z) {
        await this._writeReg(_REG_OFSX, this._encodeOffset(x));
        await this._writeReg(_REG_OFSY, this._encodeOffset(y));
        await this._writeReg(_REG_OFSZ, this._encodeOffset(z));
    }

    _encodeOffset(offsetG) {
        let raw = Math.round(offsetG / 15.6e-3);
        if (raw >  127) raw =  127;
        if (raw < -128) raw = -128;
        return raw & 0xFF;
    }

    /**
     * Measure and write per-axis offsets to null sensor bias.
     *
     * @param {number} [targetX=0] - Expected X reading during calibration (in *g*).
     * @param {number} [targetY=0] - Expected Y reading during calibration (in *g*).
     * @param {number} [targetZ=1] - Expected Z reading during calibration (in *g*).
     * @param {number} [samples=128] - Number of samples to average.
     * @returns {Promise<void>}
     */
    async calibrateOffset(targetX = 0.0, targetY = 0.0, targetZ = 1.0, samples = 128) {
        let sx = 0, sy = 0, sz = 0;
        for (let i = 0; i < samples; i++) {
            const [x, y, z] = await this.read();
            sx += x; sy += y; sz += z;
            _delayMs(11);
        }
        sx /= samples; sy /= samples; sz /= samples;
        await this.setOffset(targetX - sx, targetY - sy, targetZ - sz);
    }

    /**
     * Configure single-tap detection and enable the SINGLE_TAP interrupt.
     * @param {number} thresholdG - Tap threshold in *g* (62.5 mg/LSB).
     * @param {number} durationMs - Maximum tap duration in ms (625 µs/LSB).
     * @param {number} [axes=0x07] - Bitmask of participating axes.
     * @param {boolean} [suppress=false] - Suppress double-tap if acceleration persists.
     * @returns {Promise<void>}
     */
    async setTapDetection(thresholdG, durationMs, axes = 0x07, suppress = false) {
        await this._writeReg(_REG_THRESH_TAP, Math.round(thresholdG / 62.5e-3) & 0xFF);
        await this._writeReg(_REG_DUR, Math.round(durationMs / 0.625) & 0xFF);
        const tapAxes = (axes & 0x07) | (suppress ? 0x08 : 0x00);
        await this._writeReg(_REG_TAP_AXES, tapAxes);
        await this._enableInterrupt(ADXL345Full.INT_SINGLE_TAP);
    }

    /**
     * Configure double-tap latency and window; enable DOUBLE_TAP interrupt.
     * @param {number} latencyMs - Time after a tap when a second tap is expected (1.25 ms/LSB).
     * @param {number} windowMs  - Time window after latency for the second tap (1.25 ms/LSB).
     * @returns {Promise<void>}
     */
    async setDoubleTap(latencyMs, windowMs) {
        await this._writeReg(_REG_LATENT, Math.round(latencyMs / 1.25) & 0xFF);
        await this._writeReg(_REG_WINDOW, Math.round(windowMs / 1.25) & 0xFF);
        await this._enableInterrupt(ADXL345Full.INT_DOUBLE_TAP);
    }

    /**
     * Configure activity detection.
     * @param {number} thresholdG - Activity threshold in *g* (62.5 mg/LSB).
     * @param {number} [axes=0x70] - ACT_INACT_CTL activity bits.
     * @param {boolean} [acCoupled=true] - AC-coupled activity detection.
     * @returns {Promise<void>}
     */
    async setActivity(thresholdG, axes = 0x70, acCoupled = true) {
        await this._writeReg(_REG_THRESH_ACT, Math.round(thresholdG / 62.5e-3) & 0xFF);
        let aic = await this._readReg(_REG_ACT_INACT_CTL);
        aic &= ~0xF0;
        if (acCoupled) aic |= 0x80;
        aic |= axes & 0x70;
        await this._writeReg(_REG_ACT_INACT_CTL, aic);
        await this._enableInterrupt(ADXL345Full.INT_ACTIVITY);
    }

    /**
     * Configure inactivity detection.
     * @param {number} thresholdG - Inactivity threshold in *g* (62.5 mg/LSB).
     * @param {number} timeSec - Inactivity time in seconds.
     * @param {number} [axes=0x07] - ACT_INACT_CTL inactivity bits.
     * @param {boolean} [acCoupled=false]
     * @returns {Promise<void>}
     */
    async setInactivity(thresholdG, timeSec, axes = 0x07, acCoupled = false) {
        await this._writeReg(_REG_THRESH_INACT, Math.round(thresholdG / 62.5e-3) & 0xFF);
        await this._writeReg(_REG_TIME_INACT, Math.round(timeSec) & 0xFF);
        let aic = await this._readReg(_REG_ACT_INACT_CTL);
        aic &= ~0x0F;
        if (acCoupled) aic |= 0x08;
        aic |= axes & 0x07;
        await this._writeReg(_REG_ACT_INACT_CTL, aic);
        await this._enableInterrupt(ADXL345Full.INT_INACTIVITY);
    }

    /**
     * Configure free-fall detection and enable the FREE_FALL interrupt.
     * @param {number} thresholdG - Free-fall threshold in *g* (62.5 mg/LSB).
     * @param {number} timeMs - Free-fall time in ms (5 ms/LSB).
     * @returns {Promise<void>}
     */
    async setFreeFall(thresholdG, timeMs) {
        await this._writeReg(_REG_THRESH_FF, Math.round(thresholdG / 62.5e-3) & 0xFF);
        await this._writeReg(_REG_TIME_FF, Math.round(timeMs / 5.0) & 0xFF);
        await this._enableInterrupt(ADXL345Full.INT_FREE_FALL);
    }

    /**
     * Enable or disable an interrupt source and route it to INT1 or INT2.
     * @param {number} source - One of the ``INT_*`` constants.
     * @param {boolean} enabled
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     */
    async setInterrupt(source, enabled, pin = 1) {
        let ie = await this._readReg(_REG_INT_ENABLE);
        let im = await this._readReg(_REG_INT_MAP);
        if (enabled) {
            ie |= source;
            if (pin === 2) im |= source;
            else           im &= ~source;
        } else {
            ie &= ~source;
        }
        await this._writeReg(_REG_INT_ENABLE, ie);
        await this._writeReg(_REG_INT_MAP, im);
    }

    async _enableInterrupt(source) {
        await this.setInterrupt(source, true, 1);
    }

    /**
     * Read the INT_SOURCE register; clears latched interrupts.
     * @returns {Promise<number>} Bitmask of active interrupt sources.
     */
    async readInterruptSource() {
        return this._readReg(_REG_INT_SOURCE);
    }

    /**
     * Configure the FIFO.
     * @param {number} mode - One of FIFO_BYPASS, FIFO_FIFO, FIFO_STREAM, FIFO_TRIGGER.
     * @param {number} [samples=16] - Watermark / retained-samples count.
     * @returns {Promise<void>}
     */
    async setFifoMode(mode, samples = 16) {
        const fifoCtl = (mode & 0xC0) | (samples & 0x1F);
        await this._writeReg(_REG_FIFO_CTL, fifoCtl);
    }

    /**
     * @returns {Promise<number>} Number of FIFO entries currently available (0–32).
     */
    async fifoCount() {
        const status = await this._readReg(_REG_FIFO_STATUS);
        return status & 0x3F;
    }

    /**
     * Drain the FIFO, returning all available (x, y, z) samples in *g*.
     * @returns {Promise<Array<[number, number, number]>>} Up to 32 samples.
     */
    async readFifo() {
        const n = await this.fifoCount();
        const out = [];
        for (let i = 0; i < n; i++) {
            const raw = await this._readBurst(_REG_DATAX0, 6);
            const rx = raw.readInt16LE(0);
            const ry = raw.readInt16LE(2);
            const rz = raw.readInt16LE(4);
            const scale = this._fullRes
                ? _FULL_RES_SCALE_G_PER_LSB
                : [3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3][this._rangeBits & 0x03];
            out.push([rx * scale, ry * scale, rz * scale]);
        }
        return out;
    }

    /**
     * Enter or leave sleep mode.
     * @param {boolean} enabled
     * @param {number} [wakeupHz=8] - Sample rate during sleep (8 / 4 / 2 / 1).
     * @returns {Promise<void>}
     */
    async setSleep(enabled, wakeupHz = 8) {
        let pwr = await this._readReg(_REG_POWER_CTL);
        if (enabled) {
            const wakeupCode = {8: ADXL345Full.WAKEUP_8_HZ, 4: ADXL345Full.WAKEUP_4_HZ,
                                2: ADXL345Full.WAKEUP_2_HZ, 1: ADXL345Full.WAKEUP_1_HZ}[wakeupHz];
            if (wakeupCode === undefined) {
                throw new Error('wakeupHz must be 8, 4, 2, or 1');
            }
            pwr = (pwr & ~0x06) | wakeupCode | 0x08;
            pwr |= 0x04;
        } else {
            pwr &= ~0x04;
        }
        await this._writeReg(_REG_POWER_CTL, pwr);
    }

    /**
     * Enable or disable the activity/inactivity serial-link mode.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setLinkMode(enabled) {
        let pwr = await this._readReg(_REG_POWER_CTL);
        if (enabled) pwr |= 0x40;
        else         pwr &= ~0x40;
        await this._writeReg(_REG_POWER_CTL, pwr);
    }

    /**
     * Enable or disable auto-sleep on inactivity (requires Link=1).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setAutoSleep(enabled) {
        let pwr = await this._readReg(_REG_POWER_CTL);
        if (enabled) pwr |= 0x20;
        else         pwr &= ~0x20;
        await this._writeReg(_REG_POWER_CTL, pwr);
    }

    /**
     * Enable or disable the electrostatic self-test force on all axes.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async selfTest(enabled) {
        let df = await this._readReg(_REG_DATA_FORMAT);
        if (enabled) df |= 0x80;
        else         df &= ~0x80;
        await this._writeReg(_REG_DATA_FORMAT, df);
    }
}

ADXL345Full.INT_DATA_READY  = 0x80;
ADXL345Full.INT_SINGLE_TAP  = 0x40;
ADXL345Full.INT_DOUBLE_TAP  = 0x20;
ADXL345Full.INT_ACTIVITY    = 0x10;
ADXL345Full.INT_INACTIVITY  = 0x08;
ADXL345Full.INT_FREE_FALL   = 0x04;
ADXL345Full.INT_WATERMARK   = 0x02;
ADXL345Full.INT_OVERRUN     = 0x01;

ADXL345Full.FIFO_BYPASS  = 0x00;
ADXL345Full.FIFO_FIFO    = 0x40;
ADXL345Full.FIFO_STREAM  = 0x80;
ADXL345Full.FIFO_TRIGGER = 0xC0;

ADXL345Full.WAKEUP_8_HZ = 0x00;
ADXL345Full.WAKEUP_4_HZ = 0x02;
ADXL345Full.WAKEUP_2_HZ = 0x04;
ADXL345Full.WAKEUP_1_HZ = 0x06;

module.exports = { ADXL345Minimal, ADXL345Full };