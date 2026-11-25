'use strict';

const { toSigned } = require('../../connection/register');

const _REG_DR_STATUS   = 0x06;
const _REG_OUT_P_MSB   = 0x01;
const _REG_OUT_P_DELTA = 0x07;
const _REG_WHO_AM_I    = 0x0C;
const _REG_F_STATUS    = 0x0D;
const _REG_F_DATA      = 0x0E;
const _REG_F_SETUP     = 0x0F;
const _REG_SYSMOD      = 0x11;
const _REG_INT_SOURCE  = 0x12;
const _REG_PT_DATA_CFG = 0x13;
const _REG_BAR_IN_MSB  = 0x14;
const _REG_P_TGT_MSB   = 0x16;
const _REG_T_TGT       = 0x18;
const _REG_P_WND_MSB   = 0x19;
const _REG_T_WND       = 0x1B;
const _REG_P_MIN       = 0x1C;
const _REG_T_MIN       = 0x1F;
const _REG_P_MAX       = 0x21;
const _REG_T_MAX       = 0x24;
const _REG_CTRL_REG1   = 0x26;
const _REG_CTRL_REG2   = 0x27;
const _REG_CTRL_REG3   = 0x28;
const _REG_CTRL_REG4   = 0x29;
const _REG_CTRL_REG5   = 0x2A;
const _REG_OFF_P       = 0x2B;
const _REG_OFF_T       = 0x2C;
const _REG_OFF_H       = 0x2D;

const _CHIP_ID   = 0xC4;
const _CTRL1_ALT = 0x80;
const _CTRL1_RST = 0x04;
const _CTRL1_OST = 0x02;
const _CTRL1_SBYB = 0x01;
const _DR_PTDR   = 0x08;

// minimum time between samples (ms) for OS = 0..7
const _OSR_TIME_MS = [6, 10, 18, 34, 66, 130, 258, 512];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _pressureFrom(b) { return ((b[0] << 12) | (b[1] << 4) | (b[2] >> 4)) / 4.0; }
function _altitudeFrom(b) { return toSigned((b[0] << 8) | b[1], 16) + (b[2] >> 4) / 16.0; }
function _temperatureFrom(b) { return toSigned(b[0], 8) + (b[1] >> 4) / 16.0; }

/**
 * MPL3115A2 barometric pressure / altitude / temperature sensor — minimal interface.
 *
 * One-shot barometer readings with no configuration beyond the connection. The I²C address is
 * fixed at 0x60. `init()` resets the chip, enables the data-ready flags (PT_DATA_CFG = 0x07) and
 * selects barometer mode with oversample ratio 128 (about 512 ms per sample) in STANDBY.
 * The constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class MPL3115A2Minimal {
    constructor(connection) {
        this._conn = connection;
        this._os = 7;
        this._alt = false;
    }

    async _writeReg(reg, value) { await this._conn.writeReg(reg, value); }
    async _readReg(reg, n = 1) { return this._conn.readReg(reg, n); }

    _ctrl1() { return (this._alt ? _CTRL1_ALT : 0) | (this._os << 3); }

    /**
     * Check WHO_AM_I (0xC4), soft reset and apply the default configuration.
     * @returns {Promise<void>}
     * @throws {Error} If the chip ID is not 0xC4 or the reset does not complete.
     */
    async init() {
        const id = (await this._readReg(_REG_WHO_AM_I))[0];
        if (id !== _CHIP_ID) throw new Error(`Unexpected chip ID 0x${id.toString(16)}, expected 0xC4`);
        await this._softReset();
        this._os = 7;
        this._alt = false;
        await this._applyConfig();
    }

    async _softReset() {
        try { await this._writeReg(_REG_CTRL_REG1, _CTRL1_RST); } catch (e) { /* no ACK while rebooting */ }
        for (let i = 0; i < 100; i++) {
            await _sleep(2);
            try {
                if (!((await this._readReg(_REG_CTRL_REG1))[0] & _CTRL1_RST)) return;
            } catch (e) { /* still rebooting */ }
        }
        throw new Error('MPL3115A2 reset timeout');
    }

    async _applyConfig() {
        await this._writeReg(_REG_PT_DATA_CFG, 0x07);
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1());
    }

    async _waitPtdr() {
        const deadline = Date.now() + _OSR_TIME_MS[this._os] * 2 + 1000;
        for (;;) {
            await _sleep(3);
            if ((await this._readReg(_REG_DR_STATUS))[0] & _DR_PTDR) return;
            if (Date.now() > deadline) throw new Error('MPL3115A2 data not ready');
        }
    }

    async _oneShot() {
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1());                 // OST must be 0 before it is set
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1() | _CTRL1_OST);
        await this._waitPtdr();
        return this._readReg(_REG_OUT_P_MSB, 5);
    }

    /**
     * Run one conversion and return pressure and temperature together.
     * @returns {Promise<{pressure_pa: number, temperature_c: number}>} Pascals and degrees Celsius.
     */
    async read() {
        const raw = await this._oneShot();
        return {
            pressure_pa: _pressureFrom(raw.slice(0, 3)),
            temperature_c: _temperatureFrom(raw.slice(3, 5)),
        };
    }

    /** Run one conversion and return the pressure. @returns {Promise<number>} Pascals. */
    async readPressure() { return (await this.read()).pressure_pa; }

    /** Run one conversion and return the temperature. @returns {Promise<number>} Degrees Celsius. */
    async readTemperature() { return (await this.read()).temperature_c; }
}

