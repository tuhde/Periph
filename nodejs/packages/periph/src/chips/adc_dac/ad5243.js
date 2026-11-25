'use strict';

const _INSTR_A0 = 0x80;
const _INSTR_SD = 0x40;
const _MIDSCALE = 0x80;

function _checkChannel(channel) {
    if (channel !== 0 && channel !== 1) throw new RangeError('channel must be 0 or 1');
}

function _clampCode(code) {
    code = Math.trunc(code);
    if (code < 0) return 0;
    if (code > 255) return 255;
    return code;
}

/**
 * AD5243 dual 256-position I²C digital potentiometer (Analog Devices) —
 * minimal interface.
 *
 * Two independent 3-terminal potentiometers (A, W, B). The chip has no
 * register map: a write is `[instruction, data]` where the instruction byte
 * carries the channel select (A0, bit 7) and the shutdown flag (SD, bit 6);
 * it is passed as the "register" of the RegisterConnection. The fixed I²C
 * address is 0x2F. Wiper values are volatile (power-up midscale 0x80).
 */
class AD5243Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x2F).
     * @param {number} [rAb=10000] - End-to-end resistance RAB in ohms.
     * @param {number} [rWiper=160] - Wiper resistance RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 160.0) {
        this._conn = connection;
        this._rAb = rAb;
        this._rW = rWiper;
        this._code = [_MIDSCALE, _MIDSCALE];
        this._sd = [false, false];
    }

    _instr(channel) {
        _checkChannel(channel);
        return (channel ? _INSTR_A0 : 0) | (this._sd[channel] ? _INSTR_SD : 0);
    }

    async _writeCode(channel, code) {
        code = _clampCode(code);
        await this._conn.writeReg(this._instr(channel), code);
        this._code[channel] = code;
    }

    async _readCode(channel) {
        const buf = await this._conn.readReg(this._instr(channel), 1);
        this._code[channel] = buf[0];
        return buf[0];
    }

    /**
     * Prepare the driver. The AD5243 has no identity register and needs no
     * initialisation writes, so this performs no I²C traffic.
     * @returns {Promise<void>}
     */
    async init() {}

    /**
     * Set the wiper position of a channel.
     * @param {number} channel - 0 or 1.
     * @param {number} code - Wiper position 0–255 (clamped).
     * @returns {Promise<void>}
     */
    async setWiper(channel, code) {
        await this._writeCode(channel, code);
    }

    /**
     * Read back the wiper position of a channel (instruction byte alone,
     * then one data byte).
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Wiper position 0–255.
     */
    async getWiper(channel) {
        return this._readCode(channel);
    }

    /**
     * Set the W–B resistance to the nearest reachable code.
     * @param {number} channel - 0 or 1.
     * @param {number} ohms - Target W–B resistance in ohms (clamped to the reachable range).
     * @returns {Promise<void>}
     */
    async setResistance(channel, ohms) {
        await this._writeCode(channel, Math.floor((ohms - 2 * this._rW) / this._rAb * 256 + 0.5));
    }

    /**
     * Read the W–B resistance for the current wiper code.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Resistance in ohms, `D / 256 * RAB + 2 * RW`.
     */
    async getResistance(channel) {
        return (await this._readCode(channel)) / 256 * this._rAb + 2 * this._rW;
    }
}

/** Fixed 7-bit I²C address (no address pins). */
AD5243Minimal.I2C_ADDRESS = 0x2F;

/**
 * AD5243 full interface — extends Minimal with ratio and W–A helpers,
 * increment/decrement, per-channel shutdown and reset. The shutdown state is
 * write-only on the chip, so the driver tracks it.
 */
class AD5243Full extends AD5243Minimal {
    /**
     * Write both wipers (two transactions).
     * @param {number} code1 - Channel 0 wiper position 0–255.
     * @param {number} code2 - Channel 1 wiper position 0–255.
     * @returns {Promise<void>}
     */
    async setBoth(code1, code2) {
        await this._writeCode(0, code1);
        await this._writeCode(1, code2);
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     * @param {number} channel - 0 or 1.
     * @param {number} ratio - 0.0–1.0; code is round(ratio × 256) clamped to 255.
     * @returns {Promise<void>}
     */
    async setRatio(channel, ratio) {
        await this._writeCode(channel, Math.floor(ratio * 256 + 0.5));
    }

    /**
     * Read the divider ratio of a channel.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} `code / 256`.
     */
    async getRatio(channel) {
        return (await this._readCode(channel)) / 256;
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     * @param {number} channel - 0 or 1.
     * @param {number} ohms - Target W–A resistance in ohms.
     * @returns {Promise<void>}
     */
    async setResistanceWa(channel, ohms) {
        const steps = Math.floor((ohms - 2 * this._rW) / this._rAb * 256 + 0.5);
        await this._writeCode(channel, 256 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Resistance in ohms, `(256 − D) / 256 * RAB + 2 * RW`.
     */
    async getResistanceWa(channel) {
        return (256 - (await this._readCode(channel))) / 256 * this._rAb + 2 * this._rW;
    }

    /**
     * Move the wiper relative to its current position.
     * @param {number} channel - 0 or 1.
     * @param {number} [steps=1] - Signed step count (negative to decrement).
     * @returns {Promise<number>} The new wiper code, clamped to 0–255.
     */
    async increment(channel, steps = 1) {
        const code = _clampCode((await this._readCode(channel)) + Math.trunc(steps));
        await this._writeCode(channel, code);
        return code;
    }

    /**
     * Shut a channel down: terminal A opens and the wiper shorts to B. The
     * RDAC contents are kept.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<void>}
     */
    async shutdown(channel) {
        _checkChannel(channel);
        this._sd[channel] = true;
        await this._writeCode(channel, this._code[channel]);
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked current code.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<void>}
     */
    async resume(channel) {
        _checkChannel(channel);
        this._sd[channel] = false;
        await this._writeCode(channel, this._code[channel]);
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     * @param {number} channel - 0 or 1.
     * @returns {boolean} True if the channel was shut down by this driver.
     */
    isShutdown(channel) {
        _checkChannel(channel);
        return this._sd[channel];
    }

    /**
     * Restore the power-up preset: both wipers 0x80, SD = 0.
     * @returns {Promise<void>}
     */
    async reset() {
        this._sd = [false, false];
        await this._writeCode(0, _MIDSCALE);
        await this._writeCode(1, _MIDSCALE);
    }

    /**
     * Change the resistance parameters used by conversions (no I²C traffic).
     * @param {number} rAb - End-to-end resistance RAB in ohms.
     * @param {number} [rWiper] - Wiper resistance RW in ohms; unchanged if omitted.
     */
    setRab(rAb, rWiper) {
        this._rAb = rAb;
        if (rWiper !== undefined && rWiper !== null) this._rW = rWiper;
    }
}

module.exports = { AD5243Minimal, AD5243Full };
