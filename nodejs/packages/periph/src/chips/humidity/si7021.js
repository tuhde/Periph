'use strict';

const _CMD_RH_HOLD = 0xE5;
const _CMD_RH_POLL = 0xF5;
const _CMD_T_HOLD = 0xE3;
const _CMD_T_POLL = 0xF3;
const _CMD_T_PREVIOUS = 0xE0;
const _CMD_RESET = 0xFE;
const _CMD_WRITE_USER = 0xE6;
const _CMD_READ_USER = 0xE7;
const _POLL_ATTEMPTS = 50;

const _RESERVED = 0x3A;
const _HTRE = 0x04;
const _VDDS = 0x40;

// [rhBits, temperatureBits] indexed by RES1:RES0
const _RESOLUTIONS = [[12, 14], [8, 12], [10, 13], [11, 11]];
// typical conversion times in seconds, indexed by RES1:RES0
const _RH_TIME = [0.010, 0.0026, 0.0037, 0.0058];
const _T_TIME = [0.007, 0.0024, 0.004, 0.0015];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-8 used by the Si7021: polynomial 0x31, init 0x00, no reflection, no final XOR.
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
    return Math.min(100.0, Math.max(0.0, 125.0 * code / 65536.0 - 6.0));
}

function _toTemperature(code) {
    return 175.72 * code / 65536.0 - 46.85;
}

/**
 * Si7021 humidity and temperature sensor — minimal interface.
 *
 * Each `read()` runs one relative-humidity measurement without hold master (the
 * read header is polled until the chip ACKs, timeout 50 ms), verifies the CRC and
 * then fetches the temperature of that same measurement with command 0xE0.
 *
 * Default configuration (power-on, nothing is written at construction):
 * - 12-bit RH / 14-bit temperature, heater off
 * - No-hold-master measurement (0xF5), polling the NACKed read header
 * - CRC-8 verified on the RH result; relative humidity clamped to 0-100 %RH
 */
class Si7021Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x40).
     */
    constructor(connection) {
        this._conn = connection;
        this._stretch = false;
    }

    async _command(code) {
        await this._conn.write(Buffer.from([code]));
    }

    async _measure(holdCmd, pollCmd) {
        let data = null;
        if (this._stretch) {
            await this._command(holdCmd);
            data = Buffer.from(await this._conn.read(3));
        } else {
            await this._command(pollCmd);
            for (let i = 0; i < _POLL_ATTEMPTS && data === null; i++) {
                try {
                    data = Buffer.from(await this._conn.read(3));
                } catch (e) {
                    await _sleep(1);       // read header NACKed: not ready yet
                }
            }
            if (data === null) throw new Error('Si7021 measurement did not complete');
        }
        if (crc8(data.subarray(0, 2)) !== data[2]) throw new Error('Si7021 CRC mismatch');
        return (data[0] << 8) | data[1];
    }

    async _previousCode() {
        await this._command(_CMD_T_PREVIOUS);
        const data = Buffer.from(await this._conn.read(2));
        return (data[0] << 8) | data[1];
    }

    /**
     * Run one RH measurement and read the temperature of the same conversion.
     * @returns {Promise<[number, number]>} [temperature °C, relative humidity %RH (0-100)]
     */
    async read() {
        const rh = await this._measure(_CMD_RH_HOLD, _CMD_RH_POLL);
        return [_toTemperature(await this._previousCode()), _toHumidity(rh)];
    }
}

/**
 * Si7021 full interface — resolution, heater, hold master, ID and dew point.
 *
 * Extends Si7021Minimal with single values and raw codes, the four resolution pairs,
 * the on-chip heater, the raw user register, clock stretching (hold master), software
 * reset, electronic serial number, firmware revision, conversion time and the dew point.
 */
class Si7021Full extends Si7021Minimal {
    /**
     * Run one RH measurement and return the raw 16-bit code (status bits included).
     * @returns {Promise<number>}
     */
    async readRawHumidity() {
        return this._measure(_CMD_RH_HOLD, _CMD_RH_POLL);
    }

    /**
     * Run one temperature measurement and return the raw 16-bit code.
     * @returns {Promise<number>}
     */
    async readRawTemperature() {
        return this._measure(_CMD_T_HOLD, _CMD_T_POLL);
    }

    /**
     * Run one RH measurement.
     * @returns {Promise<number>} Relative humidity in %RH, clamped to 0-100.
     */
    async readHumidity() {
        return _toHumidity(await this.readRawHumidity());
    }

