'use strict';

const spi = require('spi-device');
const { Connection } = require('./connection');

function _encode(data) {
    const out = Buffer.alloc(data.length * 3 + 16);
    for (let i = 0; i < data.length; i++) {
        let bits = 0;
        for (let bit = 7; bit >= 0; bit--) {
            bits = (bits << 3) | ((data[i] >> bit) & 1 ? 0b110 : 0b100);
        }
        out[i * 3]     = (bits >> 16) & 0xFF;
        out[i * 3 + 1] = (bits >> 8) & 0xFF;
        out[i * 3 + 2] = bits & 0xFF;
    }
    return out;
}

/**
 * NeoPixel (WS2812B/SK6812) connection for Node.js — SPI-encoded, write-only.
 *
 * Encodes each data byte into 3 SPI bytes (3 bits per data bit) so an SPI
 * MOSI line at 2.4 MHz produces the WS2812B/SK6812 one-wire timing.
 */
class NeoPixelConnection extends Connection {
    /**
     * @param {number} busNumber    - SPI bus number.
     * @param {number} deviceNumber - Chip-select line on the bus (unused electrically; MOSI only).
     */
    constructor(busNumber, deviceNumber) {
        super();
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: spi.MODE0,
            maxSpeedHz: 2_400_000,
        });
    }

    /**
     * Encode and send pixel data.
     * @param {Buffer|Uint8Array} data - Raw GRB/GRBW pixel bytes.
     */
    async _write(data) {
        const encoded = _encode(Buffer.isBuffer(data) ? data : Buffer.from(data));
        this._device.transferSync([{ sendBuffer: encoded, byteLength: encoded.length }]);
    }

    /**
     * Close the SPI device. Must be called when the connection is no longer needed.
     */
    async close() {
        this._device.closeSync();
    }
}

module.exports = { NeoPixelConnection };
