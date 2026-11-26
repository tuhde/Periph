'use strict';

const { toSigned } = require('../../connection/register');

const _REG_PRS_B2   = 0x00;
const _REG_TMP_B2   = 0x03;
const _REG_PRS_CFG  = 0x06;
const _REG_TMP_CFG  = 0x07;
const _REG_MEAS_CFG = 0x08;
const _REG_CFG_REG  = 0x09;
const _REG_INT_STS  = 0x0A;
const _REG_FIFO_STS = 0x0B;
const _REG_RESET    = 0x0C;
const _REG_ID       = 0x0D;
const _REG_COEF     = 0x10;

const _CHIP_ID          = 0x10;
const _MEAS_COEF_RDY    = 0x80;
const _MEAS_SENSOR_RDY  = 0x40;
const _MEAS_TMP_RDY     = 0x20;
const _MEAS_PRS_RDY     = 0x10;
const _CMD_IDLE         = 0x00;
const _CMD_PRESSURE     = 0x01;
const _CMD_TEMPERATURE  = 0x02;
const _CFG_T_SHIFT      = 0x08;
const _CFG_P_SHIFT      = 0x04;
const _CFG_INT_HL       = 0x80;
const _CFG_INT_FIFO     = 0x40;
const _CFG_INT_PRS      = 0x20;
const _CFG_INT_TMP      = 0x10;
const _CFG_FIFO_EN      = 0x02;
const _TMP_EXT          = 0x80;

// Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4)
const _SCALE = [524288, 1572864, 3670016, 7864320, 253952, 516096, 1040384, 2088960];
// Typical measurement time in ms indexed by log2(oversampling), rounded up
const _MEAS_MS = [4, 6, 9, 15, 28, 54, 105, 207];
const _VALID = [1, 2, 4, 8, 16, 32, 64, 128];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _raw24(b) { return toSigned((b[0] << 16) | (b[1] << 8) | b[2], 24); }

function _log2(value, name) {
    const i = _VALID.indexOf(value);
    if (i < 0) throw new RangeError(`${name} must be 1, 2, 4, 8, 16, 32, 64 or 128`);
    return i;
}

/**
 * SPL06-007 barometric pressure and temperature sensor (Goertek) — minimal interface.
 *
 * One call returns compensated temperature and pressure. The chip stays in standby between
 * readings; each `read()` runs one temperature and one pressure measurement in command mode.
 * `init()` verifies ID = 0x10, waits for SENSOR_RDY / COEF_RDY, reads the 18 coefficient bytes and
 * writes TMP_CFG = 0x80 (external temperature sensor, 1×), PRS_CFG = 0x04 (16×, standard
 * precision) and CFG_REG = 0x04 (P_SHIFT). I²C address 0x77 (SDO high or open) or 0x76 (SDO low).
 * The constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class SPL06Minimal {
    constructor(connection) {
        this._conn = connection;
        this._coef = null;
        this._pOs = 4;
        this._tOs = 0;
        this._cfg = _CFG_P_SHIFT;
        this._lastTraw = 0;
    }

    /**
     * Verify the chip, read the calibration coefficients and apply the defaults.
     * @returns {Promise<void>}
     * @throws {Error} If the ID is not 0x10 or the chip does not become ready.
     */
    async init() {
        const id = (await this._conn.readReg(_REG_ID, 1))[0];
        if (id !== _CHIP_ID) throw new Error(`Unexpected chip ID 0x${id.toString(16)}, expected 0x10`);
        await this._waitReady();
        await this._readCoefficients();
        await this._applyDefaults();
    }

    async _waitReady() {
        const mask = _MEAS_COEF_RDY | _MEAS_SENSOR_RDY;
        const deadline = Date.now() + 200;
        for (;;) {
            try {
                if (((await this._conn.readReg(_REG_MEAS_CFG, 1))[0] & mask) === mask) return;
            } catch (e) { /* no ACK right after a reset */ }
            if (Date.now() > deadline) throw new Error('SPL06 not ready');
            await _sleep(5);
        }
    }

    async _readCoefficients() {
        const b = await this._conn.readReg(_REG_COEF, 18);
        this._coef = {
            c0: toSigned((b[0] << 4) | (b[1] >> 4), 12),
            c1: toSigned(((b[1] & 0x0F) << 8) | b[2], 12),
            c00: toSigned((b[3] << 12) | (b[4] << 4) | (b[5] >> 4), 20),
            c10: toSigned(((b[5] & 0x0F) << 16) | (b[6] << 8) | b[7], 20),
            c01: toSigned((b[8] << 8) | b[9], 16),
            c11: toSigned((b[10] << 8) | b[11], 16),
            c20: toSigned((b[12] << 8) | b[13], 16),
            c21: toSigned((b[14] << 8) | b[15], 16),
            c30: toSigned((b[16] << 8) | b[17], 16),
        };
    }

    async _applyDefaults() {
        this._pOs = 4;
        this._tOs = 0;
        this._cfg = _CFG_P_SHIFT;
        await this._conn.writeReg(_REG_TMP_CFG, _TMP_EXT);
        await this._conn.writeReg(_REG_PRS_CFG, 0x04);
        await this._conn.writeReg(_REG_CFG_REG, this._cfg);
    }

    async _measure(command, flag, osLog2) {
        await this._conn.writeReg(_REG_MEAS_CFG, command);
        await _sleep(_MEAS_MS[osLog2]);
        const deadline = Date.now() + _MEAS_MS[osLog2] + 50;
        while (!((await this._conn.readReg(_REG_MEAS_CFG, 1))[0] & flag)) {
            if (Date.now() > deadline) throw new Error('SPL06 measurement timeout');
            await _sleep(2);
        }
        return _raw24(await this._conn.readReg(command === _CMD_PRESSURE ? _REG_PRS_B2 : _REG_TMP_B2, 3));
    }

    async _measureRaw() {
        const traw = await this._measure(_CMD_TEMPERATURE, _MEAS_TMP_RDY, this._tOs);
        const praw = await this._measure(_CMD_PRESSURE, _MEAS_PRS_RDY, this._pOs);
        this._lastTraw = traw;
        return [praw, traw];
    }

    _temperature(traw) {
        return this._coef.c0 * 0.5 + this._coef.c1 * (traw / _SCALE[this._tOs]);
    }

    _pressure(praw, traw) {
        const c = this._coef;
        const p = praw / _SCALE[this._pOs];
        const t = traw / _SCALE[this._tOs];
        return c.c00 + p * (c.c10 + p * (c.c20 + p * c.c30)) + t * c.c01 + t * p * (c.c11 + p * c.c21);
    }

    /**
     * Measure temperature, then pressure (command mode), and compensate both.
     * @returns {Promise<{temperature_c: number, pressure_pa: number}>} Degrees Celsius and pascals.
     */
    async read() {
        const [praw, traw] = await this._measureRaw();
        return { temperature_c: this._temperature(traw), pressure_pa: this._pressure(praw, traw) };
    }
}

