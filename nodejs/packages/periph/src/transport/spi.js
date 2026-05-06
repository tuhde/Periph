'use strict';

const spi = require('spi-device');

class SPITransport {
    constructor(busNumber, deviceNumber, options = {}) {
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: options.mode ?? spi.MODE0,
            maxSpeedHz: options.maxSpeedHz ?? 1_000_000,
        });
    }

    write(data) {
        const sendBuffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
        this._device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
    }

    read(n) {
        const receiveBuffer = Buffer.alloc(n);
        this._device.transferSync([{ receiveBuffer, byteLength: n }]);
        return receiveBuffer;
    }

    writeRead(data, n) {
        const prefix = Buffer.isBuffer(data) ? data : Buffer.from(data);
        const sendBuffer = Buffer.concat([prefix, Buffer.alloc(n)]);
        const receiveBuffer = Buffer.alloc(sendBuffer.length);
        this._device.transferSync([{ sendBuffer, receiveBuffer, byteLength: sendBuffer.length }]);
        return receiveBuffer.subarray(prefix.length);
    }

    close() {
        this._device.closeSync();
    }
}

module.exports = { SPITransport };
