'use strict';

const _CMD_SOFT_RESET = 0x30A2;
const _CMD_HEATER_ON = 0x306D;
const _CMD_HEATER_OFF = 0x3066;
const _CMD_READ_STATUS = 0xF32D;
const _CMD_CLEAR_STATUS = 0x3041;
const _CMD_FETCH = 0xE000;
const _CMD_BREAK = 0x3093;
const _CMD_ART = 0x2B32;

// single shot: [stretching disabled, stretching enabled, wait in ms]
const _SINGLE = {
    high: [0x2400, 0x2C06, 15],
    medium: [0x240B, 0x2C0D, 6],
    low: [0x2416, 0x2C10, 4],
};

// periodic: rate -> repeatability -> command
const _PERIODIC = {
    0.5: { high: 0x2032, medium: 0x2024, low: 0x202F },
    1: { high: 0x2130, medium: 0x2126, low: 0x212D },
    2: { high: 0x2236, medium: 0x2220, low: 0x222B },
    4: { high: 0x2334, medium: 0x2322, low: 0x2329 },
    10: { high: 0x2737, medium: 0x2721, low: 0x272A },
};

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-8 used by the SHT3x: polynomial 0x31, init 0xFF, no reflection, no final XOR.
 * @param {Buffer|number[]} data
 * @returns {number}
 */
function crc8(data) {
    let crc = 0xFF;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

function _decode(data) {
    if (crc8(data.subarray(0, 2)) !== data[2] || crc8(data.subarray(3, 5)) !== data[5]) {
        throw new Error('SHT31 CRC mismatch');
    }
    return [(data[0] << 8) | data[1], (data[3] << 8) | data[4]];
}

function _convert(rawT, rawRh) {
    return [-45.0 + 175.0 * rawT / 65535.0, 100.0 * rawRh / 65535.0];
}

/**
 * SHT31 temperature and humidity sensor — minimal interface.
 *
 * Each `read()` runs one single-shot measurement at high repeatability with
 * clock stretching disabled and CRC checking on. The heater stays off.
 *
 * Default configuration (baked in at construction):
 * - Soft reset (0x30A2) with 1 ms waits before and after (started asynchronously
 *   by the constructor; every public method waits for it; `await sensor.init()` surfaces errors)
 * - Single shot, high repeatability (0x2400), fixed 15 ms wait; if the read header is
 *   still NACKed the driver keeps polling for up to 20 ms more
 * - Both CRC-8 bytes verified; one retry of the whole measurement on a mismatch, then an Error
 */
class SHT31Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x44 with ADDR low, 0x45 with ADDR high).
     */
    constructor(connection) {
        this._conn = connection;
        this._repeatability = 'high';
        this._stretch = false;
        this._ready = this._reset();
        // Re-raised by every public method; mark handled so it never surfaces on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the soft reset started by the constructor.
     * @returns {Promise<void>}
     */
    async init() {
        await this._ready;
    }

    async _reset() {
        await _sleep(1);
        await this._command(_CMD_SOFT_RESET);
        await _sleep(1);
    }

    async _command(code) {
        await this._conn.write(Buffer.from([code >> 8, code & 0xFF]));
    }

    async _singleShot() {
        await this._ready;
        const [noStretch, stretch, wait] = _SINGLE[this._repeatability];
        for (let attempt = 0; attempt < 2; attempt++) {
            await this._command(this._stretch ? stretch : noStretch);
            if (!this._stretch) await _sleep(wait);
            let data = null;
            for (let i = 0; i < 21 && data === null; i++) {
                try {
                    data = Buffer.from(await this._conn.read(6));
                } catch (e) {
                    await _sleep(1);       // read header NACKed: not ready yet
                }
            }
            if (data === null) throw new Error('SHT31 measurement did not complete');
            try {
                return _decode(data);
            } catch (e) {
                if (attempt === 1) throw e;
            }
        }
        return null;
    }

    /**
     * Run one single-shot measurement.
     * @returns {Promise<[number, number]>} `[temperature_c, humidity_pct]` — °C and %RH.
     * @throws {Error} If a CRC mismatch persists after one retry.
     */
    async read() {
        const [rawT, rawRh] = await this._singleShot();
        return _convert(rawT, rawRh);
    }
}

/**
 * SHT31 full interface — repeatability, clock stretching, periodic mode, heater and status.
 *
 * Extends SHT31Minimal with single values and raw words, the three repeatability levels, the
 * clock-stretching command set, periodic measurements (0.5–10 mps plus ART), the heater, the
 * status register, soft reset and the dew point.
 */
