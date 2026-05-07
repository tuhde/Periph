'use strict';

const _FAST_WRITE      = 0x00;
const _WRITE_DAC       = 0x40;
const _WRITE_DAC_EEPROM = 0x60;
const _GENERAL_CALL_ADDR = 0x00;
const _GEN_CALL_RESET   = 0x06;
const _GEN_CALL_WAKEUP  = 0x09;

const _MCP47xxBase = (SuperClass) => class extends SuperClass {
    wake_up() {
        this._transport.write(Buffer.from([_GENERAL_CALL_ADDR, _GEN_CALL_WAKEUP]));
    }

    reset() {
        this._transport.write(Buffer.from([_GENERAL_CALL_ADDR, _GEN_CALL_RESET]));
    }

    is_eeprom_ready() {
        const raw = this._transport.writeRead(Buffer.from([0x00]), 1);
        return (raw[0] & 0x80) !== 0;
    }
};

class MCP4725Minimal {
    constructor(transport) {
        this._transport = transport;
    }

    set_voltage(fraction) {
        const code = Math.round(Math.max(0, Math.min(1, fraction)) * 4095);
        this.set_raw(code);
    }

    set_raw(code) {
        code = Math.max(0, Math.min(4095, code));
        const buf = Buffer.alloc(2);
        buf[0] = (code >> 8) & 0x0F;
        buf[1] = code & 0xFF;
        this._transport.write(buf);
    }
}

const MCP4725FullBase = _MCP47xxBase(MCP4725Minimal);

class MCP4725Full extends MCP4725FullBase {
    constructor(transport) {
        super(transport);
    }

    set_voltage_eeprom(fraction) {
        const code = Math.round(Math.max(0, Math.min(1, fraction)) * 4095);
        this.set_raw_eeprom(code);
    }

    set_raw_eeprom(code) {
        code = Math.max(0, Math.min(4095, code));
        const buf = Buffer.alloc(3);
        buf[0] = _WRITE_DAC_EEPROM | ((code >> 8) & 0x0F);
        buf[1] = code & 0xFF;
        buf[2] = 0x00;
        this._transport.write(buf);
    }

    read() {
        const raw = this._transport.writeRead(Buffer.from([0x00]), 5);
        return {
            code: ((raw[1] << 8) | raw[2]) >> 4,
            voltage_fraction: (((raw[1] << 8) | raw[2]) >> 4) / 4095.0,
            power_down: (raw[0] >> 2) & 0x03,
            eeprom_code: ((raw[3] & 0x0F) << 8) | raw[4],
            eeprom_power_down: (raw[3] >> 4) & 0x03,
            eeprom_ready: (raw[0] & 0x80) !== 0,
            por: (raw[0] & 0x40) !== 0,
        };
    }

    set_power_down(mode) {
        const raw = this._transport.writeRead(Buffer.from([0x00]), 1);
        const pdBits = (mode & 0x03) << 4;
        const buf = Buffer.from([ raw[0] & 0x0F | pdBits, 0x00 ]);
        this._transport.write(buf);
    }
}

module.exports = { MCP4725Minimal, MCP4725Full };