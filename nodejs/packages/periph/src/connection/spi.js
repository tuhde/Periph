'use strict';

const spi = require('spi-device');
const { Connection } = require('./connection');

/**
 * SPI connection for Node.js (wraps spi-device).
 *
 * Opens the spidev device synchronously at construction. CS is managed by
 * the kernel spidev driver. Call close() when done.
 */
class SPIConnection extends Connection {
    /**
     * @param {number} busNumber    - SPI bus number.
     * @param {number} deviceNumber - Chip-select line on the bus.
     * @param {object} [options]
     * @param {number} [options.mode=spi.MODE0]       - SPI mode (0–3).
     * @param {number} [options.maxSpeedHz=1_000_000] - Clock frequency in Hz.
     * @param {import('./input_pin').InputPin|null} [options.intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [options.enPin=null] - Optional EN-pin OutputPin.
     */
    constructor(busNumber, deviceNumber, options = {}) {
        super(options.intPin ?? null, options.enPin ?? null);
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: options.mode ?? spi.MODE0,
            maxSpeedHz: options.maxSpeedHz ?? 1_000_000,
        });
    }

    /**
     * Send bytes to the device.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     */
    async _write(data) {
        const sendBuffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
        this._device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
    }

    /**
     * Read bytes from the device.
     * @param {number} n - Number of bytes to read.
     * @returns {Buffer} Data received from the device.
     */
    async _read(n) {
        const receiveBuffer = Buffer.alloc(n);
        this._device.transferSync([{ receiveBuffer, byteLength: n }]);
        return receiveBuffer;
    }

    /**
     * Full-duplex write+read in a single SPI transfer (CS held for the entire transfer).
     *
     * Sends len(data)+n bytes total and discards the first len(data) received
     * bytes (chip response during the command phase).
     *
     * @param {Buffer|Uint8Array} data - Command bytes to send.
     * @param {number}            n    - Number of response bytes expected.
     * @returns {Buffer} The n response bytes.
     */
    async _writeRead(data, n) {
        const prefix = Buffer.isBuffer(data) ? data : Buffer.from(data);
        const sendBuffer = Buffer.concat([prefix, Buffer.alloc(n)]);
        const receiveBuffer = Buffer.alloc(sendBuffer.length);
        this._device.transferSync([{ sendBuffer, receiveBuffer, byteLength: sendBuffer.length }]);
        return receiveBuffer.subarray(prefix.length);
    }

    /**
     * Close the SPI device. Must be called when the connection is no longer needed.
     */
    async close() {
        this._device.closeSync();
    }
}

module.exports = { SPIConnection };