    /**
     * Run a fresh temperature measurement (0xF3, or 0xE3 with clock stretching).
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _toTemperature(await this.readRawTemperature());
    }

    /**
     * Read the temperature of the last RH measurement (0xE0, no new conversion, no CRC).
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperaturePrevious() {
        return _toTemperature(await this._previousCode());
    }

    /**
     * Read USER_REG_1 (command 0xE7).
     * @returns {Promise<number>} Raw register value (0x3A after reset).
     */
    async readUserRegister() {
        return (await this._conn.writeRead(Buffer.from([_CMD_READ_USER]), 1))[0];
    }

    /**
     * Write USER_REG_1 (command 0xE6); the reserved bits and VDDS keep their current value.
     * @param {number} value
     */
    async writeUserRegister(value) {
        const keep = _RESERVED | _VDDS;
        const merged = ((await this.readUserRegister()) & keep) | (value & ~keep & 0xFF);
        await this._conn.write(Buffer.from([_CMD_WRITE_USER, merged]));
    }

    /**
     * Select the measurement resolution (read-modify-write of RES1:RES0).
     * @param {number} rhBits - RH resolution in bits.
     * @param {number} temperatureBits - Temperature resolution in bits; allowed pairs are
     *   (12, 14), (8, 12), (10, 13) and (11, 11).
     * @throws {Error} If the pair is not one of the four allowed ones.
     */
    async setResolution(rhBits, temperatureBits) {
        const res = _RESOLUTIONS.findIndex(([r, t]) => r === rhBits && t === temperatureBits);
        if (res < 0) throw new Error('resolution must be (12, 14), (8, 12), (10, 13) or (11, 11)');
        const bits = ((res >> 1) << 7) | (res & 1);
        await this.writeUserRegister(((await this.readUserRegister()) & ~0x81 & 0xFF) | bits);
    }

    /**
     * Return the configured resolution.
     * @returns {Promise<[number, number]>} [rhBits, temperatureBits]
     */
    async getResolution() {
        const reg = await this.readUserRegister();
        return _RESOLUTIONS[(((reg >> 7) & 1) << 1) | (reg & 1)];
    }

    /**
     * Switch the on-chip heater (HTRE, about 3.1 mA) on or off.
     * @param {boolean} on
     */
    async setHeater(on) {
        const reg = await this.readUserRegister();
        await this.writeUserRegister(on ? (reg | _HTRE) : (reg & ~_HTRE & 0xFF));
    }

    /**
     * @returns {Promise<boolean>} True if the on-chip heater is enabled.
     */
    async isHeaterOn() {
        return ((await this.readUserRegister()) & _HTRE) !== 0;
    }

    /**
     * @returns {Promise<boolean>} True if the supply voltage is low (VDDS set, VDD below about 1.9 V).
     */
    async vddLow() {
        return ((await this.readUserRegister()) & _VDDS) !== 0;
    }

    /**
     * Use the hold-master commands (0xE5 / 0xE3) instead of polling; the bus layer must support clock stretching.
     * @param {boolean} on
     */
    setClockStretching(on) {
        this._stretch = Boolean(on);
    }

    /** Software reset (0xFE) and wait 15 ms; the user register returns to 0x3A. */
    async softReset() {
        await this._command(_CMD_RESET);
        await _sleep(15);
    }

    /**
     * Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0).
     * @returns {Promise<bigint>}
     */
    async serialNumber() {
        const a = Buffer.from(await this._conn.writeRead(Buffer.from([0xFA, 0x0F]), 8));
        const b = Buffer.from(await this._conn.writeRead(Buffer.from([0xFC, 0xC9]), 6));
        return Buffer.from([a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]]).readBigUInt64BE(0);
    }

    /**
     * Read SNB_3, the device type byte.
     * @returns {Promise<number>} 0x15 for the Si7021 (0x14 Si7020, 0x0D Si7013).
     */
    async deviceType() {
        return (await this._conn.writeRead(Buffer.from([0xFC, 0xC9]), 6))[0];
    }

    /**
     * Read the firmware revision (command 0x84 0xB8).
     * @returns {Promise<number>} 0xFF for firmware 1.0.
     */
    async firmwareRevision() {
        return (await this._conn.writeRead(Buffer.from([0x84, 0xB8]), 1))[0];
    }

    /**
     * Typical RH + temperature conversion time at the current resolution.
     * @returns {Promise<number>} Time in seconds.
     */
    async conversionTime() {
        const reg = await this.readUserRegister();
        const res = (((reg >> 7) & 1) << 1) | (reg & 1);
        return _RH_TIME[res] + _T_TIME[res];
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula).
     * @returns {Promise<number>} Dew point in °C.
     */
    async dewPoint() {
        let [t, rh] = await this.read();
        if (rh <= 0.0) rh = 0.01;
        const gamma = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
        return 243.12 * gamma / (17.62 - gamma);
    }
}

module.exports = { Si7021Minimal, Si7021Full, crc8 };
