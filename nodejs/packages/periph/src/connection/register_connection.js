'use strict';

const { Connection } = require('./connection');

/**
 * Connection with register-addressed read/write, for I2C/SMBus/SPI-style buses.
 *
 * Sits between Connection and the register-capable concrete classes
 * (I2CConnection, SMBusConnection, SPIConnection); every other concrete
 * connection (UARTConnection, HX711Connection, NeoPixelConnection,
 * SiPoConnection, DHTxxConnection) is unaffected and continues to extend
 * Connection only.
 *
 * The default implementation below (writeRead/write of a leading, big-endian
 * register-address of `regBytes` bytes) matches I2C/SMBus behavior as-is;
 * SPIConnection overrides both to build its single-byte command byte instead
 * (see specs/feature_register_access_design.md §11.2 for why SPI stays
 * single-byte).
 */
class RegisterConnection extends Connection {
    /**
     * @param {import('./input_pin').InputPin|null} [intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [enPin=null] - Optional EN-pin OutputPin.
     * @param {number} [regBytes=1] - Register address width in bytes, big-endian.
     */
    constructor(intPin = null, enPin = null, regBytes = 1) {
        super(intPin, enPin);
        this._regBytes = regBytes;
    }

    /**
     * Read `length` bytes starting at register `reg`.
     * @param {number} reg    - Register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async readReg(reg, length) {
        return this.writeRead(this._regAddrBytes(reg), length);
    }

    /**
     * Write `data` to register `reg`.
     * @param {number} reg - Register address.
     * @param {Buffer|Uint8Array|number} data - Bytes to write, or a single int for a 1-byte register.
     * @returns {Promise<void>}
     */
    async writeReg(reg, data) {
        const payload = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'number' ? [data] : data);
        return this.write(Buffer.concat([this._regAddrBytes(reg), payload]));
    }

    /**
     * Build a big-endian register address of `this._regBytes` bytes.
     * @param {number} reg - Register address.
     * @returns {Buffer}
     */
    _regAddrBytes(reg) {
        const addr = Buffer.alloc(this._regBytes);
        addr.writeUIntBE(reg, 0, this._regBytes);
        return addr;
    }
}

module.exports = { RegisterConnection };
