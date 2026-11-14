'use strict';

const _RDAC = [0x01, 0x03];
const _EEMEM_FLAG = 0x20;
const _TOL_INT = [0x3A, 0x3E];

const _Q_NOP = 0x80;
const _Q_RESTORE = 0x88;
const _Q_STORE = 0x90;
const _Q_HALVE = 0x98;
const _Q_DEC_STEP = 0xA8;
const _Q_INC_6DB = 0xC0;
const _Q_INC_STEP = 0xD0;
const _Q_DEC_6DB_ALL = 0xA0;
const _Q_DEC_STEP_ALL = 0xB0;
const _Q_RESET = 0xB8;
const _Q_INC_6DB_ALL = 0xC8;
const _Q_INC_STEP_ALL = 0xD8;

const _POLL_MS = 2;
const _POLL_TIMEOUT_MS = 100;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
 * AD5252 dual 256-position I²C digital potentiometer with EEMEM (Analog
 * Devices) — minimal interface.
 *
 * Two independent 3-terminal potentiometers (A, W, B). The chip has no
 * register map: the instruction byte works as a one-byte register pointer
 * (RDAC1 = 0x01 for channel 0, RDAC3 = 0x03 for channel 1), so the driver
 * uses a RegisterConnection with a 1-byte register width. The I²C address is
 * 0x2C–0x2F (AD1, AD0 pins). Wipers power up from EEMEM (midscale 128 until
 * a value is stored).
 */
class AD5252Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x2C–0x2F).
     * @param {number} [rAb=10000] - End-to-end resistance RAB in ohms.
     * @param {number} [rWiper=75] - Wiper resistance RW in ohms.
     */
    constructor(connection, rAb = 10000.0, rWiper = 75.0) {
        this._conn = connection;
        this._rAb = rAb;
        this._rW = rWiper;
    }

    async _writeCode(channel, code) {
        _checkChannel(channel);
        await this._conn.writeReg(_RDAC[channel], _clampCode(code));
    }

    async _readCode(channel) {
        _checkChannel(channel);
        const buf = await this._conn.readReg(_RDAC[channel], 1);
        return buf[0];
    }

    /**
     * Prepare the driver. The AD5252 has no identity register and needs no
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
        await this._writeCode(channel, Math.floor((ohms - this._rW) / this._rAb * 256 + 0.5));
    }

    /**
     * Read the W–B resistance for the current wiper code.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Resistance in ohms, `D / 256 * RAB + RW`.
     */
    async getResistance(channel) {
        return (await this._readCode(channel)) / 256 * this._rAb + this._rW;
    }
}

/**
 * AD5252 full interface — extends Minimal with ratio and W–A helpers, quick
 * commands (±1 step, ±6 dB), EEMEM store/restore/reset, user EEMEM and the
 * factory RAB tolerance. EEMEM writes poll with the NOP quick command until
 * the chip acknowledges again (timeout 100 ms).
 */
class AD5252Full extends AD5252Minimal {
    async _quickByte(byte) {
        await this._conn.write(Buffer.from([byte]));
    }

    async _quick(base, baseAll, channel) {
        if (channel === undefined || channel === null) {
            await this._quickByte(baseAll);
            return;
        }
        _checkChannel(channel);
        await this._quickByte(base | _RDAC[channel]);
    }

    async _waitReady() {
        const start = Date.now();
        for (;;) {
            try {
                await this._quickByte(_Q_NOP);
                return;
            } catch (e) {
                if (Date.now() - start >= _POLL_TIMEOUT_MS) {
                    throw new Error('AD5252 busy: EEMEM write did not finish');
                }
                await _sleep(_POLL_MS);
            }
        }
    }

