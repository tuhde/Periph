'use strict';

const REG_WR = 0x00;
const REG_CR = 0x02;
const CR_MODE_SRAM = 0x80;    // MODE = 1: writes to 0x00 update WR only
const CR_MODE_EEPROM = 0x00;  // MODE = 0: writes to 0x00 update WR and IVR
const MAX_CODE = 127;
const POWER_UP_MS = 3;        // tD: power-up recall of the IVR into WR
const POLL_MS = 1;
const R_TOTAL = 10000.0;      // nominal end-to-end resistance, ±20 %

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _clampCode(code) {
    code = Math.trunc(code);
    if (code < 0) return 0;
    if (code > MAX_CODE) return MAX_CODE;
    return code;
}

/**
 * DS3502 7-bit nonvolatile high-voltage I²C digital potentiometer (Maxim) —
 * minimal interface.
 *
 * A single 128-position potentiometer (10 kΩ end to end, ±20 %) whose
 * terminals RH / RL / RW accept up to 15.5 V (V+ = 4.5–15.5 V) while the logic
 * runs from VCC = 2.5–5.5 V. The wiper position lives in the Wiper Register
 * (WR, SRAM); the Initial Value Register (IVR, EEPROM) holds the power-up
 * position (factory default 0x40). The I²C address is 0x28 (A1 = A0 = GND) to
 * 0x2B (A1 = A0 = VCC).
 *
 * Two registers exist: 0x00 is WR (and, depending on the MODE bit of CR, also
 * IVR; reads always return WR) and 0x02 is the Control Register whose bit 7
 * selects MODE (1 = writes to 0x00 only change WR). The driver runs with
 * MODE = 1 so routine wiper writes never wear the EEPROM.
 */
class DS3502Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte register address).
     * @param {number} [vHigh=1.0] - Voltage applied to RH, in volts.
     * @param {number} [vLow=0.0] - Voltage applied to RL, in volts.
     */
    constructor(connection, vHigh = 1.0, vLow = 0.0) {
        this._conn = connection;
        this._vHigh = vHigh;
        this._vLow = vLow;
        this._code = 0;
    }

    /** 7-bit I²C address with A1 = A0 = GND. */
    static get I2C_ADDRESS() { return 0x28; }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _writeCode(code) {
        code = _clampCode(code);
        await this._writeReg(REG_WR, code);
        this._code = code;
    }

    async _readCode() {
        this._code = (await this._readReg(REG_WR)) & MAX_CODE;
        return this._code;
    }

    _codeForVoltage(volts) {
        const span = this._vHigh - this._vLow;
        if (span === 0) return 0;
        return _clampCode(Math.round((volts - this._vLow) / span * MAX_CODE));
    }

    /**
     * Run the initialization sequence: write CR ← 0x80 (MODE = 1), retrying once
     * after 3 ms if the chip NACKs because it is still in its power-up recall,
     * then read WR. The wiper is left where the IVR put it.
     * @returns {Promise<void>}
     */
    async init() {
        try {
            await this._writeReg(REG_CR, CR_MODE_SRAM);
        } catch (e) {
            await sleep(POWER_UP_MS);
            await this._writeReg(REG_CR, CR_MODE_SRAM);
        }
        await this._readCode();
    }

    /**
     * Set the wiper position (volatile, SRAM only).
     * @param {number} code - Wiper position 0–127 (clamped); 0 = at RL, 127 = at RH.
     * @returns {Promise<void>}
     */
    async setWiper(code) {
        await this._writeCode(code);
    }

    /**
     * Read back the wiper position (WR).
     * @returns {Promise<number>} Wiper position 0–127.
     */
    async getWiper() {
        return this._readCode();
    }

    /**
     * Set the unloaded RW output voltage to the nearest reachable code.
     * @param {number} volts - Target voltage in volts (clamped between vLow and vHigh).
     * @returns {Promise<void>}
     */
    async setVoltage(volts) {
        await this._writeCode(this._codeForVoltage(volts));
    }

    /**
     * Read the unloaded RW voltage for the current wiper code.
     * @returns {Promise<number>} Voltage in volts, `vLow + (vHigh - vLow) * code / 127`.
     */
    async getVoltage() {
        return this._vLow + (this._vHigh - this._vLow) * (await this._readCode()) / MAX_CODE;
    }
}

