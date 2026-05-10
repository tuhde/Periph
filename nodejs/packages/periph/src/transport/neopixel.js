'use strict';

const spi = require('spi-device');

class NeoPixelTransport {
    constructor(busNumber, deviceNumber) {
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: spi.MODE0,
            maxSpeedHz: 2_400_000,
        });
    }

    _encode(data) {
        const out = Buffer.alloc(data.length * 3 + 16);
        for (let i = 0; i < data.length; i++) {
            const byte = data[i];
            for (let bit = 7; bit >= 0; bit--) {
                const idx = i * 3 + (7 - bit);
                out[idx] = ((byte >> bit) & 1) ? 0b110 : 0b100;
            }
        }
        for (let i = 0; i < 16; i++) {
            out[data.length * 3 + i] = 0x00;
        }
        return out;
    }

    write(data) {
        const sendBuffer = this._encode(data);
        this._device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
    }

    close() {
        this._device.closeSync();
    }
}

module.exports = { NeoPixelTransport };