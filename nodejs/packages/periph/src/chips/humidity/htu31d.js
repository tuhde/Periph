'use strict';

const _CMD_RESET = 0x1E;
const _CMD_HEATER_ON = 0x04;
const _CMD_HEATER_OFF = 0x02;
const _CMD_CONVERT = 0x40;
const _CMD_READ_TRH = 0x00;
const _CMD_READ_RH = 0x10;
const _CMD_READ_DIAG = 0x08;
const _CMD_READ_SERIAL = 0x0A;

// typical conversion times in seconds, indexed by OSR 0..3
const _T_RH = [0.00111, 0.00214, 0.00421, 0.00834];
const _T_T = [0.00157, 0.00306, 0.00603, 0.01198];

const _DIAG_FLAGS = [
    [7, 'nvm_err'], [6, 'rh_overrun'], [5, 'rh_high_err'], [4, 'rh_low_err'],
    [3, 't_overrun'], [2, 't_high_err'], [1, 't_low_err'], [0, 'heater_on'],
];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-8 used by the HTU31D: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
 * @param {Buffer|number[]} data
 * @returns {number}
 */
function crc8(data) {
    let crc = 0x00;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

function _toHumidity(code) {
    return Math.min(100.0, Math.max(0.0, 100.0 * code / 65535.0));
}

function _toTemperature(code) {
    return -40.0 + 165.0 * code / 65535.0;
}

/**
 * HTU31D humidity and temperature sensor — minimal interface.
 *
 * Each `read()` starts one conversion at the highest resolution (command 0x5E, OSR 3 for
 * humidity and temperature), waits 25 ms, reads the temperature and humidity words
 * (command 0x00) and verifies both CRC-8 bytes. Humidity is clamped to 0-100 %RH.
 *
 * The device is command driven (no register pointer), so a plain I²C connection is used.
 * The constructor does not touch the bus; `init()` performs the soft reset.
 */
class HTU31DMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x40 or 0x41).
     */
    constructor(connection) {
        this._conn = connection;
        this._osrRh = 3;
        this._osrT = 3;
    }

    /** Soft reset (0x1E) and wait 15 ms; heater off, highest resolution. */
    async init() {
        await this._conn.write(Buffer.from([_CMD_RESET]));
        await _sleep(15);
    }

    _waitMs() {
        if (this._osrRh === 3 && this._osrT === 3) return 25;
        return 1.25 * (_T_RH[this._osrRh] + _T_T[this._osrT]) * 1000.0;
    }

    async _start() {
        await this._conn.write(Buffer.from([_CMD_CONVERT | (this._osrRh << 3) | (this._osrT << 1)]));
    }

    async _readWords(command, count) {
        await this._conn.write(Buffer.from([command]));
        const data = Buffer.from(await this._conn.read(3 * count));
        const words = [];
        for (let i = 0; i < count; i++) {
            const chunk = data.subarray(3 * i, 3 * i + 3);
            if (crc8(chunk.subarray(0, 2)) !== chunk[2]) throw new Error('HTU31D CRC mismatch');
            words.push((chunk[0] << 8) | chunk[1]);
        }
        return words;
    }

    async _measure(command, count) {
        await this._start();
        await _sleep(this._waitMs());
        return this._readWords(command, count);
    }

    /**
     * Measure temperature and humidity.
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} °C and %RH (0-100)
     */
    async read() {
        const [t, rh] = await this._measure(_CMD_READ_TRH, 2);
        return { temperature_c: _toTemperature(t), humidity_pct: _toHumidity(rh) };
    }
}

/**
 * HTU31D full interface — oversampling, non-blocking conversion, heater, diagnostics, dew point.
 *
 * Extends HTU31DMinimal with single values, raw codes, separate oversampling settings for
 * humidity and temperature, the on-chip heater, the diagnostic byte, the serial number,
 * soft reset and the dew point.
 */