    /**
     * Write both wipers in one consecutive-write transaction.
     * @param {number} code0 - Channel 0 wiper position 0–255.
     * @param {number} code1 - Channel 1 wiper position 0–255.
     * @returns {Promise<void>}
     */
    async setBoth(code0, code1) {
        await this._conn.writeReg(_RDAC[0], Buffer.from([_clampCode(code0), _clampCode(code1)]));
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     * @param {number} channel - 0 or 1.
     * @param {number} ratio - 0.0–1.0; the code is round(ratio * 256) clamped to 255.
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
        const steps = Math.floor((ohms - this._rW) / this._rAb * 256 + 0.5);
        await this._writeCode(channel, 256 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Resistance in ohms, `(256 - D) / 256 * RAB + RW`.
     */
    async getResistanceWa(channel) {
        return (256 - (await this._readCode(channel))) / 256 * this._rAb + this._rW;
    }

    /**
     * Increment the wiper by one step (quick command).
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     */
    async increment(channel) {
        await this._quick(_Q_INC_STEP, _Q_INC_STEP_ALL, channel);
    }

    /**
     * Decrement the wiper by one step (quick command).
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     */
    async decrement(channel) {
        await this._quick(_Q_DEC_STEP, _Q_DEC_STEP_ALL, channel);
    }

    /**
     * Increase by 6 dB — the code doubles (quick command).
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     */
    async double(channel) {
        await this._quick(_Q_INC_6DB, _Q_INC_6DB_ALL, channel);
    }

    /**
     * Decrease by 6 dB — the code halves (quick command).
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     */
    async halve(channel) {
        await this._quick(_Q_HALVE, _Q_DEC_6DB_ALL, channel);
    }

    /**
     * Store the wiper in EEMEM, then wait for the ≈ 26 ms write cycle.
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not acknowledge again within 100 ms.
     */
    async store(channel) {
        if (channel === undefined || channel === null) {
            await this._quick(_Q_STORE, 0, 0);
            await this._waitReady();
            await this._quick(_Q_STORE, 0, 1);
        } else {
            await this._quick(_Q_STORE, 0, channel);
        }
        await this._waitReady();
    }

    /**
     * Restore the wiper from EEMEM, then send a NOP to return to idle.
     * @param {number} [channel] - 0 or 1; omit for both channels.
     * @returns {Promise<void>}
     */
    async restore(channel) {
        if (channel === undefined || channel === null) {
            await this._quick(_Q_RESTORE, 0, 0);
            await this._quick(_Q_RESTORE, 0, 1);
        } else {
            await this._quick(_Q_RESTORE, 0, channel);
        }
        await this.nop();
    }

    /**
     * Quick-command reset: restore both wipers from EEMEM and wait.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not acknowledge again within 100 ms.
     */
    async reset() {
        await this._quickByte(_Q_RESET);
        await this._waitReady();
    }

    /**
     * Read a user EEMEM byte.
     * @param {number} index - 4–15.
     * @returns {Promise<number>} The stored byte.
     */
    async readEemem(index) {
        if (index < 4 || index > 15) throw new RangeError('index must be 4..15');
        return (await this._conn.readReg(_EEMEM_FLAG | index, 1))[0];
    }

    /**
     * Write a user EEMEM byte and wait for the ≈ 26 ms write cycle.
     * @param {number} index - 4–15.
     * @param {number} value - Byte 0–255.
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not acknowledge again within 100 ms.
     */
    async writeEemem(index, value) {
        if (index < 4 || index > 15) throw new RangeError('index must be 4..15');
        await this._conn.writeReg(_EEMEM_FLAG | index, value & 0xFF);
        await this._waitReady();
    }

    /**
     * Read the factory-stored RAB tolerance of a channel.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Tolerance in percent (sign-magnitude integer byte plus decimal byte / 256).
     */
    async readTolerance(channel) {
        _checkChannel(channel);
        const raw = await this._conn.readReg(_TOL_INT[channel], 2);
        const value = (raw[0] & 0x7F) + raw[1] / 256;
        return (raw[0] & 0x80) ? -value : value;
    }

    /**
     * Actual RAB of a channel from its stored tolerance.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} `rAb * (1 + tolerance / 100)` in ohms.
     */
    async rabActual(channel) {
        return this._rAb * (1 + (await this.readTolerance(channel)) / 100);
    }

    /**
     * Send the NOP quick command (0x80), returning the chip to idle.
     * @returns {Promise<void>}
     */
    async nop() {
        await this._quickByte(_Q_NOP);
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

/** Default 7-bit I²C address (AD1 = AD0 = GND); 0x2C–0x2F are possible. */
AD5252Minimal.I2C_ADDRESS = 0x2C;

module.exports = { AD5252Minimal, AD5252Full };
