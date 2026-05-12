'use strict';

const i2c = require('i2c-bus');

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
 * SMBus transport for Node.js (wraps i2c-bus, uses /dev/i2c-N on Linux).
 *
 * Enforces the valid 7-bit SMBus address range (0x08–0x77) and, when pec=true,
 * appends a CRC-8 byte to writes and verifies it on reads. PEC is computed in
 * software using raw i2c transfers. Call close() to release the bus when done.
 */
class SMBusTransport {
    /**
     * @param {number}  busNumber    - I²C bus number (opens /dev/i2c-{busNumber}).
     * @param {number}  addr         - 7-bit device address (0x08–0x77).
     * @param {boolean} [pec=false]  - Enable Packet Error Code (CRC-8) checking.
     * @throws {RangeError} If addr is outside the valid SMBus range.
     */
    constructor(busNumber, addr, pec = false) {
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
    write(data) {
        let buf = Buffer.from(data);
        if (this._pec) {
            const pec = crc8(Buffer.concat([Buffer.from([this._addr << 1]), buf]));
            buf = Buffer.concat([buf, Buffer.from([pec])]);
        }
        this._bus.i2cWriteSync(this._addr, buf.length, buf);
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
    read(n) {
        const count = this._pec ? n + 1 : n;
        const buf = Buffer.alloc(count);
        this._bus.i2cReadSync(this._addr, count, buf);
        if (this._pec) {
            const data = buf.slice(0, n);
            const expected = crc8(Buffer.concat([Buffer.from([(this._addr << 1) | 1]), data]));
            if (expected !== buf[n]) throw new Error('SMBus PEC error');
            return data;
        }
        return buf;
    }

    /**
     * Write a register address byte then read data back (repeated start), with optional PEC.
     *
     * PEC covers the full transaction: write address + write data +
     * read address + read data. The same single-command-byte limitation as
     * I2CTransport applies: only data[0] is sent as the write byte.
     *
     * @param {Buffer|Uint8Array} data - Register address to send; only data[0] is used.
     * @param {number}            n    - Number of data bytes to read back.
     * @returns {Buffer} The n data bytes (PEC byte stripped if enabled).
     * @throws {Error} If the received PEC byte does not match.
     */
    writeRead(data, n) {
        const count = this._pec ? n + 1 : n;
        const buf = Buffer.alloc(count);
        this._bus.readI2cBlockSync(this._addr, data[0], count, buf);
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

    /**
     * Close the I²C bus. Must be called when the transport is no longer needed.
     */
    close() {
        this._bus.closeSync();
    }
}

module.exports = { SMBusTransport };
