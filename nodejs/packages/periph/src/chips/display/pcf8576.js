'use strict';

const _CMD_MODE_SET = 0x80;
const _CMD_LOAD_DP  = 0x20;
const _CMD_DEV_SEL  = 0x40;
const _CMD_BANK     = 0x60;
const _CMD_BLINK    = 0x70;

const _SEG_7SEG = [
    0xED, 0x60, 0xA7, 0xE3, 0x6A, 0xCB, 0xCF, 0xE0, 0xEF, 0xEB, 0x00
];

class PCF8576Minimal {
    constructor(transport, address = 0x38) {
        this._transport = transport;
        this._address = address;
        this._subaddress = 0;
        this._writeCmd(Buffer.from([_CMD_DEV_SEL | 0x01 | (this._subaddress << 1)]));
        this._writeCmd(Buffer.from([0x88]));
        this._writeCmd(Buffer.from([_CMD_LOAD_DP | 0x00]));
        this._transport.write(Buffer.alloc(40));
    }

    _writeCmd(data) {
        this._transport.write(data);
    }

    clear() {
        const cmd = Buffer.from([_CMD_LOAD_DP | 0x00, 0x00]);
        this._transport.write(cmd);
        this._transport.write(Buffer.alloc(40));
    }

    writeRaw(address, data) {
        if (address < 0 || (address + data.length) > 40) return;
        const cmd = Buffer.from([_CMD_LOAD_DP | address]);
        this._transport.write(cmd);
        this._transport.write(data);
    }

    setDigit7Seg(position, segments) {
        if (position < 0 || position > 19) return;
        const addr = position * 2;
        const cmd = Buffer.from([_CMD_LOAD_DP | addr]);
        this._transport.write(cmd);
        this._transport.write(Buffer.from([segments]));
    }
}

class PCF8576Full extends PCF8576Minimal {
    static MUX_STATIC = 0;
    static MUX_1_2    = 1;
    static MUX_1_3    = 3;
    static MUX_1_4    = 2;

    static BLINK_OFF  = 0;
    static BLINK_2HZ  = 1;
    static BLINK_1HZ  = 2;
    static BLINK_05HZ = 3;

    static SEG_7SEG = _SEG_7SEG;

    constructor(transport, address = 0x38) {
        super(transport, address);
    }

    _buildModeSet(backplanes, bias, enable) {
        const e = enable ? 0x08 : 0x00;
        const b = bias ? 0x04 : 0x00;
        const m = (backplanes === 4) ? 0 :
                  (backplanes === 1) ? 1 :
                  (backplanes === 2) ? 2 : 3;
        return 0x80 | e | b | m;
    }

    enable() {
        this._writeCmd(Buffer.from([this._buildModeSet(4, 0, true)]));
    }

    disable() {
        this._writeCmd(Buffer.from([this._buildModeSet(4, 0, false)]));
    }

    setMode(backplanes, bias = 0) {
        this._writeCmd(Buffer.from([this._buildModeSet(backplanes, bias, true)]));
    }

    setBlink(frequency, alternateBank = false) {
        const ab = alternateBank ? 0x04 : 0x00;
        this._writeCmd(Buffer.from([_CMD_BLINK | ab | (frequency & 0x03)]));
    }

    setBank(inputBank, outputBank) {
        const i = inputBank ? 0x02 : 0x00;
        const o = outputBank ? 0x01 : 0x00;
        this._writeCmd(Buffer.from([_CMD_BANK | i | o]));
    }

    deviceSelect(subaddress) {
        if (subaddress < 0 || subaddress > 7) return;
        this._subaddress = subaddress;
        this._writeCmd(Buffer.from([_CMD_DEV_SEL | 0x01 | (subaddress << 1)]));
    }
}

module.exports = { PCF8576Minimal, PCF8576Full };