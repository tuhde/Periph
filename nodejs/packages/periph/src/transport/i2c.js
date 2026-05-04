'use strict';

const i2c = require('i2c-bus');

class I2CTransport {
    constructor(busNumber, addr) {
        this._bus = i2c.openSync(busNumber);
        this._addr = addr;
    }

    write(data) {
        this._bus.i2cWriteSync(this._addr, data.length, data);
    }

    read(n) {
        const buf = Buffer.alloc(n);
        this._bus.i2cReadSync(this._addr, n, buf);
        return buf;
    }

    writeRead(data, n) {
        const buf = Buffer.alloc(n);
        this._bus.readI2cBlockSync(this._addr, data[0], n, buf);
        return buf;
    }

    close() {
        this._bus.closeSync();
    }
}

module.exports = { I2CTransport };
