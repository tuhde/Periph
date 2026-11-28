'use strict';

/*
 * RRH62000 all-in-one air-quality module (Renesas).
 *
 * Laser particulate-matter sensor, HS40xx temperature/humidity sensor and ZMOD4410 TVOC/IAQ
 * sensor behind a fan. Over I²C (address 0x69, SEL pin to GND) the module is register
 * addressed: a one-byte command code is the register pointer; reading 0x00 returns the
 * 37-byte measurement block with a CRC-8. The module must not be accessed more often than
 * once per second (tRD); the driver rate-limits itself.
 */

const CMD_READ = 0x00;
const CMD_DATA = 0x40;
const CMD_SLEEP = 0x50;
const CMD_CLEAN = 0x51;
const CMD_RESET = 0x52;
const CMD_MAVE = 0x53;
const CMD_TINTC_H = 0x5A;
const CMD_TINTC_L = 0x5B;
const CMD_TCLEAN = 0x5C;
const CMD_SPEEDFAN = 0x63;
const CMD_RMOX = 0x71;
const CMD_UID = 0x72;
const CMD_ARGVER = 0x73;
const CMD_CSTATUS = 0x74;
const CMD_FWVER = 0x75;

const BLOCK_LENGTH = 37;
const _STARTUP_MS = 1000;
const _VALID_MS = 3300;
const _DATA_TIMEOUT_MS = 4000;

const _sleep = (ms) => (ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve());
const _now = () => Date.now();

/**
 * CRC-8 of the measurement block (polynomial 0x31, init 0xFF, no reflection, no final XOR).
 * @param {Buffer|number[]} data - Bytes to check (bytes 0–35 of the block).
 * @returns {number} The CRC (`crc8([0xBE, 0xEF]) === 0x92`).
 */
