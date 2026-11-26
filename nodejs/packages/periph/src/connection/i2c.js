'use strict';

const i2c = require('i2c-bus');
const { RegisterConnection } = require('./register_connection');

/** Largest read readI2cBlockSync (SMBus I²C block read) can do in one transaction. */
const I2C_BLOCK_MAX = 32;

/**
 * Throw if an i2c-bus call moved fewer bytes than requested. i2c-bus already
 * throws (with errno) when the underlying read()/write()/ioctl() fails; this
 * catches the short transfer it reports only through its return value.
 * @param {string} what  - Call name for the message, e.g. 'i2cReadSync(0x48)'.
 * @param {number} got   - Bytes the call reported.
 * @param {number} want  - Bytes requested.
 */
function checkCount(what, got, want) {
    if (got !== want) {
        const err = new Error(`${what} transferred ${got} of ${want} bytes`);
        err.code = 'EIO';
        throw err;
    }
}

/**
 * I²C connection for Node.js (wraps i2c-bus, uses /dev/i2c-N on Linux).
 *
 * One instance represents one device on the bus; the bus is opened
 * synchronously at construction. Call close() to release the bus when done.
 */
class I2CConnection extends RegisterConnection {
    /**
     * @param {number} busNumber - I²C bus number (opens /dev/i2c-{busNumber}).
     * @param {number} addr      - 7-bit device address.
     * @param {import('./input_pin').InputPin|null} [intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [enPin=null] - Optional EN-pin OutputPin.
     * @param {boolean} [enActiveHigh=true] - True if the EN pin is active-high (default); false for active-low.
     * @param {number} [regBytes=1] - Register address width in bytes, big-endian.
     */
    constructor(busNumber, addr, intPin = null, enPin = null, regBytes = 1, enActiveHigh = true) {
        super(intPin, enPin, regBytes, enActiveHigh);
        this._bus = i2c.openSync(busNumber);
        this._addr = addr;
    }

    /**
     * Send bytes to the device.
     * @param {Buffer|Uint8Array} data - Bytes to write.
     */
    async _write(data) {
        const buf = Buffer.from(data);
        checkCount(this._what('i2cWriteSync'), this._bus.i2cWriteSync(this._addr, buf.length, buf), buf.length);
    }

    /**
     * Read bytes from the device.
     * @param {number} n - Number of bytes to read.
     * @returns {Buffer} Data received from the device.
     */
    async _read(n) {
        const buf = Buffer.alloc(n);
        checkCount(this._what('i2cReadSync'), this._bus.i2cReadSync(this._addr, n, buf), n);
        return buf;
    }

    /**
     * Write register/command bytes then read data back.
     *
     * i2c-bus has no synchronous combined (I2C_RDWR) transfer, so:
     * - one command byte and at most 32 bytes to read: readI2cBlockSync, a single
     *   transaction with a repeated START between the phases;
     * - anything else (multi-byte register addresses, reads over 32 bytes): the
     *   full `data` is written, then `n` bytes are read, as two transactions
     *   (STOP + START). No bytes of `data` are dropped.
     *
     * Either way a short transfer throws.
     *
     * @param {Buffer|Uint8Array} data - Register/command bytes to send.
     * @param {number}            n    - Number of bytes to read back.
     * @returns {Buffer} Data received from the device.
     */
    async _writeRead(data, n) {
        if (data.length === 1 && n <= I2C_BLOCK_MAX) {
            const buf = Buffer.alloc(n);
            checkCount(this._what('readI2cBlockSync'),
                this._bus.readI2cBlockSync(this._addr, data[0], n, buf), n);
            return buf;
        }
        await this._write(data);
        return this._read(n);
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

module.exports = { I2CConnection, checkCount, I2C_BLOCK_MAX };
