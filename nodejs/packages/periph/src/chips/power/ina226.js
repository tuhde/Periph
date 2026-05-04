'use strict';

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;
const _REG_MASK    = 0x06;
const _REG_ALERT   = 0x07;
const _REG_MFR_ID  = 0xFE;
const _REG_DIE_ID  = 0xFF;

const _CONFIG_DEFAULT = 0x4127;

class INA226Minimal {
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        this._transport = transport;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.floor(0.00512 / (this._currentLsb * rShunt));
        this._writeReg(_REG_CONFIG, _CONFIG_DEFAULT);
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

    voltage()      { return this._readReg(_REG_BUS) * 1.25e-3; }
    shuntVoltage() { return this._readRegSigned(_REG_SHUNT) * 2.5e-6; }
    current()      { return this._readRegSigned(_REG_CURRENT) * this._currentLsb; }
    power()        { return this._readReg(_REG_POWER) * 25 * this._currentLsb; }
}

class INA226Full extends INA226Minimal {
    static SOL  = 0x8000;
    static SUL  = 0x4000;
    static BOL  = 0x2000;
    static BUL  = 0x1000;
    static POL  = 0x0800;
    static CNVR = 0x0400;

    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        super(transport, rShunt, maxCurrent);
        this._mode = 0x07;
    }

    configure(avg = 0, vbusCt = 4, vshCt = 4, mode = 7) {
        const config = ((avg & 0x07) << 9) | ((vbusCt & 0x07) << 6) | ((vshCt & 0x07) << 3) | (mode & 0x07);
        this._mode = mode & 0x07;
        this._writeReg(_REG_CONFIG, config);
    }

    conversionReady() { return !!(this._readReg(_REG_MASK) & 0x0008); }
    overflow()        { return !!(this._readReg(_REG_MASK) & 0x0004); }

    setAlert(fn, limit = 0, polarity = 0, latch = 0) {
        let raw = 0;
        if (fn === INA226Full.SOL || fn === INA226Full.SUL) raw = Math.floor(limit / 2.5e-6);
        else if (fn === INA226Full.BOL || fn === INA226Full.BUL) raw = Math.floor(limit / 1.25e-3);
        else if (fn === INA226Full.POL) raw = Math.floor(limit / (25 * this._currentLsb));
        const mask = fn | ((polarity & 1) << 1) | (latch & 1);
        this._writeReg(_REG_MASK, mask);
        this._writeReg(_REG_ALERT, raw & 0xFFFF);
    }

    alertFlags()     { return this._readReg(_REG_MASK); }

    reset() {
        this._writeReg(_REG_CONFIG, 0x8000);
        this._writeReg(_REG_CAL, this._cal);
    }

    shutdown() {
        const config = this._readReg(_REG_CONFIG);
        this._mode = config & 0x07;
        this._writeReg(_REG_CONFIG, config & 0xFFF8);
    }

    wake() {
        const config = this._readReg(_REG_CONFIG);
        this._writeReg(_REG_CONFIG, (config & 0xFFF8) | this._mode);
    }

    manufacturerId() { return this._readReg(_REG_MFR_ID); }
    dieId()          { return this._readReg(_REG_DIE_ID); }
}

module.exports = { INA226Minimal, INA226Full };
