'use strict';

const _REG_TEMPERATURE = 0x00;
const _REG_HUMIDITY = 0x01;
const _REG_CONFIG = 0x02;
const _REG_SERIAL1 = 0xFB;
const _REG_MANUFACTURER_ID = 0xFE;
const _REG_DEVICE_ID = 0xFF;

const _RST = 0x8000;
const _HEAT = 0x2000;
const _MODE = 0x1000;
const _BTST = 0x0800;
const _TRES = 0x0400;
const _HRES_MASK = 0x0300;
const _CONFIG_DEFAULT = 0x1000;
const _POLL_ATTEMPTS = 50;     // x 1 ms = 50 ms timeout

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const _toTemperature = (raw) => raw / 65536.0 * 165.0 - 40.0;
const _toHumidity = (raw) => raw / 65536.0 * 100.0;

/**
 * HDC1080 humidity and temperature sensor (Texas Instruments) — minimal interface.
 *
 * `init()` writes the configuration 0x1000 (14-bit T and RH, sequence mode, heater off).
 * `read()` triggers one sequence measurement (pointer 0x00), polls the NACKed read header
 * (timeout 50 ms) and converts the temperature and humidity words. The constructor does not
 * touch the bus.
 */
class HDC1080Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Plain I²C connection (0x40).
     */
    constructor(connection) {
        this._conn = connection;
        this._config = _CONFIG_DEFAULT;
    }

    /** Write the default configuration 0x1000 (14-bit, sequence mode, heater off). */
    async init() {
        this._config = _CONFIG_DEFAULT;
        await this._writeConfig();
    }

    async _writeConfig() {
        await this._conn.write(Buffer.from([_REG_CONFIG, (this._config >> 8) & 0xFF, this._config & 0xFF]));
    }

    async _triggerRead(pointer, n) {
        await this._conn.write(Buffer.from([pointer]));
        for (let i = 0; i < _POLL_ATTEMPTS; i++) {
            try {
                return Buffer.from(await this._conn.read(n));
            } catch (e) {
                await _sleep(1);       // read header NACKed: not ready yet
            }
        }
        throw new Error('HDC1080 measurement did not complete');
    }

    async _setMode(sequence) {
        const want = sequence ? (this._config | _MODE) : (this._config & ~_MODE);
        if (want !== this._config) {
            this._config = want;
            await this._writeConfig();
        }
    }

    async _measurePair() {
        await this._setMode(true);
        const d = await this._triggerRead(_REG_TEMPERATURE, 4);
        return [(d[0] << 8) | d[1], (d[2] << 8) | d[3]];
    }

    /**
     * Measure temperature and humidity (sequence mode, temperature first).
     * @returns {Promise<[number, number]>} [temperature °C, relative humidity %RH]
     */
    async read() {
        const [t, h] = await this._measurePair();
        return [_toTemperature(t), _toHumidity(h)];
    }
}

/**
 * HDC1080 full interface — single values, resolution, heater, battery flag, serial ID and the ID registers.
 *
 * Extends HDC1080Minimal.
 */
class HDC1080Full extends HDC1080Minimal {
    /**
     * Measure and return the raw 16-bit words.
     * @returns {Promise<[number, number]>} [raw temperature, raw humidity], bits 1:0 zero
     */
    async readRaw() {
        return this._measurePair();
    }

    /**
     * Measure temperature only (single mode).
     * @returns {Promise<number>} °C
     */
    async readTemperature() {
        await this._setMode(false);
        const d = await this._triggerRead(_REG_TEMPERATURE, 2);
        return _toTemperature((d[0] << 8) | d[1]);
    }

    /**
     * Measure humidity only (single mode).
     * @returns {Promise<number>} %RH
     */
    async readHumidity() {
        await this._setMode(false);
        const d = await this._triggerRead(_REG_HUMIDITY, 2);
        return _toHumidity((d[0] << 8) | d[1]);
    }

    /**
     * Select the conversion resolutions.
     * @param {number} temperatureBits - 14 or 11.
     * @param {number} humidityBits - 14, 11 or 8.
     * @throws {RangeError} For any other value.
     */
    async setResolution(temperatureBits, humidityBits) {
        if (temperatureBits !== 14 && temperatureBits !== 11) throw new RangeError('temperatureBits must be 14 or 11');
        if (![14, 11, 8].includes(humidityBits)) throw new RangeError('humidityBits must be 14, 11 or 8');
        let cfg = this._config & ~(_TRES | _HRES_MASK);
        if (temperatureBits === 11) cfg |= _TRES;
        cfg |= { 14: 0x0000, 11: 0x0100, 8: 0x0200 }[humidityBits];
        this._config = cfg;
        await this._writeConfig();
    }

    /**
     * Enable or disable the heater.
     * @param {boolean} on
     */
    async setHeater(on) {
        this._config = on ? (this._config | _HEAT) : (this._config & ~_HEAT);
        await this._writeConfig();
    }

    /** @returns {Promise<boolean>} True if the heater bit is set in the configuration register. */
    async isHeaterOn() {
        return ((await this.readConfig()) & _HEAT) !== 0;
    }

    /** @returns {Promise<boolean>} True if VDD is below 2.8 V (BTST). */
    async batteryLow() {
        return ((await this.readConfig()) & _BTST) !== 0;
    }

    /** @returns {Promise<number>} The 16-bit configuration word. */
    async readConfig() {
        await this._conn.write(Buffer.from([_REG_CONFIG]));
        const d = Buffer.from(await this._conn.read(2));
        return (d[0] << 8) | d[1];
    }

    /** Set RST, wait 15 ms, then rewrite the stored configuration. */
    async softwareReset() {
        await this._conn.write(Buffer.from([_REG_CONFIG, (_RST >> 8) & 0xFF, 0x00]));
        await _sleep(15);
        await this._writeConfig();
    }

    /** @returns {Promise<number>} The 40-bit serial number from registers 0xFB-0xFD. */
    async serialId() {
        const w = [];
        for (let i = 0; i < 3; i++) {
            await this._conn.write(Buffer.from([_REG_SERIAL1 + i]));
            const d = Buffer.from(await this._conn.read(2));
            w.push((d[0] << 8) | d[1]);
        }
        return w[0] * 2 ** 24 + w[1] * 2 ** 8 + (w[2] >> 7);
    }

    /** @returns {number} Typical conversion time in seconds for the current resolutions (T + RH). */
    conversionTime() {
        const t = (this._config & _TRES) ? 0.00365 : 0.00635;
        const h = { 0x0000: 0.0065, 0x0100: 0.00385, 0x0200: 0.0025 }[this._config & _HRES_MASK] ?? 0.0065;
        return t + h;
    }

    async _readWord(reg) {
        await this._conn.write(Buffer.from([reg]));
        const d = Buffer.from(await this._conn.read(2));
        return (d[0] << 8) | d[1];
    }

    /** @returns {Promise<number>} The manufacturer ID register 0xFE (0x5449 = Texas Instruments). */
    async manufacturerId() {
        return this._readWord(_REG_MANUFACTURER_ID);
    }

    /** @returns {Promise<number>} The device ID register 0xFF (0x1050 for the HDC1080). */
    async deviceId() {
        return this._readWord(_REG_DEVICE_ID);
    }
}

/** Default 7-bit I²C address (fixed, no address pins). */
HDC1080Minimal.I2C_ADDRESS = 0x40;

module.exports = { HDC1080Minimal, HDC1080Full };
