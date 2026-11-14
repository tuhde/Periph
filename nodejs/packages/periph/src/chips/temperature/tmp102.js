'use strict';

const _REG_TEMPERATURE = 0x00;
const _REG_CONFIG      = 0x01;
const _REG_T_LOW       = 0x02;
const _REG_T_HIGH      = 0x03;

// CONFIGURATION (0x01) bits.
const _CFG_OS       = 0x8000;
const _CFG_F_SHIFT  = 11;
const _CFG_F_MASK   = 0x1800;
const _CFG_POL      = 0x0400;
const _CFG_TM       = 0x0200;
const _CFG_SD       = 0x0100;
const _CFG_CR_SHIFT = 6;
const _CFG_CR_MASK  = 0x00C0;
const _CFG_AL       = 0x0020;
const _CFG_EM       = 0x0010;
const _CFG_WRITABLE = 0x9FD0;
const _CFG_RESERVED = 0x6000; // R1:R0, read-only, always 11

const _LSB_C = 0.0625;
const _LIMIT_MIN = -55.0;
const _LIMIT_MAX = 150.0;
const _CONVERSION_RATES = [0.25, 1.0, 4.0, 8.0];
const _FAULT_QUEUES = [1, 2, 4, 6];

const _sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function _signed(value, bits) {
    const sign = 1 << (bits - 1);
    return (value ^ sign) - sign;
}

// Raw signed count of a TEMPERATURE/limit register; bit 0 = 1 marks the 13-bit format.
function _decodeRaw(raw16) {
    if (raw16 & 0x0001) return _signed(raw16 >> 3, 13);
    return _signed(raw16 >> 4, 12);
}

/**
 * TMP102 12-bit digital temperature sensor with ALERT output (Texas
 * Instruments) — minimal interface.
 *
 * Two-wire temperature sensor (0.0625 °C per LSB, ±0.5 °C typical) read over
 * an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian, addressed
 * through a persistent Register Pointer. Four selectable addresses
 * (0x48–0x4B) via the ADD0 strap.
 *
 * The constructor makes no bus access: the power-on default (continuous
 * conversion at 4 Hz, comparator mode, 12-bit) already serves the primary
 * use case.
 */
