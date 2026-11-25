'use strict';

const _INSTR_RS = 0x40;
const _INSTR_SD = 0x20;
const _MIDSCALE = 0x80;

function _clampCode(code) {
    code = Math.trunc(code);
    if (code < 0) return 0;
    if (code > 255) return 255;
    return code;
}

/**
 * CAT5171 256-position I²C digital potentiometer (ON Semiconductor) —
 * minimal interface.
 *
 * A single 3-terminal potentiometer (A, W, B). The chip has no register map:
 * a write is `[instruction, data]` where the instruction byte carries the
 * midscale-reset flag (RS, bit 6) and the shutdown flag (SD, bit 5); a read is
 * a bare one-byte read returning the wiper register. The driver therefore
 * uses the plain Connection `write()` / `read()` calls. The I²C address is
 * 0x2C (AD0 = GND) or 0x2D (AD0 = VDD). The wiper is volatile (power-up
 * midscale 0x80).
 */
class CAT5171Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - I²C connection (0x2C or 0x2D).
     * @param {number} [rAb=100000] - End-to-end resistance RAB in ohms.
     * @param {number} [rWiper=50] - Wiper resistance RW in ohms.
     */
    constructor(connection, rAb = 100000.0, rWiper = 50.0) {
        this._conn = connection;
        this._rAb = rAb;
        this._rW = rWiper;
        this._code = _MIDSCALE;
        this._sd = false;
    }

    async _write(instruction, code) {
        await this._conn.write(Buffer.from([instruction, code]));
    }

    async _writeCode(code) {
        code = _clampCode(code);
        await this._write(this._sd ? _INSTR_SD : 0, code);
        this._code = code;
    }

    async _readCode() {
        const buf = await this._conn.read(1);
        this._code = buf[0];
        return buf[0];
    }

    /**
     * Prepare the driver. The CAT5171 has no identity register and needs no
     * initialisation writes, so this performs no I²C traffic.
     * @returns {Promise<void>}
     */
    async init() {}

    /**
     * Set the wiper position.
     * @param {number} code - Wiper position 0–255 (clamped).
     * @returns {Promise<void>}
     */
    async setWiper(code) {
        await this._writeCode(code);
    }

    /**
     * Read back the wiper position with a single read transaction.
     * @returns {Promise<number>} Wiper position 0–255.
     */
    async getWiper() {
        return this._readCode();
    }

    /**
     * Set the W–B resistance to the nearest reachable code.
     * @param {number} ohms - Target W–B resistance in ohms (clamped to the reachable range).
     * @returns {Promise<void>}
     */
    async setResistance(ohms) {
        await this._writeCode(Math.floor((ohms - this._rW) / this._rAb * 256 + 0.5));
    }

    /**
     * Read the W–B resistance for the current wiper code.
     * @returns {Promise<number>} Resistance in ohms, `D / 256 * RAB + RW`.
     */
    async getResistance() {
        return (await this._readCode()) / 256 * this._rAb + this._rW;
    }
}

/** 7-bit I²C address with AD0 = GND. */
CAT5171Minimal.I2C_ADDRESS = 0x2C;
/** 7-bit I²C address with AD0 = VDD. */
CAT5171Minimal.I2C_ADDRESS_AD0_HIGH = 0x2D;

/**
 * CAT5171 full interface — extends Minimal with ratio and W–A helpers,
 * increment/decrement, midscale, shutdown and reset. The shutdown state is
 * write-only on the chip, so the driver tracks it.
 */
class CAT5171Full extends CAT5171Minimal {
    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     * @param {number} ratio - 0.0–1.0; code is round(ratio × 256) clamped to 255.
     * @returns {Promise<void>}
     */
    async setRatio(ratio) {
        await this._writeCode(Math.floor(ratio * 256 + 0.5));
    }

    /**
     * Read the divider ratio.
     * @returns {Promise<number>} `code / 256`.
     */
    async getRatio() {
        return (await this._readCode()) / 256;
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     * @param {number} ohms - Target W–A resistance in ohms.
     * @returns {Promise<void>}
     */
    async setResistanceWa(ohms) {
        const steps = Math.floor((ohms - this._rW) / this._rAb * 256 + 0.5);
        await this._writeCode(256 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     * @returns {Promise<number>} Resistance in ohms, `(256 − D) / 256 * RAB + RW`.
     */
    async getResistanceWa() {
        return (256 - (await this._readCode())) / 256 * this._rAb + this._rW;
    }

    /**
     * Move the wiper relative to its current position.
     * @param {number} [steps=1] - Signed step count (negative to decrement).
     * @returns {Promise<number>} The new wiper code, clamped to 0–255.
     */
    async increment(steps = 1) {
        const code = _clampCode((await this._readCode()) + Math.trunc(steps));
        await this._writeCode(code);
        return code;
    }

    /**
     * Move the wiper to midscale (0x80) with the RS instruction bit; SD is
     * kept if the device is shut down.
     * @returns {Promise<void>}
     */
    async midscale() {
        await this._write(_INSTR_RS | (this._sd ? _INSTR_SD : 0), _MIDSCALE);
        this._code = _MIDSCALE;
    }

    /**
     * Shut down: terminal A opens and the wiper shorts to B. The wiper
     * register is kept.
     * @returns {Promise<void>}
     */
    async shutdown() {
        this._sd = true;
        await this._write(_INSTR_SD, this._code);
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked current code.
     * @returns {Promise<void>}
     */
    async resume() {
        this._sd = false;
        await this._write(0, this._code);
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     * @returns {boolean} True if the device was shut down by this driver.
     */
    isShutdown() {
        return this._sd;
    }

    /**
     * Midscale reset (RS = 1, SD = 0): wiper to 0x80, shutdown cleared.
     * @returns {Promise<void>}
     */
    async reset() {
        this._sd = false;
        await this._write(_INSTR_RS, _MIDSCALE);
        this._code = _MIDSCALE;
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

module.exports = { CAT5171Minimal, CAT5171Full };
