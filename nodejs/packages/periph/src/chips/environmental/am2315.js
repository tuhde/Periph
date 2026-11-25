'use strict';

const _FUNC_READ = 0x03;
const _FUNC_WRITE = 0x10;
const _MIN_INTERVAL_MS = 2000;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-16 (Modbus) used by the AM2315: init 0xFFFF, polynomial 0xA001 (reflected).
 * @param {Buffer|number[]} data
 * @returns {number}
 */
function crc16(data) {
    let crc = 0xFFFF;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc & 1) ? (crc >> 1) ^ 0xA001 : crc >> 1;
    }
    return crc;
}

/** The AM2315 answered with an error response (function code with bit 7 set); `code` is 0x80-0x84. */
class AM2315Error extends Error {
    constructor(code) {
        super('AM2315 error response 0x' + code.toString(16).toUpperCase());
        this.code = code;
    }
}

function _toTemperature(raw) {
    const t = (raw & 0x7FFF) / 10.0;
    return (raw & 0x8000) ? -t : t;
}

/**
 * AM2315 humidity and temperature sensor — minimal interface.
 *
 * The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the sensor does
 * not acknowledge), a command write, a 2 ms wait and a read whose response carries a CRC-16.
 * The sensor measures after each communication and returns that result on the next one, so
 * `read()` returns the previous measurement; transactions are kept at least 2 s apart by
 * sleeping the remainder.
 *
 * Default configuration: the constructor does not touch the bus; the first `read()` waits until
 * 2 s after construction (power-up settling); one retry after a new wake on a CRC mismatch or
 * bus error.
 */
class AM2315Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x5C, standard mode, at most 100 kHz).
     */
    constructor(connection) {
        this._conn = connection;
        this._minIntervalMs = _MIN_INTERVAL_MS;
        this._last = Date.now();
    }

    async _waitInterval() {
        if (this._minIntervalMs <= 0) return;
        const remaining = this._minIntervalMs - (Date.now() - this._last);
        if (remaining > 0) await _sleep(remaining);
    }

    async _wake() {
        try {
            await this._conn.write(Buffer.from([0x00]));   // the sleeping sensor NACKs this
        } catch (e) { /* expected */ }
        await _sleep(1);
    }

    async _exchange(frame, responseLen) {
        await this._waitInterval();
        await this._wake();
        await this._conn.write(Buffer.from(frame));
        await _sleep(2);
        const data = Buffer.from(await this._conn.read(responseLen));
        this._last = Date.now();
        return data;
    }

    _check(data, fn, responseLen) {
        if (data.length >= 1 && (data[0] & 0x80)) throw new AM2315Error(data[0]);
        if (data.length < responseLen) throw new Error('AM2315 short response');
        if (data[0] !== fn) throw new Error('AM2315 unexpected function code');
        const crc = data[responseLen - 2] | (data[responseLen - 1] << 8);
        if (crc16(data.subarray(0, responseLen - 2)) !== crc) throw new Error('AM2315 CRC mismatch');
    }

    async _withRetry(fn) {
        try {
            return await fn();
        } catch (e) {
            if (e instanceof AM2315Error) throw e;
            return await fn();
        }
    }

    async _readRegisters(start, count) {
        return this._withRetry(async () => {
            const data = await this._exchange([_FUNC_READ, start, count], count + 4);
            this._check(data, _FUNC_READ, count + 4);
            if (data[1] !== count) throw new Error('AM2315 unexpected byte count');
            return data.subarray(2, 2 + count);
        });
    }

    /**
     * Read temperature and humidity (registers 0x00-0x03). Returns the previous measurement;
     * see `AM2315Full.readFresh()` for the real-time value.
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} °C and %RH.
     */
    async read() {
        const d = await this._readRegisters(0x00, 4);
        return { temperature_c: _toTemperature((d[2] << 8) | d[3]), humidity_pct: ((d[0] << 8) | d[1]) / 10.0 };
    }
}

/**
 * AM2315 full interface — single values, raw words, fresh read, registers, user registers.
 *
 * Extends AM2315Minimal with temperature-only and humidity-only reads, raw words, the two-read
 * real-time value, arbitrary register read/write (CRC verified), device information, the user
 * registers and an explicit wake-up.
 */
