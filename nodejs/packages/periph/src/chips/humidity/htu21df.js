'use strict';

const _CMD_T_HOLD = 0xE3;
const _CMD_RH_HOLD = 0xE5;
const _CMD_T_POLL = 0xF3;
const _CMD_RH_POLL = 0xF5;
const _CMD_WRITE_USER = 0xE6;
const _CMD_READ_USER = 0xE7;
const _CMD_RESET = 0xFE;
const _POLL_ATTEMPTS = 100;

const _HEATER = 0x04;
const _VDD_LOW = 0x40;
const _RES_MASK = 0x81;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-8 used by the HTU21D-F: polynomial 0x131 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
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
    return -6.0 + 125.0 * code / 65536.0;
}

function _toTemperature(code) {
    return -46.85 + 175.72 * code / 65536.0;
}

/**
 * HTU21D-F humidity and temperature sensor — minimal interface.
 *
 * Each `read()` triggers one temperature and one humidity measurement without hold master
 * (0xF3 / 0xF5), polls the NACKed read header until the chip ACKs (timeout 100 ms) and
 * verifies the CRC of both results (one retry on a mismatch).
 *
 * Default configuration (power-on): 12-bit RH / 14-bit temperature, heater off,
 * no hold master, CRC checked. The constructor does not touch the bus; `init()` performs
 * the soft reset.
 */
class HTU21DFMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x40).
     */
    constructor(connection) {
        this._conn = connection;
        this._hold = false;
    }

    /** Wait 15 ms for power-up, soft reset (0xFE) and wait 15 ms. */
    async init() {
        await _sleep(15);
        await this._conn.write(Buffer.from([_CMD_RESET]));
        await _sleep(15);
    }

    async _measureOnce(holdCmd, pollCmd) {
        if (this._hold) {
            await this._conn.write(Buffer.from([holdCmd]));
            return Buffer.from(await this._conn.read(3));
        }
        await this._conn.write(Buffer.from([pollCmd]));
        for (let i = 0; i < _POLL_ATTEMPTS; i++) {
            try {
                return Buffer.from(await this._conn.read(3));
            } catch (e) {
                await _sleep(1);       // read header NACKed: conversion not done yet
            }
        }
        throw new Error('HTU21D-F measurement did not complete');
    }

    async _measure(holdCmd, pollCmd) {
        for (let attempt = 0; attempt < 2; attempt++) {
            const data = await this._measureOnce(holdCmd, pollCmd);
            if (crc8(data.subarray(0, 2)) === data[2]) return ((data[0] << 8) | data[1]) & 0xFFFC;
        }
        throw new Error('HTU21D-F CRC mismatch');
    }

    /**
     * Measure temperature and humidity.
     * @returns {Promise<[number, number]>} [temperature °C, relative humidity %RH (not clamped)]
     */
    async read() {
        const t = await this._measure(_CMD_T_HOLD, _CMD_T_POLL);
        const rh = await this._measure(_CMD_RH_HOLD, _CMD_RH_POLL);
        return [_toTemperature(t), _toHumidity(rh)];
    }
}

/**
 * HTU21D-F full interface — resolution, heater, hold master, compensation, dew point.
 *
 * Extends HTU21DFMinimal with single values, raw codes, the four resolution settings,
 * the on-chip heater, the end-of-battery flag, the raw user register, soft reset,
 * hold-master mode, temperature-compensated humidity and the dew point.
 */
class HTU21DFFull extends HTU21DFMinimal {
    /**
     * Measure temperature and humidity and return the raw codes.
     * @returns {Promise<[number, number]>} [temperatureRaw, humidityRaw], 16-bit with the status bits cleared.
     */
    async readRaw() {
        const t = await this._measure(_CMD_T_HOLD, _CMD_T_POLL);
        const rh = await this._measure(_CMD_RH_HOLD, _CMD_RH_POLL);
        return [t, rh];
    }

    /**
     * Run one temperature measurement.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _toTemperature(await this._measure(_CMD_T_HOLD, _CMD_T_POLL));
    }

    /**
     * Run one humidity measurement (not clamped; the ASIC range is -6 ... 118 %RH).
     * @returns {Promise<number>} Relative humidity in %RH.
     */
    async readHumidity() {
        return _toHumidity(await this._measure(_CMD_RH_HOLD, _CMD_RH_POLL));
    }

    /**
     * Measure and correct the humidity for temperature (-0.15 %RH/°C relative to 25 °C).
     * @returns {Promise<number>} Compensated relative humidity in %RH.
     */
    async readHumidityCompensated() {
        const [t, rh] = await this.read();
        return rh + (25.0 - t) * (-0.15);
    }

    /**
     * Read the user register (command 0xE7).
     * @returns {Promise<number>} Raw register value (0x02 after reset).
     */
    async readUserRegister() {
        const data = await this._conn.writeRead(Buffer.from([_CMD_READ_USER]), 1);
        return data[0];
    }

    async _writeUser(value) {
        await this._conn.write(Buffer.from([_CMD_WRITE_USER, value & 0xFF]));
    }

    /**
     * Select the resolution (read-modify-write of the RES bits 7 and 0).
     * @param {number} res - 0 = 12-bit RH / 14-bit T, 1 = 8 / 12, 2 = 10 / 13, 3 = 11 / 11.
     */
    async setResolution(res) {
        if (![0, 1, 2, 3].includes(res)) throw new RangeError('res must be 0-3');
        const bits = ((res >> 1) << 7) | (res & 1);
        await this._writeUser(((await this.readUserRegister()) & ~_RES_MASK) | bits);
    }

    /**
     * @returns {Promise<number>} Resolution setting: 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11.
     */
    async getResolution() {
        const reg = await this.readUserRegister();
        return (((reg >> 7) & 1) << 1) | (reg & 1);
    }

    /**
     * Switch the on-chip heater on or off (read-modify-write).
     * @param {boolean} on
     */
    async setHeater(on) {
        const reg = await this.readUserRegister();
        await this._writeUser(on ? (reg | _HEATER) : (reg & ~_HEATER));
    }

    /** @returns {Promise<boolean>} True if the on-chip heater is enabled. */
    async heater() {
        return ((await this.readUserRegister()) & _HEATER) !== 0;
    }

    /** @returns {Promise<boolean>} True if the end-of-battery flag is set (VDD below about 2.25 V). */
    async batteryLow() {
        return ((await this.readUserRegister()) & _VDD_LOW) !== 0;
    }

    /** Soft reset (0xFE) and wait 15 ms; the heater bit survives. */
    async softReset() {
        await this._conn.write(Buffer.from([_CMD_RESET]));
        await _sleep(15);
    }

    /**
     * Use the hold-master commands (0xE3 / 0xE5, SCL stretching) instead of polling.
     * The bus layer must support clock stretching.
     * @param {boolean} on
     */
    setHoldMaster(on) {
        this._hold = Boolean(on);
    }

    /**
     * Run one measurement and compute the dew point.
     * @returns {Promise<number>} Dew point in °C.
     */
    async dewPoint() {
        let [t, rh] = await this.read();
        if (rh <= 0.0) rh = 0.01;
        const pp = Math.pow(10.0, 8.1332 - 1762.39 / (t + 235.66));
        return -1762.39 / (Math.log10(rh * pp / 100.0) - 8.1332) - 235.66;
    }
}

module.exports = { HTU21DFMinimal, HTU21DFFull, crc8 };
