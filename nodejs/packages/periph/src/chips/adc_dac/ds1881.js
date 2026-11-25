'use strict';

const _CMD_CONFIG = 0x80;
const _CFG_VOLATILE = 0x04;
const _CFG_ZC = 0x02;
const _CFG_OPTION2 = 0x01;
const _CFG_MASK = 0x07;
const _CFG_DEFAULT = _CFG_VOLATILE | _CFG_ZC; // volatile, zero-crossing, Option 1
const _MUTE_DB = 80.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _checkChannel(channel) {
    if (channel !== 0 && channel !== 1) throw new RangeError('channel must be 0 or 1');
    return channel;
}

function _mutePosition(option) {
    return option === 1 ? 63 : 33;
}

function _positionToDb(position, option) {
    if (position >= _mutePosition(option)) return _MUTE_DB;
    if (option === 1 || position <= 12) return position;
    if (position <= 24) return 12 + 2 * (position - 12);
    return 36 + 3 * (position - 24);
}

function _nearestPosition(db, option) {
    if (db <= 0) return 0;
    if (option === 1) return Math.min(Math.floor(db + 0.5), 62);
    let best = 0;
    let bestErr = db;
    for (let p = 1; p <= 32; p++) {
        const err = Math.abs(_positionToDb(p, 2) - db);
        if (err < bestErr) { best = p; bestErr = err; }
    }
    return best;
}

/**
 * DS1881 dual nonvolatile audio log-taper digital potentiometer (Maxim) —
 * minimal interface.
 *
 * Two 45 kΩ potentiometers whose wiper positions are steps in an attenuation
 * table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB, position 63
 * = mute); Option 2 (DS1808 compatible) gives 34 positions (12 × 1 dB,
 * 12 × 2 dB, 8 × 3 dB, position 33 = mute). The I²C address is 0x28
 * (A2 = A1 = A0 = 0) to 0x2F.
 *
 * The chip has no register map: a write is one or more command bytes
 * (`00pppppp` wiper 0, `01pppppp` wiper 1, `10xxxVZC` configuration) and a
 * read returns wiper 0, wiper 1 and the configuration register round-robin.
 * The driver therefore uses the plain Connection `write()` / `read()` calls.
 * Wipers and configuration live in EEPROM when V/NV = 0 (50 000 writes); the
 * drivers default to volatile operation. The CE pin is active low and is left
 * to the Connection `enPin` (`enActiveHigh = false`).
 */
class DS1881Minimal {
    /**
     * The constructor makes no bus access; call {@link DS1881Minimal#init} once after power-up.
     * @param {import('../../connection/connection').Connection} connection - I²C connection (0x28–0x2F).
     */
    constructor(connection) {
        this._conn = connection;
        this._config = _CFG_DEFAULT;
        this._option = 1;
    }

    /**
     * Run the initialization sequence: wait the 1 ms power-up time, read the
     * three bytes and rewrite the configuration (volatile, zero-crossing on,
     * Option 1) only if it differs, waiting for the EEPROM write that this
     * triggers. The wipers are not touched.
     * @returns {Promise<void>}
     */
    async init() {
        await _sleep(1);
        const raw = await this._conn.read(3);
        if ((raw[2] & _CFG_MASK) !== _CFG_DEFAULT) {
            await this._conn.write(Buffer.from([_CMD_CONFIG | _CFG_DEFAULT]));
            await this._waitReady(0.05);
        }
        this._config = _CFG_DEFAULT;
        this._option = 1;
    }

    async _waitReady(timeoutS) {
        const attempts = Math.floor(timeoutS / 0.001) + 1;
        for (let i = 0; i < attempts; i++) {
            try {
                await this._conn.read(1);
                return;
            } catch (e) {
                await _sleep(1);
            }
        }
        throw new Error('DS1881 busy: EEPROM write did not finish');
    }

    async _writeCommands(bytes) {
        await this._conn.write(Buffer.from(bytes));
        if (!(this._config & _CFG_VOLATILE)) await this._waitReady(0.05);
    }

    async _writePosition(channel, position) {
        _checkChannel(channel);
        const mute = _mutePosition(this._option);
        position = Math.trunc(position);
        if (position < 0) position = 0;
        else if (position > mute) position = mute;
        await this._writeCommands([(channel << 6) | position]);
    }

