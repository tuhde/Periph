'use strict';

const REG_INT_STATUS = 0x00;
const REG_INT_ENABLE = 0x01;
const REG_CONFIG = 0x02;
const REG_LUX_HIGH = 0x03;
const REG_LUX_LOW = 0x04;
const REG_UPPER = 0x05;
const REG_LOWER = 0x06;
const REG_TIMER = 0x07;

const CFG_CONT = 0x80;
const CFG_MANUAL = 0x40;
const CFG_CDR = 0x08;
const CFG_TIM_MASK = 0x07;

const LUX_PER_LSB = 0.045;
const MAX_LUX = 188006.4;          // E = 14, M = 255
const OVERRANGE_EXP = 15;
const INTEGRATION_MS = [800.0, 400.0, 200.0, 100.0, 50.0, 25.0, 12.5, 6.25];
const POLL_INTERVAL_MS = 100;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _luxFrom(exponent, mantissa) {
    if (exponent >= OVERRANGE_EXP) return MAX_LUX;
    return (2 ** exponent) * mantissa * LUX_PER_LSB;
}

function _encodeThreshold(lux) {
    if (lux < 11.5) return Math.min(15, Math.round(lux / (16 * LUX_PER_LSB)));
    let exponent = 1;
    let mantissa = 0;
    for (exponent = 1; exponent <= 14; exponent++) {
        mantissa = lux / ((2 ** exponent) * LUX_PER_LSB);
        if (mantissa < 256) break;
    }
    if (exponent > 14) exponent = 14;
    mantissa = Math.max(128, Math.min(255, Math.floor(mantissa)));
    return Math.min(0xEF, (exponent << 4) | (mantissa >> 4));
}

function _decodeThreshold(code, upper) {
    const exponent = code >> 4;
    const nibble = code & 0x0F;
    return (2 ** exponent) * ((nibble << 4) | (upper ? 0x0F : 0x00)) * LUX_PER_LSB;
}

/**
 * MAX44009 ultra-low-power ambient light sensor with ADC (Maxim / Analog
 * Devices) — minimal interface.
 *
 * An on-chip photodiode with automatic ranging reports the illuminance as a
 * 12-bit floating-point value (4-bit exponent, 8-bit mantissa):
 * `lux = 2^E * M * 0.045`, from 0.045 lx to 188 006.4 lx. The I²C address is
 * 0x4A (A0 = GND) or 0x4B (A0 = VCC). A `RegisterConnection` read ends with a
 * STOP, so the lux registers are read as high, low, high and repeated once if
 * the high byte changed in between.
 */
class MAX44009Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte register address).
     */
    constructor(connection) {
        this._conn = connection;
        this._lastExponent = 0;
    }

    /** 7-bit I²C address with A0 = GND. */
    static get I2C_ADDRESS() { return 0x4A; }

    /** 7-bit I²C address with A0 = VCC. */
    static get I2C_ADDRESS_A0_HIGH() { return 0x4B; }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readLuxRegs() {
        let high = await this._readReg(REG_LUX_HIGH);
        let low = await this._readReg(REG_LUX_LOW);
        const high2 = await this._readReg(REG_LUX_HIGH);
        if (high2 !== high) {
            high = high2;
            low = await this._readReg(REG_LUX_LOW);
        }
        const exponent = high >> 4;
        const mantissa = ((high & 0x0F) << 4) | (low & 0x0F);
        this._lastExponent = exponent;
        return { exponent, mantissa };
    }

    /**
     * Run the initialization sequence: write the reset state (interrupt off,
     * CONFIG 0x03, thresholds fully open, timer 0xFF), read INT_STATUS once to
     * clear a stale interrupt and wait 900 ms (`MAX44009Minimal.initWaitMs`) so the
     * first `lux()` returns a real measurement.
     * @returns {Promise<void>}
     */
    async init() {
        await this._writeReg(REG_INT_ENABLE, 0x00);
        await this._writeReg(REG_CONFIG, 0x03);
        await this._writeReg(REG_UPPER, 0xFF);
        await this._writeReg(REG_LOWER, 0x00);
        await this._writeReg(REG_TIMER, 0xFF);
        await this._readReg(REG_INT_STATUS);
        if (MAX44009Minimal.initWaitMs > 0) await sleep(MAX44009Minimal.initWaitMs);
    }

    /**
     * Read the ambient illuminance.
     * @returns {Promise<number>} Illuminance in lux (`2^E * M * 0.045`); the maximum 188006.4 lx on overrange (E = 15).
     */
    async lux() {
        const { exponent, mantissa } = await this._readLuxRegs();
        return _luxFrom(exponent, mantissa);
    }
}

/** Wait after init so the first lux() returns a real measurement, in ms (tests may set it to 0). */
MAX44009Minimal.initWaitMs = 900;

/**
 * MAX44009 full interface — extends Minimal with raw readings, measurement
 * mode and range configuration, interrupt thresholds and delay, and interrupt
 * delivery.
 */