/**
 * SPL06-007 full interface — extends SPL06Minimal with oversampling and rate configuration,
 * background (continuous) mode, the 32-entry FIFO, raw values and compensation, altitude,
 * soft reset and the data-ready / FIFO-full interrupt.
 *
 * The interrupt output shares the SDO pin. INT_HL stays at its reset value 0 (active low), whose
 * idle-high level matches the default address 0x77.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class SPL06Full extends SPL06Minimal {
    constructor(connection) {
        super(connection);
        this._pRate = 0;
        this._tRate = 0;
        this._continuous = 0;
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    async _writeCfg() { await this._conn.writeReg(_REG_CFG_REG, this._cfg); }

    static _intBits(source) {
        let bits = 0;
        if (source & SPL06Full.SOURCE_PRESSURE) bits |= _CFG_INT_PRS;
        if (source & SPL06Full.SOURCE_TEMPERATURE) bits |= _CFG_INT_TMP;
        if (source & SPL06Full.SOURCE_FIFO_FULL) bits |= _CFG_INT_FIFO;
        return bits;
    }

    /**
     * Measure temperature, then pressure (in background mode: the latest results).
     * @returns {Promise<{temperature_c: number, pressure_pa: number}>} Degrees Celsius and pascals.
     */
    async read() {
        if (this._continuous) return this.readContinuous();
        return super.read();
    }

    /** Measure temperature and pressure and return the compensated pressure. @returns {Promise<number>} Pascals. */
    async readPressure() { return (await this.read()).pressure_pa; }

    /** Measure and return the temperature. @returns {Promise<number>} Degrees Celsius. */
    async readTemperature() {
        if (this._continuous) return (await this.readContinuous()).temperature_c;
        const traw = await this._measure(_CMD_TEMPERATURE, _MEAS_TMP_RDY, this._tOs);
        this._lastTraw = traw;
        return this._temperature(traw);
    }

    /**
     * Raw 24-bit two's-complement results. Command mode measures temperature, then pressure;
     * background mode reads the latest results without starting a measurement.
     * @returns {Promise<number[]>} `[praw, traw]`.
     */
    async readRaw() {
        if (this._continuous) {
            const b = await this._conn.readReg(_REG_PRS_B2, 6);
            const praw = _raw24(b.slice(0, 3));
            const traw = _raw24(b.slice(3, 6));
            this._lastTraw = traw;
            return [praw, traw];
        }
        return this._measureRaw();
    }

    /**
     * Write PRS_CFG and P_SHIFT (set above 8×).
     * @param {number} [oversampling=16] - 1, 2, 4, 8, 16, 32, 64 or 128.
     * @param {number} [rate=1] - Background measurements per second: 1, 2, 4, ..., 128.
     * @throws {RangeError} For values other than powers of two 1-128.
     */
    async setPressureConfig(oversampling = 16, rate = 1) {
        const os = _log2(oversampling, 'oversampling');
        const r = _log2(rate, 'rate');
        await this._conn.writeReg(_REG_PRS_CFG, (r << 4) | os);
        this._pOs = os;
        this._pRate = r;
        this._cfg = oversampling > 8 ? (this._cfg | _CFG_P_SHIFT) : (this._cfg & ~_CFG_P_SHIFT & 0xFF);
        await this._writeCfg();
    }

    /**
     * Write TMP_CFG (always TMP_EXT = 1) and T_SHIFT (set above 8×).
     * @param {number} [oversampling=1] - 1, 2, 4, 8, 16, 32, 64 or 128.
     * @param {number} [rate=1] - Background measurements per second: 1, 2, 4, ..., 128.
     * @throws {RangeError} For values other than powers of two 1-128.
     */
    async setTemperatureConfig(oversampling = 1, rate = 1) {
        const os = _log2(oversampling, 'oversampling');
        const r = _log2(rate, 'rate');
        await this._conn.writeReg(_REG_TMP_CFG, _TMP_EXT | (r << 4) | os);
        this._tOs = os;
        this._tRate = r;
        this._cfg = oversampling > 8 ? (this._cfg | _CFG_T_SHIFT) : (this._cfg & ~_CFG_T_SHIFT & 0xFF);
        await this._writeCfg();
    }

    /**
     * Start background mode (MEAS_CTRL = 101 / 110 / 111).
     * @param {boolean} [pressure=true] - Measure pressure continuously.
     * @param {boolean} [temperature=true] - Measure temperature continuously.
     * @throws {RangeError} If both are false.
     */
    async startContinuous(pressure = true, temperature = true) {
        if (!pressure && !temperature) throw new RangeError('select pressure and/or temperature');
        const mode = 0x04 | (pressure ? 0x01 : 0) | (temperature ? 0x02 : 0);
        await this._conn.writeReg(_REG_MEAS_CFG, mode);
        this._continuous = mode;
    }

    /** Stop background mode and return to standby (MEAS_CTRL = 000). */
    async stop() {
        await this._conn.writeReg(_REG_MEAS_CFG, _CMD_IDLE);
        this._continuous = 0;
    }

    /**
     * Wait for the background ready flags and return the latest results (no new measurement).
     * @returns {Promise<{temperature_c: number, pressure_pa: number}>} Degrees Celsius and pascals.
     */
    async readContinuous() {
        let flags = 0;
        if (this._continuous & 0x01) flags |= _MEAS_PRS_RDY;
        if (this._continuous & 0x02) flags |= _MEAS_TMP_RDY;
        if (flags) {
            const period = 1000 / (1 << Math.min(this._pRate, this._tRate));
            const deadline = Date.now() + period * 2 + 500;
            while (((await this._conn.readReg(_REG_MEAS_CFG, 1))[0] & flags) !== flags) {
                if (Date.now() > deadline) throw new Error('SPL06 background data not ready');
                await _sleep(2);
            }
        }
        const b = await this._conn.readReg(_REG_PRS_B2, 6);
        const praw = _raw24(b.slice(0, 3));
        const traw = _raw24(b.slice(3, 6));
        this._lastTraw = traw;
        return { temperature_c: this._temperature(traw), pressure_pa: this._pressure(praw, traw) };
    }

    /** Read the MEAS_CFG ready flags. @returns {Promise<boolean[]>} `[pressureReady, temperatureReady]`. */
    async dataReady() {
        const m = (await this._conn.readReg(_REG_MEAS_CFG, 1))[0];
        return [!!(m & _MEAS_PRS_RDY), !!(m & _MEAS_TMP_RDY)];
    }

    /** Enable the 32-entry result FIFO (CFG_REG.FIFO_EN). */
    async enableFifo() {
        this._cfg |= _CFG_FIFO_EN;
        await this._writeCfg();
    }

    /** Disable the result FIFO. */
    async disableFifo() {
        this._cfg &= ~_CFG_FIFO_EN & 0xFF;
        await this._writeCfg();
    }

    /**
     * Drain the FIFO until FIFO_EMPTY (at most 32 entries). The LSB of each entry flags its kind
     * (1 = pressure, 0 = temperature); pressure entries are compensated with the most recent
     * temperature entry (or the last measured temperature).
     * @returns {Promise<Array<{kind: string, value: number}>>} `kind` is "pressure" (Pa) or "temperature" (°C).
     */
    async readFifo() {
        const out = [];
        for (let i = 0; i < 32; i++) {
            if ((await this._conn.readReg(_REG_FIFO_STS, 1))[0] & 0x01) break;
            const raw = _raw24(await this._conn.readReg(_REG_PRS_B2, 3));
            if (raw === -0x800000) break;
            if (raw & 1) {
                out.push({ kind: 'pressure', value: this._pressure(raw & ~1, this._lastTraw) });
            } else {
                this._lastTraw = raw;
                out.push({ kind: 'temperature', value: this._temperature(raw) });
            }
        }
        return out;
    }

    /** Discard the FIFO contents (RESET.FIFO_FLUSH). */
    async flushFifo() { await this._conn.writeReg(_REG_RESET, 0x80); }

    /** Read FIFO_STS. @returns {Promise<boolean[]>} `[empty, full]`. */
    async fifoStatus() {
        const s = (await this._conn.readReg(_REG_FIFO_STS, 1))[0];
        return [!!(s & 0x01), !!(s & 0x02)];
    }

    /** Soft reset (RESET = 0x09), wait for the ready flags, re-read the coefficients and restore the init defaults. */
    async reset() {
        try { await this._conn.writeReg(_REG_RESET, 0x09); } catch (e) { /* no ACK while resetting */ }
        await _sleep(12);
        await this._waitReady();
        await this._readCoefficients();
        await this._applyDefaults();
        this._pRate = 0;
        this._tRate = 0;
        this._continuous = 0;
    }

    /** Decoded calibration coefficients. @returns {{c0: number, c1: number, c00: number, c10: number, c01: number, c11: number, c20: number, c21: number, c30: number}} */
    coefficients() { return { ...this._coef }; }

    /**
     * Compensate raw values with the stored coefficients (no bus access).
     * @param {number} praw - Signed raw pressure.
     * @param {number} traw - Signed raw temperature.
     * @param {number} pOversampling - Pressure oversampling of `praw`.
     * @param {number} tOversampling - Temperature oversampling of `traw`.
     * @returns {{temperature_c: number, pressure_pa: number}} Degrees Celsius and pascals.
     * @throws {RangeError} For an oversampling other than a power of two 1-128.
     */
    compensate(praw, traw, pOversampling, tOversampling) {
        const c = this._coef;
        const p = praw / _SCALE[_log2(pOversampling, 'pOversampling')];
        const t = traw / _SCALE[_log2(tOversampling, 'tOversampling')];
        return {
            temperature_c: c.c0 * 0.5 + c.c1 * t,
            pressure_pa: c.c00 + p * (c.c10 + p * (c.c20 + p * c.c30)) + t * c.c01 + t * p * (c.c11 + p * c.c21),
        };
    }

    /**
     * Measure the pressure and convert it to altitude (international barometric formula).
     * @param {number} [seaLevelPa=101325] - Reference sea-level pressure in pascals.
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelPa = 101325.0) {
        const p = await this.readPressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }

    /** Enable interrupt sources on SDO (CFG_REG.INT_PRS / INT_TMP / INT_FIFO). @param {number} source - SOURCE_* or a bitwise OR. */
    async enableInterrupt(source) {
        this._cfg |= SPL06Full._intBits(source);
        await this._writeCfg();
    }

    /** Disable interrupt sources. @param {number} source - SOURCE_* or a bitwise OR. */
    async disableInterrupt(source) {
        this._cfg &= ~SPL06Full._intBits(source) & 0xFF;
        await this._writeCfg();
    }

    /** Read INT_STS (cleared on read). @returns {Promise<number>} Bitmask of SOURCE_* flags (0 = none). */
    async pollInterrupt() { return (await this._conn.readReg(_REG_INT_STS, 1))[0] & 0x07; }

    /**
     * Subscribe to the interrupt. `callback(status)` receives the SOURCE_* bitmask. Delivery is via
     * the INT line (`intPin` or connection.intPin, edge matching INT_HL) or a 5 ms polling timer.
     * @param {function(number): void} callback - Called with the status bitmask.
     * @param {import('../../connection/input_pin').InputPin|null} [intPin] - Overrides connection.intPin.
     */
    async onInterrupt(callback, intPin = null) {
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = ipin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (ipin) {
            await ipin.onEdge(this._edgeHandler, (this._cfg & _CFG_INT_HL) ? 'rising' : 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 5);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe and stop delivery. */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        } catch (e) {
            /* a failed poll is retried on the next edge / tick */
        } finally {
            this._polling = false;
        }
    }
}

/** Pressure result ready (INT_STS.INT_PRS). */
SPL06Full.SOURCE_PRESSURE = 0x01;
/** Temperature result ready (INT_STS.INT_TMP). */
SPL06Full.SOURCE_TEMPERATURE = 0x02;
/** FIFO full (INT_STS.INT_FIFO_FULL). */
SPL06Full.SOURCE_FIFO_FULL = 0x04;

module.exports = { SPL06Minimal, SPL06Full };
