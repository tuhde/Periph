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
 * The default implementation below (writeRead/write of a leading
 * register-address byte) matches I2C/SMBus behavior as-is; SPIConnection
 * overrides both to build its command byte first.
 */
class RegisterConnection extends Connection {
    /**
     * Read `length` bytes starting at register `reg`.
     * @param {number} reg    - Register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async readReg(reg, length) {
        return this.writeRead(Buffer.from([reg]), length);
    }

    /**
     * Write `data` to register `reg`.
     * @param {number} reg - Register address.
     * @param {Buffer|Uint8Array|number} data - Bytes to write, or a single int for a 1-byte register.
     * @returns {Promise<void>}
     */
    async writeReg(reg, data) {
        const payload = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'number' ? [data] : data);
        return this.write(Buffer.concat([Buffer.from([reg]), payload]));
    }
}

module.exports = { RegisterConnection };