function crc8(data) {
    let crc = 0xFF;
    for (const b of data) {
        crc ^= b;
        for (let i = 0; i < 8; i++) crc = crc & 0x80 ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

/** RRH62000 data error: CRC mismatch, invalid (start-up) sample or timeout. */
class RRH62000Error extends Error {
    constructor(message) {
        super(message);
        this.name = 'RRH62000Error';
    }
}

/**
 * RRH62000 air-quality module (Renesas) — minimal interface.
 *
 * One call returns PM1/PM2.5/PM10 (KCl reference), temperature, humidity, TVOC, estimated CO₂
 * and the UBA IAQ index. The module defaults (10 moving averages, fan 86 %, cleaning every
 * 7 days for 10 s) are kept. The start-up time (1 s for I²C, 3.3 s for valid data) is
 * counted from construction, `wake()` and `softReset()`. The constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection -
 *   RegisterConnection (1-byte register addresses) bound to 0x69.
 * @param {{accessIntervalMs?: number}} [options] - `accessIntervalMs`: minimum spacing between
 *   two accesses (the module needs ≥ 1000 ms; lower it only for mocks).
 */
class RRH62000Minimal {
    constructor(connection, { accessIntervalMs = 1000 } = {}) {
        this._connection = connection;
        this._interval = accessIntervalMs;
        this._lastAccess = 0;
        this._validAt = 0;
        this._dataTimeoutMs = _DATA_TIMEOUT_MS;
        this._markStartup();
    }

    _markStartup() {
        const now = _now();
        const scale = this._interval / 1000;
        this._lastAccess = now + Math.round(_STARTUP_MS * scale) - this._interval;
        this._validAt = now + Math.round(_VALID_MS * scale);
    }

    async _waitAccess() {
        await _sleep(this._interval - (_now() - this._lastAccess));
    }

    async _read(cmd, length) {
        await this._waitAccess();
        try {
            return Buffer.from(await this._connection.readReg(cmd, length));
        } finally {
            this._lastAccess = _now();
        }
    }

    async _write(cmd, value) {
        await this._waitAccess();
        try {
            await this._connection.writeReg(cmd, Buffer.from([value & 0xFF]));
        } finally {
            this._lastAccess = _now();
        }
    }

    /**
     * Wait for the start-up time (1 s from construction) and read the firmware version as a
     * presence check.
     * @returns {Promise<void>}
     */
    async init() {
        await this._read(CMD_FWVER, 2);
    }

    /**
     * Wait for a new sample (DATA polled once per second, up to 4 s) and return the main values.
     * @returns {Promise<{pm1_ug_m3: number, pm2_5_ug_m3: number, pm10_ug_m3: number,
     *   temperature_c: number, humidity_rh: number, tvoc: number, eco2_ppm: number, iaq: number}>}
     *   PM in µg/m³ (KCl reference), °C, %RH, TVOC in µg/m³, eCO₂ in ppm, IAQ 1.00–5.00.
     * @throws {RRH62000Error} On a timeout, a CRC mismatch or a start-up (all 0xFF) block.
     */
    async read() {
        const m = await this._readBlock(true);
        return {
            pm1_ug_m3: m.pm1_ug_m3,
            pm2_5_ug_m3: m.pm2_5_ug_m3,
            pm10_ug_m3: m.pm10_ug_m3,
            temperature_c: m.temperature_c,
            humidity_rh: m.humidity_rh,
            tvoc: m.tvoc,
            eco2_ppm: m.eco2_ppm,
            iaq: m.iaq,
        };
    }

    async _waitData() {
        const start = _now();
        for (;;) {
            if ((await this._read(CMD_DATA, 1))[0] === 0x01) return;
            if (_now() - start >= this._dataTimeoutMs) throw new RRH62000Error('RRH62000 no new data within 4 s');
        }
    }

    async _readBlock(wait) {
        await _sleep(this._validAt - _now());
        if (wait) await this._waitData();
        return RRH62000Minimal.decode(await this._read(CMD_READ, BLOCK_LENGTH));
    }

    /**
     * Decode a 37-byte measurement block.
     * @param {Buffer|number[]} block - The raw block read from 0x00.
     * @returns {object} All values (see {@link RRH62000Full#readAll}).
     * @throws {RRH62000Error} On a wrong length, an all-0xFF block or a CRC mismatch.
     */
    static decode(block) {
        const b = Buffer.from(block);
        if (b.length !== BLOCK_LENGTH) throw new RRH62000Error('RRH62000 block must be 37 bytes');
        if (b.subarray(0, 36).every((x) => x === 0xFF)) throw new RRH62000Error('RRH62000 sample invalid (start-up)');
        if (crc8(b.subarray(0, 36)) !== b[36]) throw new RRH62000Error('RRH62000 CRC error');
        const w = (i) => b.readUInt16BE(i);
        const status = b[0] | (b[1] << 8);
        return {
            status,
            high_concentration: Boolean(status & 0x01),
            dust_accumulation: Boolean(status & 0x02),
            fan_speed: Boolean(status & 0x04),
            fan: Boolean(status & 0x08),
            nc_0_3: w(2) / 10,
            nc_0_5: w(4) / 10,
            nc_1: w(6) / 10,
            nc_2_5: w(8) / 10,
            nc_4: w(10) / 10,
            pm1_ug_m3: w(12) / 10,
            pm2_5_ug_m3: w(14) / 10,
            pm10_ug_m3: w(16) / 10,
            pm1_smoke_ug_m3: w(18) / 10,
            pm2_5_smoke_ug_m3: w(20) / 10,
            pm10_smoke_ug_m3: w(22) / 10,
            temperature_c: b.readInt16BE(24) / 100,
            humidity_rh: w(26) / 100,
            tvoc: w(28) * 10,
            eco2_ppm: w(30),
            iaq: w(32) / 100,
        };
    }
}

/**
 * RRH62000 full interface — extends RRH62000Minimal with the complete block, status flags,
 * particle bins, sleep, fan cleaning, reset, settings (moving average, fan speed, cleaning
 * interval and time), MOX resistance and identification.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection -
 *   RegisterConnection bound to 0x69.
 * @param {{accessIntervalMs?: number}} [options] - Minimum spacing between two accesses.
 */
class RRH62000Full extends RRH62000Minimal {
    /**
     * Wait for a new sample and return the complete decoded block.
     * @returns {Promise<object>} `status` (raw word), `high_concentration`, `dust_accumulation`,
     *   `fan_speed`, `fan` (flags), `nc_0_3`, `nc_0_5`, `nc_1`, `nc_2_5`, `nc_4` (cm⁻³),
     *   `pm1_ug_m3`, `pm2_5_ug_m3`, `pm10_ug_m3` (KCl), `pm1_smoke_ug_m3`, `pm2_5_smoke_ug_m3`,
     *   `pm10_smoke_ug_m3` (cigarette smoke), `temperature_c`, `humidity_rh`, `tvoc` (µg/m³),
     *   `eco2_ppm`, `iaq`.
     * @throws {RRH62000Error} On a timeout, a CRC mismatch or a start-up block.
     */
    async readAll() {
        return this._readBlock(true);
    }

    /**
     * Read the current block (without waiting for new data) and decode its status word.
     * @returns {Promise<{raw: number, high_concentration: boolean, dust_accumulation: boolean,
     *   fan_speed: boolean, fan: boolean}>}
     */
    async readStatus() {
        const m = await this._readBlock(false);
        return {
            raw: m.status,
            high_concentration: m.high_concentration,
            dust_accumulation: m.dust_accumulation,
            fan_speed: m.fan_speed,
            fan: m.fan,
        };
    }

    /**
     * Read the current block (without waiting for new data) and return the particle values.
     * @returns {Promise<object>} The six PM values (µg/m³, KCl and smoke) and the five
     *   number-concentration bins (cm⁻³).
     */
    async readParticles() {
        const m = await this._readBlock(false);
        const out = {};
        for (const k of ['pm1_ug_m3', 'pm2_5_ug_m3', 'pm10_ug_m3', 'pm1_smoke_ug_m3', 'pm2_5_smoke_ug_m3',
            'pm10_smoke_ug_m3', 'nc_0_3', 'nc_0_5', 'nc_1', 'nc_2_5', 'nc_4']) out[k] = m[k];
        return out;
    }

    /**
     * Check the DATA flag (0x40).
     * @returns {Promise<boolean>} True when new data has arrived.
     */
    async dataReady() {
        return (await this._read(CMD_DATA, 1))[0] === 0x01;
    }

    /**
     * Put the module to sleep (0x50 ← 0x00).
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._write(CMD_SLEEP, 0x00);
    }

    /**
     * Wake the module up (0x50 ← 0x80) and restart the start-up timers.
     * @returns {Promise<void>}
     */
    async wake() {
        await this._write(CMD_SLEEP, 0x80);
        this._markStartup();
    }

    /**
     * Start the fan dust-cleaning (0x51 ← 0x01).
     * @returns {Promise<void>}
     */
    async startCleaning() {
        await this._write(CMD_CLEAN, 0x01);
    }

    /**
     * Read the cleaning status (0x74).
     * @returns {Promise<boolean>} True when the cleaning is completed.
     */
    async cleaningDone() {
        return (await this._read(CMD_CSTATUS, 1))[0] === 0x01;
    }

    /**
     * Reset the module (0x52 ← 0x81, same as power-on) and restart the start-up timers.
     * A missing acknowledge during the reset is ignored.
     * @returns {Promise<void>}
     */
    async softReset() {
        try {
            await this._write(CMD_RESET, 0x81);
        } catch (e) {
            // the module may not acknowledge while resetting
        }
        this._markStartup();
    }

    /**
     * Set the number of moving averages (0x53).
     * @param {number} count - 1–60 (default 10).
     * @returns {Promise<void>}
     * @throws {RangeError} If count is out of range.
     */
    async setMovingAverage(count) {
        if (!Number.isInteger(count) || count < 1 || count > 60) throw new RangeError('count must be 1..60');
        await this._write(CMD_MAVE, count);
    }

    /**
     * Read the number of moving averages.
     * @returns {Promise<number>} 1–60.
     */
    async getMovingAverage() {
        return (await this._read(CMD_MAVE, 1))[0];
    }

    /**
     * Set the fan speed (0x63).
     * @param {number} percent - 60–100 % (default 86).
     * @returns {Promise<void>}
     * @throws {RangeError} If percent is out of range.
     */
    async setFanSpeed(percent) {
        if (!Number.isInteger(percent) || percent < 60 || percent > 100) throw new RangeError('percent must be 60..100');
        await this._write(CMD_SPEEDFAN, percent);
    }

    /**
     * Read the fan speed.
     * @returns {Promise<number>} Percent (60–100).
     */
    async getFanSpeed() {
        return (await this._read(CMD_SPEEDFAN, 1))[0];
    }

    /**
     * Set the automatic cleaning interval (0x5A/0x5B, 16-bit count of 30 s units).
     * @param {number} seconds - 0–1 814 400 s, a multiple of 30 (default 604 800 = 7 days).
     * @returns {Promise<void>}
     * @throws {RangeError} If seconds is out of range or not a multiple of 30.
     */
    async setCleaningInterval(seconds) {
        if (!Number.isInteger(seconds) || seconds < 0 || seconds > 1814400 || seconds % 30) {
            throw new RangeError('seconds must be 0..1814400 and a multiple of 30');
        }
        const units = seconds / 30;
        await this._write(CMD_TINTC_H, units >> 8);
        await this._write(CMD_TINTC_L, units & 0xFF);
    }

    /**
     * Read the automatic cleaning interval.
     * @returns {Promise<number>} Seconds.
     */
    async getCleaningInterval() {
        const hi = (await this._read(CMD_TINTC_H, 1))[0];
        const lo = (await this._read(CMD_TINTC_L, 1))[0];
        return ((hi << 8) | lo) * 30;
    }

    /**
     * Set the fan auto-cleaning time (0x5C).
     * @param {number} seconds - 0–60 s (default 10).
     * @returns {Promise<void>}
     * @throws {RangeError} If seconds is out of range.
     */
    async setCleaningTime(seconds) {
        if (!Number.isInteger(seconds) || seconds < 0 || seconds > 60) throw new RangeError('seconds must be 0..60');
        await this._write(CMD_TCLEAN, seconds);
    }

    /**
     * Read the fan auto-cleaning time.
     * @returns {Promise<number>} Seconds (0–60).
     */
    async getCleaningTime() {
        return (await this._read(CMD_TCLEAN, 1))[0];
    }

    /**
     * Read the MOX sensor resistance (0x71, 4 bytes big-endian).
     * @returns {Promise<number>} Ohms.
     */
    async readMoxResistance() {
        return (await this._read(CMD_RMOX, 4)).readUInt32BE(0);
    }

    /**
     * Read the unique ID (0x72).
     * @returns {Promise<Buffer>} 6 bytes.
     */
    async uniqueId() {
        return this._read(CMD_UID, 6);
    }

    /**
     * Read the TVOC algorithm version (0x73).
     * @returns {Promise<number[]>} `[major, minor, patch]`.
     */
    async algorithmVersion() {
        const d = await this._read(CMD_ARGVER, 3);
        return [d[0], d[1], d[2]];
    }

    /**
     * Read the firmware version (0x75).
     * @returns {Promise<number[]>} `[major, minor]`.
     */
    async firmwareVersion() {
        const d = await this._read(CMD_FWVER, 2);
        return [d[0], d[1]];
    }
}

module.exports = { RRH62000Minimal, RRH62000Full, RRH62000Error, crc8 };