class TMP102Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B).
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _readReg(reg) {
        const buf = await this._conn.readReg(reg & 0xFF, 2);
        return buf.readUInt16BE(0);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg & 0xFF, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    /**
     * Read the temperature. Decodes the 12-bit (0.0625 °C per LSB) or, in
     * extended mode, 13-bit two's-complement value; the format is taken from
     * bit 0 of the result.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _decodeRaw(await this._readReg(_REG_TEMPERATURE)) * _LSB_C;
    }
}

/**
 * TMP102 full interface — extends Minimal with conversion rate, extended mode,
 * the T_LOW/T_HIGH limits, fault queue, polarity, thermostat mode,
 * shutdown/one-shot, alert status, general-call reset and the ALERT
 * interrupt API.
 */
class TMP102Full extends TMP102Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _updateConfig(clear, set) {
        const config = ((await this.readConfig()) & ~clear) | set;
        await this.writeConfig(config);
    }

    /**
     * Read the raw signed temperature count.
     * @returns {Promise<number>} Signed 12-bit (13-bit in extended mode) count; temperature = count × 0.0625 °C.
     */
    async readRaw() {
        return _decodeRaw(await this._readReg(_REG_TEMPERATURE));
    }

    /**
     * Read the 16-bit CONFIGURATION register (reset value 0x60A0).
     * @returns {Promise<number>} Register value.
     */
    async readConfig() {
        return this._readReg(_REG_CONFIG);
    }

    /**
     * Write the 16-bit CONFIGURATION register; read-only bits (R1:R0, AL,
     * bits 3:0) are masked out.
     * @param {number} value - Register value; only OS, F1:F0, POL, TM, SD, CR1:CR0 and EM are applied.
     * @returns {Promise<void>}
     */
    async writeConfig(value) {
        await this._writeReg(_REG_CONFIG, (value & _CFG_WRITABLE) | _CFG_RESERVED);
    }

    /**
     * Set the conversion rate.
     * @param {number} hz - 0.25, 1, 4 or 8 Hz.
     * @returns {Promise<void>}
     * @throws {RangeError} If hz is not a supported rate.
     */
    async setConversionRate(hz) {
        const code = _CONVERSION_RATES.indexOf(hz);
        if (code < 0) throw new RangeError('hz must be 0.25, 1, 4 or 8');
        await this._updateConfig(_CFG_CR_MASK, code << _CFG_CR_SHIFT);
    }

    /**
     * Enable or disable the 13-bit extended mode (EM bit).
     * @param {boolean} on - true for 13-bit (-256…+255.9375 °C), false for 12-bit.
     * @returns {Promise<void>}
     */
    async setExtendedMode(on) {
        await this._updateConfig(_CFG_EM, on ? _CFG_EM : 0);
    }

    async _setLimit(reg, celsius) {
        const extended = ((await this.readConfig()) & _CFG_EM) !== 0;
        let c = Math.min(Math.max(celsius, _LIMIT_MIN), _LIMIT_MAX);
        const steps = c / _LSB_C;
        let counts = steps >= 0 ? Math.floor(steps + 0.5) : -Math.floor(-steps + 0.5);
        if (extended) {
            await this._writeReg(reg, (counts << 3) & 0xFFF8);
        } else {
            if (counts > 2047) counts = 2047;
            await this._writeReg(reg, (counts << 4) & 0xFFF0);
        }
    }

    async _getLimit(reg) {
        const extended = ((await this.readConfig()) & _CFG_EM) !== 0;
        const raw16 = await this._readReg(reg);
        const counts = extended ? _signed(raw16 >> 3, 13) : _signed(raw16 >> 4, 12);
        return counts * _LSB_C;
    }

    /**
     * Set the T_HIGH limit (reset 80 °C).
     * @param {number} celsius - Limit in °C, rounded to 0.0625 °C and clamped to -55…+150 °C (12-bit mode: up to +127.9375 °C).
     * @returns {Promise<void>}
     */
    async setTHigh(celsius) {
        await this._setLimit(_REG_T_HIGH, celsius);
    }

    /**
     * Read the T_HIGH limit.
     * @returns {Promise<number>} Limit in °C.
     */
    async getTHigh() {
        return this._getLimit(_REG_T_HIGH);
    }

    /**
     * Set the T_LOW limit (reset 75 °C).
     * @param {number} celsius - Limit in °C, rounded to 0.0625 °C and clamped to -55…+150 °C (12-bit mode: up to +127.9375 °C).
     * @returns {Promise<void>}
     */
    async setTLow(celsius) {
        await this._setLimit(_REG_T_LOW, celsius);
    }

    /**
     * Read the T_LOW limit.
     * @returns {Promise<number>} Limit in °C.
     */
    async getTLow() {
        return this._getLimit(_REG_T_LOW);
    }

    /**
     * Set the number of consecutive faults that trigger ALERT.
     * @param {number} count - 1, 2, 4 or 6.
     * @returns {Promise<void>}
     * @throws {RangeError} If count is not a supported value.
     */
    async setFaultQueue(count) {
        const code = _FAULT_QUEUES.indexOf(count);
        if (code < 0) throw new RangeError('count must be 1, 2, 4 or 6');
        await this._updateConfig(_CFG_F_MASK, code << _CFG_F_SHIFT);
    }

    /**
     * Set the ALERT polarity (POL bit).
     * @param {boolean} activeHigh - true for active-high, false for active-low (default).
     * @returns {Promise<void>}
     */
    async setPolarity(activeHigh) {
        await this._updateConfig(_CFG_POL, activeHigh ? _CFG_POL : 0);
    }

    /**
     * Select the thermostat mode (TM bit).
     * @param {boolean} interrupt - true for interrupt mode, false for comparator mode (default).
     * @returns {Promise<void>}
     */
    async setThermostatMode(interrupt) {
        await this._updateConfig(_CFG_TM, interrupt ? _CFG_TM : 0);
    }

    /**
     * Shut the device down after the current conversion (SD=1).
     * @returns {Promise<void>}
     */
    async shutdown() {
        await this._updateConfig(0, _CFG_SD);
    }

    /**
     * Resume continuous conversion (SD=0).
     * @returns {Promise<void>}
     */
    async wake() {
        await this._updateConfig(_CFG_SD, 0);
    }

    /**
     * Take a single conversion and return the temperature. Puts the device in
     * shutdown if needed, sets OS=1, polls OS until the conversion is done
     * (30 ms timeout) and restores the previous mode.
     * @returns {Promise<number>} Temperature in °C.
     * @throws {Error} If the conversion does not finish within 30 ms.
     */
    async oneShot() {
        let config = await this.readConfig();
        const wasRunning = (config & _CFG_SD) === 0;
        if (wasRunning) {
            config |= _CFG_SD;
            await this.writeConfig(config);
        }
        await this.writeConfig(config | _CFG_OS);
        let done = false;
        for (let i = 0; i < 30; i++) {
            await _sleep(1);
            if ((await this.readConfig()) & _CFG_OS) { done = true; break; }
        }
        const temperature = await this.readTemperature();
        if (wasRunning) await this.wake();
        if (!done) throw new Error('TMP102: one-shot conversion timed out');
        return temperature;
    }

    /**
     * Report whether ALERT is currently active.
     * @returns {Promise<boolean>} true while the comparator state is alerting (AL bit corrected for POL).
     */
    async alertState() {
        const config = await this.readConfig();
        const al = (config & _CFG_AL) !== 0;
        // AL reads 1 while inactive with POL=0 (inverted); POL=1 flips it.
        return (config & _CFG_POL) ? al : !al;
    }

    /**
     * Send the general-call reset (0x00 0x06). Resets every general-call
     * device on the bus to power-up values. Needs a Connection that transmits
     * the bytes to the general-call address.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._conn.write(Buffer.from([0x00, 0x06]));
    }

    /**
     * Read the alert state and clear an interrupt-mode alert (reads
     * CONFIGURATION, then TEMPERATURE).
     * @returns {Promise<boolean>} true while the comparator state is alerting.
     */
    async pollInterrupt() {
        const alerting = await this.alertState();
        await this._readReg(_REG_TEMPERATURE);
        return alerting;
    }

    /**
     * Subscribe to ALERT events. With an interrupt pin on the connection the
     * callback runs on every active ALERT edge (direction follows POL);
     * otherwise a 1 s polling task calls it when pollInterrupt() reports an alert.
     * @param {(temperatureC: number) => void} callback
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            const activeHigh = ((await this.readConfig()) & _CFG_POL) !== 0;
            this._edgeHandler = async () => {
                const t = await this.readTemperature();
                if (this._callback) this._callback(t);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, activeHigh ? 'rising' : 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    if (await this.pollInterrupt()) {
                        const t = await this.readTemperature();
                        if (this._callback) this._callback(t);
                    }
                } finally {
                    busy = false;
                }
            }, 1000);
        }
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

TMP102Minimal.I2C_ADDRESS = 0x48;

module.exports = { TMP102Minimal, TMP102Full };