/**
 * DS3502 full interface — extends Minimal with ratio / resistance helpers,
 * increment, EEPROM storage of the power-up value, MODE control and
 * acknowledge polling.
 */
class DS3502Full extends DS3502Minimal {
    /**
     * Set the wiper by divider ratio V(RW−RL) / V(RH−RL).
     * @param {number} ratio - 0.0–1.0; the code is `round(ratio * 127)`.
     * @returns {Promise<void>}
     */
    async setRatio(ratio) {
        await this._writeCode(Math.round(ratio * MAX_CODE));
    }

    /**
     * Read the divider ratio.
     * @returns {Promise<number>} `code / 127`.
     */
    async getRatio() {
        return (await this._readCode()) / MAX_CODE;
    }

    /**
     * Nominal wiper resistance for the current code (RTOTAL = 10 kΩ ±20 %).
     * @param {string} [terminal='L'] - 'L' for wiper-to-RL, 'H' for wiper-to-RH.
     * @returns {Promise<number>} Resistance in ohms (approximate; excludes the wiper resistance).
     */
    async getResistance(terminal = 'L') {
        if (terminal !== 'L' && terminal !== 'H') throw new RangeError("terminal must be 'L' or 'H'");
        const code = await this._readCode();
        return terminal === 'L' ? code / MAX_CODE * R_TOTAL : (MAX_CODE - code) / MAX_CODE * R_TOTAL;
    }

    /**
     * Move the wiper relative to its current position (SRAM only).
     * @param {number} [steps=1] - Signed step count (negative to decrement).
     * @returns {Promise<number>} The new wiper code, clamped to 0–127.
     */
    async increment(steps = 1) {
        const code = _clampCode((await this._readCode()) + Math.trunc(steps));
        await this._writeCode(code);
        return code;
    }

    /**
     * Write the Initial Value Register (EEPROM power-up position). Sets MODE = 0,
     * writes WR / IVR, waits for the EEPROM write (acknowledge polling, ≤ 20 ms)
     * and restores MODE = 1, also on error. The wiper moves to `code`. Each call
     * counts toward the EEPROM endurance (50 000 writes at 70 °C, 200 000 at 25 °C).
     * @param {number} [code] - Position 0–127 to store; default the current wiper position.
     * @returns {Promise<void>}
     */
    async storeWiper(code) {
        code = code === undefined || code === null ? await this._readCode() : _clampCode(code);
        await this._writeReg(REG_CR, CR_MODE_EEPROM);
        try {
            await this._writeReg(REG_WR, code);
            this._code = code;
            await this.waitReady();
        } finally {
            await this._writeReg(REG_CR, CR_MODE_SRAM);
        }
    }

    /**
     * Select what a write to the wiper register updates (advanced; storeWiper handles it).
     * @param {boolean} eeprom - true for MODE = 0 (writes also program the IVR), false for MODE = 1 (SRAM only).
     * @returns {Promise<void>}
     */
    async setMode(eeprom) {
        await this._writeReg(REG_CR, eeprom ? CR_MODE_EEPROM : CR_MODE_SRAM);
    }

    /**
     * Read the MODE bit of the Control Register.
     * @returns {Promise<boolean>} true if MODE = 0 (writes reach the EEPROM).
     */
    async getMode() {
        return ((await this._readReg(REG_CR)) & CR_MODE_SRAM) === 0;
    }

    /**
     * Acknowledge polling: repeat a read of CR until the chip ACKs.
     * @param {number} [timeoutMs=30] - Maximum time to wait, in milliseconds.
     * @returns {Promise<void>}
     * @throws {Error} if the chip still NACKs after `timeoutMs`.
     */
    async waitReady(timeoutMs = 30) {
        const attempts = Math.floor(timeoutMs / POLL_MS) + 1;
        for (let i = 0; i < attempts; i++) {
            try {
                await this._readReg(REG_CR);
                return;
            } catch (e) {
                await sleep(POLL_MS);
            }
        }
        throw new Error('DS3502 busy: EEPROM write did not finish');
    }

    /**
     * Change the RH / RL voltages used by the conversions (no I²C traffic).
     * @param {number} vHigh - Voltage applied to RH, in volts.
     * @param {number} [vLow=0.0] - Voltage applied to RL, in volts.
     */
    setEndpoints(vHigh, vLow = 0.0) {
        this._vHigh = vHigh;
        this._vLow = vLow;
    }
}

module.exports = { DS3502Minimal, DS3502Full };