    async _readAll() {
        const raw = await this._conn.read(3);
        return [raw[0] & 0x3F, raw[1] & 0x3F, raw[2]];
    }

    _dbToPos(db) {
        return _nearestPosition(db, this._option);
    }

    /**
     * Set a channel's attenuation to the nearest position.
     * @param {number} channel - 0 or 1.
     * @param {number} db - Attenuation in dB (positive = quieter); rounded to the nearest step, clamped to 0…62 dB (Option 1).
     * @returns {Promise<void>}
     */
    async setAttenuation(channel, db) {
        _checkChannel(channel);
        await this._writePosition(channel, this._dbToPos(db));
    }

    /**
     * Read a channel's attenuation.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Attenuation in dB (80 for mute).
     */
    async getAttenuation(channel) {
        return _positionToDb(await this.getPosition(channel), this._option);
    }

    /**
     * Mute a channel (position 63 in Option 1).
     * @param {number} channel - 0 or 1.
     * @returns {Promise<void>}
     */
    async mute(channel) {
        await this._writePosition(channel, _mutePosition(this._option));
    }

    /**
     * Write a raw wiper position.
     * @param {number} channel - 0 or 1.
     * @param {number} position - 0–63 (63 = mute); clamped.
     * @returns {Promise<void>}
     */
    async setPosition(channel, position) {
        await this._writePosition(channel, position);
    }

    /**
     * Read a wiper position with the three-byte round-robin read.
     * @param {number} channel - 0 or 1.
     * @returns {Promise<number>} Position (low 6 bits of the read-back byte).
     */
    async getPosition(channel) {
        _checkChannel(channel);
        return (await this._readAll())[channel];
    }
}

DS1881Minimal.I2C_ADDRESS = 0x28;

/**
 * DS1881 full interface — extends Minimal with stereo writes, Option 2,
 * zero-crossing and EEPROM control, and table helpers.
 *
 * The driver caches the configuration (volatile, zero-crossing, option);
 * `init()` and `getConfig()` refresh it from the chip.
 */
class DS1881Full extends DS1881Minimal {
    _dbToPos(db) {
        if (db >= 70) return _mutePosition(this._option);
        return _nearestPosition(db, this._option);
    }

    /**
     * Write both wipers in one transaction (no STOP between them).
     * @param {number} position0 - Position for channel 0 (clamped).
     * @param {number} position1 - Position for channel 1 (clamped).
     * @returns {Promise<void>}
     */
    async setBoth(position0, position1) {
        const mute = _mutePosition(this._option);
        const data = [position0, position1].map((p, channel) => {
            p = Math.max(0, Math.min(Math.trunc(p), mute));
            return (channel << 6) | p;
        });
        await this._writeCommands(data);
    }

    /**
     * Set both channels' attenuation (stereo volume).
     * @param {number} db0 - Attenuation of channel 0 in dB.
     * @param {number} [db1] - Attenuation of channel 1 in dB; defaults to `db0`.
     * @returns {Promise<void>}
     */
    async setAttenuationBoth(db0, db1) {
        if (db1 === undefined || db1 === null) db1 = db0;
        await this.setBoth(this._dbToPos(db0), this._dbToPos(db1));
    }

    /**
     * Write the configuration byte if it differs from the cached value. The
     * configuration is stored in EEPROM, so a write waits for acknowledge
     * polling. The wipers are not rewritten — positions keep their numeric
     * value when the option changes.
     * @param {number} [option=1] - 1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute, DS1808 compatible).
     * @param {boolean} [zeroCrossing=true] - Enable zero-crossing detection.
     * @param {boolean} [nonvolatile=false] - Store wiper writes in EEPROM and restore them at power-up (50 000 writes).
     * @returns {Promise<void>}
     */
    async configure(option = 1, zeroCrossing = true, nonvolatile = false) {
        if (option !== 1 && option !== 2) throw new RangeError('option must be 1 or 2');
        let config = 0;
        if (!nonvolatile) config |= _CFG_VOLATILE;
        if (zeroCrossing) config |= _CFG_ZC;
        if (option === 2) config |= _CFG_OPTION2;
        if (config !== this._config) {
            await this._conn.write(Buffer.from([_CMD_CONFIG | config]));
            await this._waitReady(0.05);
        }
        this._config = config;
        this._option = option;
    }

