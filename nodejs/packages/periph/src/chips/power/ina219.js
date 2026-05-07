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

class INA219Full extends INA219Minimal {
    static PGA_1  = 0x00;
    static PGA_2  = 0x01;
    static PGA_4  = 0x02;
    static PGA_8  = 0x03;

    static BRNG_16V = 0x00;
    static BRNG_32V = 0x01;

    static ADC_9BIT    = 0x00;
    static ADC_10BIT   = 0x01;
    static ADC_11BIT   = 0x02;
    static ADC_12BIT   = 0x03;
    static ADC_AVG_2   = 0x08;
    static ADC_AVG_4   = 0x09;
    static ADC_AVG_8   = 0x0A;
    static ADC_AVG_16  = 0x0B;
    static ADC_AVG_32  = 0x0C;
    static ADC_AVG_64  = 0x0D;
    static ADC_AVG_128 = 0x0E;

    static MODE_POWERDOWN      = 0x00;
    static MODE_SHUNT_TRIG     = 0x01;
    static MODE_BUS_TRIG       = 0x02;
    static MODE_SHUNT_BUS_TRIG = 0x03;
    static MODE_ADC_OFF        = 0x04;
    static MODE_SHUNT_CONT     = 0x05;
    static MODE_BUS_CONT       = 0x06;
    static MODE_SHUNT_BUS_CONT = 0x07;

    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        super(transport, rShunt, maxCurrent);
        this._mode = 0x07;
    }

    configure(brng = 1, pga = 3, badc = 3, sadc = 3, mode = 7) {
        const config = ((brng & 1) << 13) | ((pga & 3) << 11) | ((badc & 0xF) << 7) | ((sadc & 0xF) << 3) | (mode & 7);
        this._mode = mode & 7;
        this._writeReg(_REG_CONFIG, config);
        this._writeReg(_REG_CAL, this._cal);
    }

    conversionReady() { return !!(this._readReg(_REG_BUS) & 0x0002); }
    overflow()        { return !!(this._readReg(_REG_BUS) & 0x0001); }

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

    trigger() {
        const config = this._readReg(_REG_CONFIG);
        this._writeReg(_REG_CONFIG, (config & 0xFFF8) | this._mode);
    }
}

module.exports = { INA219Minimal, INA219Full };