class HTU31DFull extends HTU31DMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x40 or 0x41).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Run one humidity measurement (command 0x10 after the conversion).
     * @returns {Promise<number>} Relative humidity in %RH, clamped to 0-100.
     */
    async readHumidity() {
        return _toHumidity((await this._measure(_CMD_READ_RH, 1))[0]);
    }

    /**
     * Run one temperature measurement (the read stops after the temperature word).
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _toTemperature((await this._measure(_CMD_READ_TRH, 1))[0]);
    }

    /**
     * Measure temperature and humidity and return the raw codes.
     * @returns {Promise<[number, number]>} [t_raw, rh_raw], both 16-bit unsigned.
     */
    async readRaw() {
        return this._measure(_CMD_READ_TRH, 2);
    }

    /**
     * Select the oversampling of the following measurements.
     * @param {number} osrRh - Humidity oversampling 0-3.
     * @param {number} osrT - Temperature oversampling 0-3.
     * @throws {RangeError} If either value is not 0-3.
     */
    setResolution(osrRh, osrT) {
        if (![0, 1, 2, 3].includes(osrRh) || ![0, 1, 2, 3].includes(osrT)) throw new RangeError('osr must be 0-3');
        this._osrRh = osrRh;
        this._osrT = osrT;
    }

    /** @returns {[number, number]} [osrRh, osrT] */
    getResolution() {
        return [this._osrRh, this._osrT];
    }

    /** @returns {number} Typical conversion time of one measurement at the current oversampling, in seconds. */
    conversionTime() {
        return _T_RH[this._osrRh] + _T_T[this._osrT];
    }

    /** Write the conversion command only (non-blocking use); wait `conversionTime()` plus margin before `readResult()`. */
    async startConversion() {
        await this._start();
    }

    /**
     * Read the result of the last conversion (command 0x00).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>}
     */
    async readResult() {
        const [t, rh] = await this._readWords(_CMD_READ_TRH, 2);
        return { temperature_c: _toTemperature(t), humidity_pct: _toHumidity(rh) };
    }

    /**
     * Switch the on-chip heater on (0x04) or off (0x02).
     * @param {boolean} on
     */
    async setHeater(on) {
        await this._conn.write(Buffer.from([on ? _CMD_HEATER_ON : _CMD_HEATER_OFF]));
    }

    /** @returns {Promise<boolean>} True if the diagnostic byte reports the heater on. */
    async isHeaterOn() {
        return ((await this.diagnostic()) & 0x01) !== 0;
    }

    /** @returns {Promise<number>} The diagnostic byte (command 0x08). */
    async diagnostic() {
        await this._conn.write(Buffer.from([_CMD_READ_DIAG]));
        return Buffer.from(await this._conn.read(1))[0];
    }

    /** @returns {Promise<Object>} Named boolean diagnostic flags (nvm_err, rh_overrun, rh_high_err, rh_low_err, t_overrun, t_high_err, t_low_err, heater_on). */
    async diagnosticFlags() {
        const value = await this.diagnostic();
        const flags = {};
        for (const [bit, name] of _DIAG_FLAGS) flags[name] = ((value >> bit) & 1) === 1;
        return flags;
    }

    /**
     * Read the 24-bit serial number (command 0x0A, CRC verified).
     * @returns {Promise<number>}
     */
    async serialNumber() {
        await this._conn.write(Buffer.from([_CMD_READ_SERIAL]));
        const data = Buffer.from(await this._conn.read(4));
        if (crc8(data.subarray(0, 3)) !== data[3]) throw new Error('HTU31D CRC mismatch');
        return (data[0] << 16) | (data[1] << 8) | data[2];
    }

    /** Soft reset (0x1E) and wait 15 ms; heater off, oversampling back to OSR 3 / 3. */
    async softReset() {
        await this._conn.write(Buffer.from([_CMD_RESET]));
        await _sleep(15);
        this._osrRh = 3;
        this._osrT = 3;
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula over water).
     * @returns {Promise<number>} Dew point in °C.
     */
    async dewPoint() {
        const m = await this.read();
        const t = m.temperature_c;
        const rh = m.humidity_pct <= 0.0 ? 0.01 : m.humidity_pct;
        const g = Math.log(rh / 100.0) + 17.62 * t / (243.12 + t);
        return 243.12 * g / (17.62 - g);
    }
}

module.exports = { HTU31DMinimal, HTU31DFull, crc8 };
