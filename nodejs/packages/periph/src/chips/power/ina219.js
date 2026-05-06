'use strict';

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;

class INA219Minimal {
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        this._transport = transport;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.floor(0.04096 / (this._currentLsb * rShunt)) & 0xFFFE;
        this._writeReg(_REG_CAL, this._cal);
    }

    _writeReg(reg, value) {
        const buf = Buffer.alloc(3);
        buf[0] = reg;
        buf.writeUInt16BE(value, 1);
        this._transport.write(buf);
    }

    _readReg(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 2).readUInt16BE(0);
    }

    _readRegSigned(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 2).readInt16BE(0);
    }

    voltage()      { return (this._readReg(_REG_BUS) >> 3) * 4e-3; }
    shuntVoltage() { return this._readRegSigned(_REG_SHUNT) * 10e-6; }
    current()      { return this._readRegSigned(_REG_CURRENT) * this._currentLsb; }
    power()        { return this._readReg(_REG_POWER) * 20 * this._currentLsb; }
}

module.exports = { INA219Minimal };