class AM2315Full extends AM2315Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x5C).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Read the temperature only (registers 0x02-0x03).
     * @returns {Promise<number>} °C.
     */
    async readTemperature() {
        const d = await this._readRegisters(0x02, 2);
        return _toTemperature((d[0] << 8) | d[1]);
    }

    /**
     * Read the humidity only (registers 0x00-0x01).
     * @returns {Promise<number>} %RH.
     */
    async readHumidity() {
        const d = await this._readRegisters(0x00, 2);
        return ((d[0] << 8) | d[1]) / 10.0;
    }

    /**
     * Read the raw 16-bit words; the temperature word is sign + magnitude.
     * @returns {Promise<number[]>} [rawHumidity, rawTemperature].
     */
    async readRaw() {
        const d = await this._readRegisters(0x00, 4);
        return [(d[0] << 8) | d[1], (d[2] << 8) | d[3]];
    }

    /**
     * Read twice (at least 2 s apart) and return the second, real-time result.
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>}
     */
    async readFresh() {
        await this.read();
        return this.read();
    }

    /**
     * Read `count` registers from `start` (function 0x03, CRC verified).
     * @param {number} start - First register address (0x00-0x1F).
     * @param {number} count - Number of registers, 1-10.
     * @returns {Promise<Buffer>}
     */
    async readRegisters(start, count) {
        if (!(count >= 1 && count <= 10)) throw new RangeError('count must be 1-10');
        if (!(start >= 0 && start <= 0x1F && start + count <= 0x20)) throw new RangeError('register range out of bounds');
        return Buffer.from(await this._readRegisters(start, count));
    }

    /**
     * Write the user registers 0x10-0x13 (function 0x10 with CRC).
     * @param {number} start - First register address (0x10-0x13).
     * @param {Buffer|number[]} data - 1-4 bytes; start + length must not pass 0x14.
     * @returns {Promise<void>}
     */
    async writeRegisters(start, data) {
        const bytes = Buffer.from(data);
        if (bytes.length < 1 || bytes.length > 4 || start < 0x10 || start + bytes.length > 0x14) {
            throw new RangeError('only the user registers 0x10-0x13 may be written');
        }
        const body = Buffer.concat([Buffer.from([_FUNC_WRITE, start, bytes.length]), bytes]);
        const crc = crc16(body);
        const frame = Buffer.concat([body, Buffer.from([crc & 0xFF, crc >> 8])]);
        await this._withRetry(async () => {
            const resp = await this._exchange(frame, 5);
            this._check(resp, _FUNC_WRITE, 5);
            if (resp[1] !== start || resp[2] !== bytes.length) throw new Error('AM2315 unexpected write response');
        });
    }

    /**
     * Read the device information (registers 0x08-0x0E).
     * @returns {Promise<{model: number, version: number, device_id: number}>}
     */
    async readDeviceInfo() {
        const d = await this._readRegisters(0x08, 7);
        return { model: (d[0] << 8) | d[1], version: d[2], device_id: d.readUInt32BE(3) };
    }

    /**
     * Read the two 16-bit user registers (0x10-0x13).
     * @returns {Promise<number[]>} [user1, user2].
     */
    async readUserRegisters() {
        const d = await this._readRegisters(0x10, 4);
        return [(d[0] << 8) | d[1], (d[2] << 8) | d[3]];
    }

    /**
     * Write the two 16-bit user registers (0x10-0x13).
     * @param {number} user1 - 16-bit value for registers 0x10-0x11.
     * @param {number} user2 - 16-bit value for registers 0x12-0x13.
     * @returns {Promise<void>}
     */
    async writeUserRegisters(user1, user2) {
        await this.writeRegisters(0x10, [(user1 >> 8) & 0xFF, user1 & 0xFF, (user2 >> 8) & 0xFF, user2 & 0xFF]);
    }

    /** Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    async wake() {
        await this._wake();
    }
}

module.exports = { AM2315Minimal, AM2315Full, AM2315Error, crc16 };
