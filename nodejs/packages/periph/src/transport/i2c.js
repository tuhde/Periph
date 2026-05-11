'use strict';

const i2c = require('i2c-bus');

/**
 * I²C transport for Node.js (wraps i2c-bus, uses /dev/i2c-N on Linux).
 *
 * One instance represents one device on the bus; the bus is opened
 * synchronously at construction. Call close() to release the bus when done.
 */
class I2CTransport {
    /**
     * @param {number} busNumber - I²C bus number (opens /dev/i2c-{busNumber}).
     * @param {number} addr      - 7-bit device address.
     */
    constructor(busNumber, addr) {
        this._bus = i2c.openSync(busNumber);
        this._addr = addr;
    }

    /**
     * Send bytes to the device.
     * @param {Buffer|Uint8Array} data - Bytes to write.
     */
    write(data) {
        this._bus.i2cWriteSync(this._addr, data.length, data);
    }

    /**
     * Read bytes from the device.
     * @param {number} n - Number of bytes to read.
     * @returns {Buffer} Data received from the device.
     */
    read(n) {
        const buf = Buffer.alloc(n);
        this._bus.i2cReadSync(this._addr, n, buf);
        return buf;
    }

    /**
     * Write a single register address byte then read data back (repeated start).
     *
     * Uses readI2cBlockSync, which accepts exactly one command byte. Only
     * data[0] is sent; any additional bytes in data are ignored. This is a
     * library constraint: i2c-bus v5 has no synchronous multi-message transfer
     * API (I2C_RDWR with multiple messages), so multi-byte writes before the
     * repeated start are not supported in synchronous mode.
     *
     * @param {Buffer|Uint8Array} data - Register address to send; only data[0] is used.
     * @param {number}            n    - Number of bytes to read back.
     * @returns {Buffer} Data received from the device.
     */
    writeRead(data, n) {
        const buf = Buffer.alloc(n);
        this._bus.readI2cBlockSync(this._addr, data[0], n, buf);
        return buf;
    }

    /**
     * Close the I²C bus. Must be called when the transport is no longer needed.
     */
    close() {
        this._bus.closeSync();
    }
}

module.exports = { I2CTransport };