/**
 * MPL3115A2 full interface — extends MPL3115A2Minimal with altimeter mode, oversampling,
 * continuous acquisition, offsets, sea-level pressure, targets and windows, min/max capture,
 * the 32-sample FIFO and interrupts.
 */
class MPL3115A2Full extends MPL3115A2Minimal {
    constructor(connection) {
        super(connection);
        this._fifoMode = 0;
        this._callback = null;
        this._intPinNo = 1;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._edgeHandler = () => { this._service(); };
    }

    async _standby() {
        const c1 = (await this._readReg(_REG_CTRL_REG1))[0];
        const wasActive = !!(c1 & _CTRL1_SBYB);
        if (wasActive) await this._writeReg(_REG_CTRL_REG1, c1 & ~_CTRL1_SBYB & 0xFF);
        return wasActive;
    }

    async _restore(wasActive) {
        if (wasActive) await this._writeReg(_REG_CTRL_REG1, this._ctrl1() | _CTRL1_SBYB);
    }

    /**
     * Select barometer or altimeter mode (the chip is dropped to STANDBY and restored).
     * @param {boolean} altimeter - True for altimeter (metres), false for barometer (pascals).
     */
    async setMode(altimeter) {
        const was = await this._standby();
        this._alt = !!altimeter;
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1());
        await this._restore(was);
    }

    /** Switch to altimeter mode if needed and run one conversion. @returns {Promise<number>} Altitude in metres. */
    async readAltitude() {
        if (!this._alt) await this.setMode(true);
        return _altitudeFrom((await this._oneShot()).slice(0, 3));
    }

    /** Run one barometer conversion (switches back from altimeter mode if needed). @returns {Promise<{pressure_pa: number, temperature_c: number}>} */
    async read() {
        if (this._alt) await this.setMode(false);
        return super.read();
    }

    /**
     * Set the oversample ratio.
     * @param {number} ratio - 1, 2, 4, 8, 16, 32, 64 or 128.
     * @throws {RangeError} For any other value.
     */
    async setOversample(ratio) {
        const os = [1, 2, 4, 8, 16, 32, 64, 128].indexOf(ratio);
        if (os < 0) throw new RangeError('ratio must be 1, 2, 4, ..., 128');
        const was = await this._standby();
        this._os = os;
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1());
        await this._restore(was);
    }

    /** Current oversample ratio (1..128). @returns {number} */
    getOversample() { return 1 << this._os; }

    /**
     * Start periodic acquisition (ACTIVE mode).
     * @param {number} [stepExp=0] - Auto-acquisition step exponent 0-15 (2^n seconds).
     * @throws {RangeError} If stepExp is outside 0-15.
     */
    async startContinuous(stepExp = 0) {
        if (!(stepExp >= 0 && stepExp <= 15)) throw new RangeError('stepExp must be 0-15');
        await this._standby();
        const c2 = (await this._readReg(_REG_CTRL_REG2))[0];
        await this._writeReg(_REG_CTRL_REG2, (c2 & 0xF0) | stepExp);
        await this._writeReg(_REG_CTRL_REG1, this._ctrl1() | _CTRL1_SBYB);
    }

    /** Stop periodic acquisition (back to STANDBY). */
    async stopContinuous() { await this._writeReg(_REG_CTRL_REG1, this._ctrl1()); }

    /** True if new pressure/altitude or temperature data is ready (PTDR). @returns {Promise<boolean>} */
    async dataReady() { return !!((await this._readReg(_REG_DR_STATUS))[0] & _DR_PTDR); }

    /**
     * Wait for PTDR and read the latest sample.
     * @returns {Promise<{value: number, temperature_c: number}>} value is pascals or metres per mode.
     */
    async readContinuous() {
        await this._waitPtdr();
        const raw = await this._readReg(_REG_OUT_P_MSB, 5);
        const v = this._alt ? _altitudeFrom(raw.slice(0, 3)) : _pressureFrom(raw.slice(0, 3));
        return { value: v, temperature_c: _temperatureFrom(raw.slice(3, 5)) };
    }

    /** Change since the previous sample. @returns {Promise<[number, number]>} [delta value (Pa or m), delta temperature (°C)]. */
    async readDelta() {
        const raw = await this._readReg(_REG_OUT_P_DELTA, 5);
        let dv;
        if (this._alt) dv = _altitudeFrom(raw.slice(0, 3));
        else {
            dv = _pressureFrom(raw.slice(0, 3));
            if (dv >= 262144) dv -= 524288;
        }
        return [dv, _temperatureFrom(raw.slice(3, 5))];
    }

    /** Write BAR_IN, the altitude reference. @param {number} pa - Sea-level pressure in pascals (2 Pa steps, default 101326). */
    async setSeaLevelPressure(pa) {
        const raw = Math.round(pa / 2) & 0xFFFF;
        await this._writeReg(_REG_BAR_IN_MSB, Buffer.from([raw >> 8, raw & 0xFF]));
    }

    /**
     * Write the offset trims.
     * @param {number} [pressurePa=0] - -512..+508 Pa (4 Pa steps).
     * @param {number} [temperatureC=0] - -8..+7.9375 °C (0.0625 steps).
     * @param {number} [altitudeM=0] - -128..+127 m.
     * @throws {RangeError} If a value is outside its register range.
     */
    async setOffsets(pressurePa = 0, temperatureC = 0, altitudeM = 0) {
        const op = Math.round(pressurePa / 4);
        const ot = Math.round(temperatureC / 0.0625);
        const oh = Math.round(altitudeM);
        for (const v of [op, ot, oh]) if (v < -128 || v > 127) throw new RangeError('offset out of range');
        await this._writeReg(_REG_OFF_P, op & 0xFF);
        await this._writeReg(_REG_OFF_T, ot & 0xFF);
        await this._writeReg(_REG_OFF_H, oh & 0xFF);
    }

    /**
     * Program threshold/window targets.
     * @param {number} value - Pressure target in pascals or altitude in metres (per mode).
     * @param {number} window - Pressure window in pascals or altitude window in metres.
     * @param {number} [temperatureC=0] - Temperature target (signed integer °C).
     * @param {number} [temperatureWindow=0] - Temperature window (unsigned integer °C).
     */
    async setTarget(value, window, temperatureC = 0, temperatureWindow = 0) {
        const tgt = (this._alt ? Math.round(value) : Math.round(value / 2)) & 0xFFFF;
        const wnd = (this._alt ? Math.round(window) : Math.round(window / 2)) & 0xFFFF;
        const was = await this._standby();
        await this._writeReg(_REG_P_TGT_MSB, Buffer.from([tgt >> 8, tgt & 0xFF]));
        await this._writeReg(_REG_T_TGT, Math.round(temperatureC) & 0xFF);
        await this._writeReg(_REG_P_WND_MSB, Buffer.from([wnd >> 8, wnd & 0xFF]));
        await this._writeReg(_REG_T_WND, Math.round(temperatureWindow) & 0xFF);
        await this._restore(was);
    }

    /** Use the current output as the target (ALARM_SEL = 1, pulse LOAD_OUTPUT). */
    async loadCurrentAsTarget() {
        const c2 = (await this._readReg(_REG_CTRL_REG2))[0];
        await this._writeReg(_REG_CTRL_REG2, (c2 | 0x10) & ~0x20 & 0xFF);
        await this._writeReg(_REG_CTRL_REG2, c2 | 0x30);
    }

    /** Captured extremes. @returns {Promise<{p_min: number, p_max: number, t_min: number, t_max: number}>} */
    async readMinMax() {
        const pm = await this._readReg(_REG_P_MIN, 3);
        const px = await this._readReg(_REG_P_MAX, 3);
        const tm = await this._readReg(_REG_T_MIN, 2);
        const tx = await this._readReg(_REG_T_MAX, 2);
        const conv = this._alt ? _altitudeFrom : _pressureFrom;
        return { p_min: conv(pm), p_max: conv(px), t_min: _temperatureFrom(tm), t_max: _temperatureFrom(tx) };
    }

    /** Clear the min/max registers. */
    async clearMinMax() {
        await this._writeReg(_REG_P_MIN, Buffer.alloc(3));
        await this._writeReg(_REG_T_MIN, Buffer.alloc(2));
        await this._writeReg(_REG_P_MAX, Buffer.alloc(3));
        await this._writeReg(_REG_T_MAX, Buffer.alloc(2));
    }

    /**
     * Configure the FIFO.
     * @param {number} mode - 0 off, 1 circular, 2 stop when full.
     * @param {number} [watermark=0] - Watermark sample count 0-63 (0 disables the flag).
     * @throws {RangeError} For an invalid mode or a direct switch between modes 1 and 2.
     */
    async fifoSetup(mode, watermark = 0) {
        if (![0, 1, 2].includes(mode)) throw new RangeError('mode must be 0, 1 or 2');
        if (this._fifoMode !== 0 && mode !== 0 && mode !== this._fifoMode) {
            throw new RangeError('disable the FIFO before switching between circular and stop modes');
        }
        const was = await this._standby();
        await this._writeReg(_REG_F_SETUP, (mode << 6) | (watermark & 0x3F));
        this._fifoMode = mode;
        await this._restore(was);
    }

    /** FIFO state. @returns {Promise<[boolean, boolean, number]>} [overflow, watermark, count]. */
    async fifoStatus() {
        const s = (await this._readReg(_REG_F_STATUS))[0];
        return [!!(s & 0x80), !!(s & 0x40), s & 0x3F];
    }

    /** Drain the FIFO. @returns {Promise<Array<[number, number]>>} [value (Pa or m), temperature (°C)] per sample. */
    async fifoRead() {
        const count = (await this.fifoStatus())[2];
        const out = [];
        for (let i = 0; i < count; i++) {
            const raw = await this._readReg(_REG_F_DATA, 5);
            const v = this._alt ? _altitudeFrom(raw.slice(0, 3)) : _pressureFrom(raw.slice(0, 3));
            out.push([v, _temperatureFrom(raw.slice(3, 5))]);
        }
        return out;
    }

    /** Software reset, then restore the default configuration (as `init()`). */
    async reset() {
        await this._softReset();
        this._os = 7;
        this._alt = false;
        this._fifoMode = 0;
        await this._applyConfig();
    }

    /** WHO_AM_I register (0xC4). @returns {Promise<number>} */
    async whoAmI() { return (await this._readReg(_REG_WHO_AM_I))[0]; }

    /** True if SYSMOD reports ACTIVE mode. @returns {Promise<boolean>} */
    async isActive() { return !!((await this._readReg(_REG_SYSMOD))[0] & 0x01); }

    /**
     * Write CTRL_REG3: interrupt polarity (true = active high) and open-drain per pin.
     */
    async setInterruptPins(polarity1 = false, openDrain1 = false, polarity2 = false, openDrain2 = false) {
        const was = await this._standby();
        await this._writeReg(_REG_CTRL_REG3, (polarity1 ? 0x20 : 0) | (openDrain1 ? 0x10 : 0)
            | (polarity2 ? 0x02 : 0) | (openDrain2 ? 0x01 : 0));
        await this._restore(was);
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2.
     * @param {number} source - A SOURCE_* constant (or an OR of them).
     * @param {number} [pin=1] - 1 or 2.
     * @throws {RangeError} If pin is not 1 or 2.
     */
    async enableInterrupt(source, pin = 1) {
        if (pin !== 1 && pin !== 2) throw new RangeError('pin must be 1 or 2');
        const was = await this._standby();
        const c4 = (await this._readReg(_REG_CTRL_REG4))[0];
        const c5 = (await this._readReg(_REG_CTRL_REG5))[0];
        await this._writeReg(_REG_CTRL_REG4, c4 | source);
        await this._writeReg(_REG_CTRL_REG5, pin === 1 ? (c5 | source) : (c5 & ~source & 0xFF));
        await this._restore(was);
    }

    /** Disable an interrupt source (clears it in CTRL_REG4). @param {number} source */
    async disableInterrupt(source) {
        const was = await this._standby();
        const c4 = (await this._readReg(_REG_CTRL_REG4))[0];
        await this._writeReg(_REG_CTRL_REG4, c4 & ~source & 0xFF);
        await this._restore(was);
    }

    /** Read INT_SOURCE. @returns {Promise<number>} Bitmask of SOURCE_* flags. */
    async pollInterrupt() { return (await this._readReg(_REG_INT_SOURCE))[0]; }

    /**
     * Subscribe to INT1 or INT2 (falling edge). `callback(status)` receives INT_SOURCE masked to the
     * sources routed to that pin. Delivery is via the INT line (options.intPin or connection.intPin)
     * or a 100 ms polling timer.
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {number} [options.pin=1] - 1 or 2.
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     */
    async onInterrupt(callback, options = {}) {
        const pin = options.pin ?? 1;
        if (pin !== 1 && pin !== 2) throw new RangeError('pin must be 1 or 2');
        this._callback = callback;
        this._intPinNo = pin;
        const ipin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = ipin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (ipin) {
            await ipin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 100);
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
            let status = await this.pollInterrupt();
            const c5 = (await this._readReg(_REG_CTRL_REG5))[0];
            status &= this._intPinNo === 1 ? c5 : (~c5 & 0xFF);
            if (status && this._callback) this._callback(status);
        } finally {
            this._polling = false;
        }
    }
}

MPL3115A2Full.SOURCE_DRDY = 0x80;
MPL3115A2Full.SOURCE_FIFO = 0x40;
MPL3115A2Full.SOURCE_PW   = 0x20;
MPL3115A2Full.SOURCE_TW   = 0x10;
MPL3115A2Full.SOURCE_PTH  = 0x08;
MPL3115A2Full.SOURCE_TTH  = 0x04;
MPL3115A2Full.SOURCE_PCHG = 0x02;
MPL3115A2Full.SOURCE_TCHG = 0x01;
MPL3115A2Full.FIFO_OFF = 0;
MPL3115A2Full.FIFO_CIRCULAR = 1;
MPL3115A2Full.FIFO_STOP = 2;

module.exports = { MPL3115A2Minimal, MPL3115A2Full };