class SHT31Full extends SHT31Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x44 or 0x45).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Run one measurement and return the temperature only.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() { return (await this.read())[0]; }

    /**
     * Run one measurement and return the relative humidity only.
     * @returns {Promise<number>} Relative humidity in %RH.
     */
    async readHumidity() { return (await this.read())[1]; }

    /**
     * Run one measurement and return the raw 16-bit words.
     * @returns {Promise<[number, number]>} `[rawTemperature, rawHumidity]`, each 0–65535.
     */
    async readRaw() { return this._singleShot(); }

    /**
     * Select the repeatability for later single-shot reads (measurement time 4 / 6 / 15 ms).
     * @param {'low'|'medium'|'high'} level
     * @throws {RangeError} For any other value.
     */
    setRepeatability(level) {
        if (!(level in _SINGLE)) throw new RangeError("level must be 'low', 'medium' or 'high'");
        this._repeatability = level;
    }

    /**
     * Use the clock-stretching command set for single-shot reads (the bus layer must support it); the fixed wait is skipped.
     * @param {boolean} on
     */
    setClockStretching(on) { this._stretch = !!on; }

    /**
     * Start periodic measurements; read results with `fetch()`.
     * @param {number} rate - Measurements per second: 0.5, 1, 2, 4 or 10 (ignored with `art`).
     * @param {'low'|'medium'|'high'} [repeatability='high']
     * @param {boolean} [art=false] - Accelerated response time mode (4 mps, command 0x2B32).
     * @returns {Promise<void>}
     * @throws {RangeError} For an unsupported rate or repeatability.
     */
    async startPeriodic(rate, repeatability = 'high', art = false) {
        await this._ready;
        if (art) {
            await this._command(_CMD_ART);
            return;
        }
        if (!(rate in _PERIODIC)) throw new RangeError('rate must be 0.5, 1, 2, 4 or 10');
        if (!(repeatability in _SINGLE)) throw new RangeError("repeatability must be 'low', 'medium' or 'high'");
        await this._command(_PERIODIC[rate][repeatability]);
    }

    /**
     * Read the latest periodic result (command 0xE000).
     * @returns {Promise<[number, number]|null>} `[temperature_c, humidity_pct]`, or `null` if no new data is available (read header NACKed).
     * @throws {Error} If a CRC check fails.
     */
    async fetch() {
        await this._ready;
        await this._command(_CMD_FETCH);
        let data;
        try {
            data = Buffer.from(await this._conn.read(6));
        } catch (e) {
            return null;
        }
        const [rawT, rawRh] = _decode(data);
        return _convert(rawT, rawRh);
    }

    /**
     * Stop periodic mode (break command 0x3093) and wait 1 ms.
     * @returns {Promise<void>}
     */
    async stopPeriodic() {
        await this._ready;
        await this._command(_CMD_BREAK);
        await _sleep(1);
    }

    /**
     * Switch the on-chip heater (about 5–25 mW) on or off.
     * @param {boolean} on
     * @returns {Promise<void>}
     */
    async setHeater(on) {
        await this._ready;
        await this._command(on ? _CMD_HEATER_ON : _CMD_HEATER_OFF);
    }

    /**
     * Read the status register (command 0xF32D, CRC verified).
     * @returns {Promise<number>} 16-bit status, e.g. 0x8010 after reset; bit 13 = heater on.
     * @throws {Error} If the CRC check fails.
     */
    async readStatus() {
        await this._ready;
        await this._command(_CMD_READ_STATUS);
        const data = Buffer.from(await this._conn.read(3));
        if (crc8(data.subarray(0, 2)) !== data[2]) throw new Error('SHT31 CRC mismatch');
        return (data[0] << 8) | data[1];
    }

    /**
     * Clear the status register flags (command 0x3041).
     * @returns {Promise<void>}
     */
    async clearStatus() {
        await this._ready;
        await this._command(_CMD_CLEAR_STATUS);
    }

    /**
     * Soft reset (0x30A2), wait 1 ms; leaves periodic mode and returns to single shot.
     * @returns {Promise<void>}
     */
    async softReset() {
        await this._ready;
        await this._command(_CMD_SOFT_RESET);
        await _sleep(1);
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula).
     * @returns {Promise<number>} Dew point in °C.
     */
    async dewPoint() {
        let [t, rh] = await this.read();
        if (rh <= 0) rh = 0.01;
        const gamma = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
        return 243.12 * gamma / (17.62 - gamma);
    }
}

module.exports = { SHT31Minimal, SHT31Full, crc8 };