    /**
     * Read the configuration register (fresh read; refreshes the cache).
     * @returns {Promise<{option: number, zeroCrossing: boolean, nonvolatile: boolean, raw: number}>}
     */
    async getConfig() {
        const raw = (await this._readAll())[2];
        this._config = raw & _CFG_MASK;
        this._option = (raw & _CFG_OPTION2) ? 2 : 1;
        return {
            option: this._option,
            zeroCrossing: Boolean(raw & _CFG_ZC),
            nonvolatile: !(raw & _CFG_VOLATILE),
            raw,
        };
    }

    /**
     * Switch between Option 1 and 2 (positions are not converted).
     * @param {number} option - 1 or 2.
     * @returns {Promise<void>}
     */
    async setOption(option) {
        await this.configure(option, Boolean(this._config & _CFG_ZC), !(this._config & _CFG_VOLATILE));
    }

    /**
     * Enable or disable zero-crossing detection.
     * @param {boolean} enabled - true to enable.
     * @returns {Promise<void>}
     */
    async setZeroCrossing(enabled) {
        await this.configure(this._option, enabled, !(this._config & _CFG_VOLATILE));
    }

    /**
     * Select where wiper writes go (V/NV bit).
     * @param {boolean} enabled - true: subsequent wiper writes are stored in EEPROM and restored at power-up (50 000 writes).
     * @returns {Promise<void>}
     */
    async setNonvolatile(enabled) {
        await this.configure(this._option, Boolean(this._config & _CFG_ZC), enabled);
    }

    /**
     * Persist the current wiper positions to EEPROM: read both positions,
     * switch to nonvolatile mode if needed, rewrite both wiper bytes in one
     * transaction, wait by acknowledge polling and restore volatile mode if
     * it was active. The EEPROM is rated for only 50 000 writes.
     * @returns {Promise<void>}
     */
    async store() {
        const [position0, position1] = await this._readAll();
        const wasNv = !(this._config & _CFG_VOLATILE);
        const zc = Boolean(this._config & _CFG_ZC);
        const option = this._option;
        if (!wasNv) await this.configure(option, zc, true);
        await this._writeCommands([position0, 0x40 | position1]);
        if (!wasNv) await this.configure(option, zc, false);
    }

    /**
     * Acknowledge-poll until the device answers (it NACKs while an EEPROM
     * write is in progress).
     * @param {number} [timeoutS=0.05] - Maximum time to wait in seconds.
     * @returns {Promise<void>} Rejects if the device is still busy after the timeout.
     */
    async waitReady(timeoutS = 0.05) {
        await this._waitReady(timeoutS);
    }

    /**
     * Read wiper 0, wiper 1 and the configuration byte in one transaction.
     * @returns {Promise<number[]>} `[position0, position1, configByte]`.
     */
    async readAll() {
        return this._readAll();
    }

    /**
     * Attenuation of a position in the current option.
     * @param {number} position - Wiper position.
     * @returns {number} dB (80 for mute and above).
     */
    positionToDb(position) {
        return _positionToDb(Math.trunc(position), this._option);
    }

    /**
     * Nearest position for an attenuation in the current option.
     * @param {number} db - Attenuation in dB; ≥ 70 dB maps to mute, negative to 0.
     * @returns {number} Wiper position.
     */
    dbToPosition(db) {
        return this._dbToPos(db);
    }

    /**
     * Last non-mute position of the current option.
     * @returns {number} 62 (Option 1) or 32 (Option 2).
     */
    maxPosition() {
        return _mutePosition(this._option) - 1;
    }

    /**
     * Volatile, zero-crossing on, Option 1 and both wipers to mute, sent as
     * one transaction (configuration byte, then both wiper bytes); waits for
     * the configuration EEPROM write.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._conn.write(Buffer.from([_CMD_CONFIG | _CFG_DEFAULT, 0x3F, 0x7F]));
        this._config = _CFG_DEFAULT;
        this._option = 1;
        await this._waitReady(0.05);
    }
}

module.exports = { DS1881Minimal, DS1881Full };
