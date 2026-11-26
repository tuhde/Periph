'use strict';

const i2c = require('i2c-bus');
const { RegisterConnection } = require('./register_connection');
const { checkCount, I2C_BLOCK_MAX } = require('./i2c');

/**
 * Computes CRC-8 with polynomial 0x07 (x⁸ + x² + x + 1), initial value 0x00.
 * @param {Buffer|Uint8Array} data
 * @returns {number}
 */
function crc8(data) {
    let crc = 0;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) {
            crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
    }
    return crc;
}

/**
 * SMBus connection for Node.js (wraps i2c-bus, uses /dev/i2c-N on Linux).
 *
 * Enforces the valid 7-bit SMBus address range (0x08–0x77) and, when pec=true,
 * appends a CRC-8 byte to writes and verifies it on reads. PEC is computed in
 * software using raw i2c transfers. Call close() to release the bus when done.
 */
class SMBusConnection extends RegisterConnection {
    /**
     * @param {number}  busNumber    - I²C bus number (opens /dev/i2c-{busNumber}).
     * @param {number}  addr         - 7-bit device address (0x08–0x77).
     * @param {boolean} [pec=false]  - Enable Packet Error Code (CRC-8) checking.
     * @param {import('./input_pin').InputPin|null} [intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [enPin=null] - Optional EN-pin OutputPin.
     * @param {boolean} [enActiveHigh=true] - True if the EN pin is active-high (default); false for active-low.
     * @param {number} [regBytes=1] - Register address width in bytes, big-endian.
     * @throws {RangeError} If addr is outside the valid SMBus range.
     */
    constructor(busNumber, addr, pec = false, intPin = null, enPin = null, regBytes = 1, enActiveHigh = true) {
        super(intPin, enPin, regBytes, enActiveHigh);
        if (addr < 0x08 || addr > 0x77) {
            throw new RangeError('SMBus address must be in range 0x08-0x77');
        }
        this._bus  = i2c.openSync(busNumber);
        this._addr = addr;
        this._pec  = pec;
    }

    /**
     * Send bytes to the device, appending a PEC byte if enabled.
     * @param {Buffer|Uint8Array} data - Bytes to write.
     */
    async _write(data) {
        let buf = Buffer.from(data);
        if (this._pec) {
            const pec = crc8(Buffer.concat([Buffer.from([this._addr << 1]), buf]));
            buf = Buffer.concat([buf, Buffer.from([pec])]);
        }
        checkCount(this._what('i2cWriteSync'), this._bus.i2cWriteSync(this._addr, buf.length, buf), buf.length);
    }

    /**
     * Read bytes from the device, verifying the PEC byte if enabled.
     *
     * Reads n+1 bytes when PEC is enabled; the trailing byte is the CRC.
     *
     * @param {number} n - Number of data bytes to read.
     * @returns {Buffer} The n data bytes (PEC byte stripped if enabled).
     * @throws {Error} If the received PEC byte does not match.
     */
    async _read(n) {
        const count = this._pec ? n + 1 : n;
        const buf = Buffer.alloc(count);
        checkCount(this._what('i2cReadSync'), this._bus.i2cReadSync(this._addr, count, buf), count);
        if (this._pec) {
            const data = buf.slice(0, n);
            const expected = crc8(Buffer.concat([Buffer.from([(this._addr << 1) | 1]), data]));
            if (expected !== buf[n]) throw new Error('SMBus PEC error');
            return data;
        }
        return buf;
    }

    /**
     * Write register/command bytes then read data back, with optional PEC.
     *
     * One command byte and at most 32 bytes to read (PEC byte included) use
     * readI2cBlockSync: one transaction with a repeated START, and the PEC covers
     * write address + command + read address + read data. Anything else is a
     * write (with its own PEC) followed by a read (with its own PEC) as two
     * transactions; no bytes of `data` are dropped. A short transfer throws.
     *
     * @param {Buffer|Uint8Array} data - Register/command bytes to send.
     * @param {number}            n    - Number of data bytes to read back.
     * @returns {Buffer} The n data bytes (PEC byte stripped if enabled).
     * @throws {Error} If the received PEC byte does not match.
     */
    async _writeRead(data, n) {
        const count = this._pec ? n + 1 : n;
        if (data.length !== 1 || count > I2C_BLOCK_MAX) {
            await this._write(data);
            return this._read(n);
        }
        const buf = Buffer.alloc(count);
        checkCount(this._what('readI2cBlockSync'),
            this._bus.readI2cBlockSync(this._addr, data[0], count, buf), count);
        if (this._pec) {
            const payload = buf.slice(0, n);
            const expected = crc8(Buffer.concat([
                Buffer.from([this._addr << 1]),
                Buffer.from([data[0]]),
                Buffer.from([(this._addr << 1) | 1]),
                payload,
            ]));
            if (expected !== buf[n]) throw new Error('SMBus PEC error');
            return payload;
        }
        return buf;
    }

    /** @private Call name plus device address for error messages. */
    _what(call) {
        return `${call}(0x${this._addr.toString(16).padStart(2, '0')})`;
    }

    /**
     * Close the I²C bus. Must be called when the connection is no longer needed.
     */
    async close() {
        this._bus.closeSync();
    }
}

module.exports = { SMBusConnection };
