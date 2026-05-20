'use strict';

const spi = require('spi-device');

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

class NeoPixelTransport {
    constructor(busNumber, deviceNumber) {
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: spi.MODE0,
            maxSpeedHz: 2_400_000,
        });
    }

    write(data) {
        const encoded = _encode(Buffer.isBuffer(data) ? data : Buffer.from(data));
        this._device.transferSync([{ sendBuffer: encoded, byteLength: encoded.length }]);
    }

    close() {
        this._device.closeSync();
    }
}

module.exports = { NeoPixelTransport };