class MAX44009Full extends MAX44009Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /** Status bit 0: light level outside the threshold window for longer than the delay. */
    static get SOURCE_THRESHOLD() { return 0x01; }

    /**
     * Read the lux registers as one consistent sample.
     * @returns {Promise<{exponent: number, mantissa: number, overrange: boolean}>} Exponent 0–15, 8-bit mantissa, overrange = (E = 15).
     */
    async readRaw() {
        const { exponent, mantissa } = await this._readLuxRegs();
        return { exponent, mantissa, overrange: exponent >= OVERRANGE_EXP };
    }

    /**
     * Whether the last reading was overrange (E = 15).
     * @returns {boolean}
     */
    isOverrange() {
        return this._lastExponent >= OVERRANGE_EXP;
    }

    /**
     * Lux per mantissa LSB for the last reading, `2^E * 0.045`.
     * @returns {number}
     */
    resolution() {
        return (2 ** Math.min(this._lastExponent, 14)) * LUX_PER_LSB;
    }

    async _updateConfig(setBits, clearBits) {
        const cfg = await this._readReg(REG_CONFIG);
        await this._writeReg(REG_CONFIG, (cfg & ~clearBits) | setBits);
    }

    /**
     * Select continuous or low-power measurement mode.
     * @param {boolean} continuous - true: a new measurement as soon as one finishes; false: one per 800 ms.
     * @returns {Promise<void>}
     */
    async setContinuous(continuous) {
        await this._updateConfig(continuous ? CFG_CONT : 0, CFG_CONT);
    }

    /**
     * Select automatic mode (the chip chooses current divider and integration time).
     * @returns {Promise<void>}
     */
    async setAutomatic() {
        await this._updateConfig(0, CFG_MANUAL);
    }

    /**
     * Select manual mode.
     * @param {number} integrationMs - Integration time; rounded to the nearest of 800, 400, 200, 100, 50, 25, 12.5, 6.25 ms.
     * @param {boolean} [divideBy8=false] - true to divide the photodiode current by 8 (high brightness).
     * @returns {Promise<void>}
     */
    async setManual(integrationMs, divideBy8 = false) {
        let best = 0;
        let bestErr = Math.abs(INTEGRATION_MS[0] - integrationMs);
        for (let tim = 1; tim < 8; tim++) {
            const err = Math.abs(INTEGRATION_MS[tim] - integrationMs);
            if (err < bestErr) { best = tim; bestErr = err; }
        }
        const cfg = await this._readReg(REG_CONFIG);
        await this._writeReg(REG_CONFIG, (cfg & CFG_CONT) | CFG_MANUAL | (divideBy8 ? CFG_CDR : 0) | best);
    }

    /**
     * Read the CONFIG register.
     * @returns {Promise<{continuous: boolean, manual: boolean, divideBy8: boolean, integrationMs: number}>}
     */
    async getConfig() {
        const cfg = await this._readReg(REG_CONFIG);
        return {
            continuous: Boolean(cfg & CFG_CONT),
            manual: Boolean(cfg & CFG_MANUAL),
            divideBy8: Boolean(cfg & CFG_CDR),
            integrationMs: INTEGRATION_MS[cfg & CFG_TIM_MASK],
        };
    }

    /**
     * Set the interrupt threshold window.
     * @param {number} lowLux - Lower threshold in lux.
     * @param {number} highLux - Upper threshold in lux.
     * @returns {Promise<void>}
     * @throws {RangeError} if lowLux is greater than highLux.
     */
    async setThresholds(lowLux, highLux) {
        if (lowLux > highLux) throw new RangeError('lowLux must not exceed highLux');
        await this._writeReg(REG_LOWER, _encodeThreshold(lowLux));
        await this._writeReg(REG_UPPER, _encodeThreshold(highLux));
    }

    /**
     * Read the interrupt threshold window (the upper one carries the implied low nibble 1111).
     * @returns {Promise<{lowLux: number, highLux: number}>}
     */
    async getThresholds() {
        const low = await this._readReg(REG_LOWER);
        const high = await this._readReg(REG_UPPER);
        return { lowLux: _decodeThreshold(low, false), highLux: _decodeThreshold(high, true) };
    }

    /**
     * Set how long the light must stay outside the window before the interrupt.
     * @param {number} seconds - Delay in seconds, 0–25.5 (0.1 s steps).
     * @returns {Promise<void>}
     */
    async setThresholdDelay(seconds) {
        await this._writeReg(REG_TIMER, Math.max(0, Math.min(255, Math.round(seconds / 0.1))));
    }

    /**
     * Read the threshold delay.
     * @returns {Promise<number>} Delay in seconds.
     */
    async getThresholdDelay() {
        return (await this._readReg(REG_TIMER)) * 0.1;
    }

    /**
     * Enable the interrupt (INT_ENABLE = 1).
     * @returns {Promise<void>}
     */
    async enableInterrupt() {
        await this._writeReg(REG_INT_ENABLE, 0x01);
    }

    /**
     * Disable the interrupt (INT_ENABLE = 0; also clears a pending status).
     * @returns {Promise<void>}
     */
    async disableInterrupt() {
        await this._writeReg(REG_INT_ENABLE, 0x00);
    }

    /**
     * Read INT_STATUS (clearing it).
     * @returns {Promise<number>} Status; bit 0 (SOURCE_THRESHOLD) set if the light was outside the window for longer than the delay.
     */
    async pollInterrupt() {
        return (await this._readReg(REG_INT_STATUS)) & 0x01;
    }

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, POLL_INTERVAL_MS);
        }
    }

    /**
     * Subscribe to INT assertions. `callback(status)` is called after the driver
     * has read (and thereby cleared) INT_STATUS. Delivery is via the INT line
     * (options.intPin or connection.intPin, falling edge) or a 100 ms polling timer.
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        await this._arm(options.intPin ?? this._conn.intPin ?? null);
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
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
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { MAX44009Minimal, MAX44009Full };
