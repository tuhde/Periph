'use strict';

const _ADDR_SA0_LOW  = 0x38;
const _ADDR_SA0_HIGH = 0x39;

const _CMD_MODE_SET      = 0x40;
const _CMD_LOAD_PTR      = 0x00;
const _CMD_DEVICE_SELECT = 0x60;
const _CMD_BANK_SELECT   = 0x78;
const _CMD_BLINK_SELECT  = 0x70;

const _MODE_1_4    = 0x00;
const _MODE_STATIC = 0x01;
const _MODE_1_2    = 0x02;
const _MODE_1_3    = 0x03;

const _BIAS_1_3 = 0x00;
const _BIAS_1_2 = 0x04;

const _DISPLAY_OFF = 0x00;
const _DISPLAY_ON  = 0x08;

const _SEVEN_SEG = [
    0xED, 0x60, 0xA7, 0xE3, 0x6A,
    0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
];

/**
 * PCF8576 40x4 universal LCD segment driver — minimal interface.
 *
 * Drives a single 7-segment LCD display (static or 1:4 multiplex) out of
 * the box. The chip is write-only — the host never reads back. I2C address
 * is 0x38 (SA0 = VSS) or 0x39 (SA0 = VDD).
 *
 * Default: 1:4 multiplex drive mode, 1/3 bias, display enabled, and a
 * 7-segment digit lookup table for the default multiplex mode.
 *
 * @param {object} transport - Configured I2C transport pointing at the device.
 */
class PCF8576Minimal {
    constructor(transport) {
        this._transport = transport;
        this._backplanes = 4;
        this._clear();
    }

    _cmdMode(enable, bias, mode) {
        return _CMD_MODE_SET | (enable ? _DISPLAY_ON : _DISPLAY_OFF) | bias | mode;
    }

    _sendCommands(cmds) {
        const out = [];
        for (let i = 0; i < cmds.length - 1; i++) {
            out.push(0x80 | (cmds[i] & 0x7F));
        }
        out.push(cmds[cmds.length - 1] & 0x7F);
        this._transport.write(Buffer.from(out));
    }

    _sendCommandsWithData(cmd, data) {
        const out = [cmd & 0x7F, ...data];
        this._transport.write(Buffer.from(out));
    }

    _clear() {
        this._sendCommands([this._cmdMode(true, _BIAS_1_3, _MODE_1_4)]);
        const zeros = new Array(20).fill(0);
        this._sendCommandsWithData(_CMD_LOAD_PTR, zeros);
    }

    /**
     * Zero all 40 columns of display RAM; all segments off.
     */
    clear() {
        this._clear();
    }

    /**
     * Set the data pointer to {@param address} and write raw data bytes.
     *
     * @param {number} address - RAM column address, 0-39.
     * @param {Buffer|Uint8Array|number[]} data - Bytes to write to display RAM.
     */
    writeRaw(address, data) {
        if (address < 0 || address > 39) {
            throw new RangeError('address must be in 0..39');
        }
        if (!data || data.length === 0) return;
        this._sendCommandsWithData(_CMD_LOAD_PTR | (address & 0x3F), Array.from(data));
    }

    /**
     * Write one 7-segment byte at column {@param position} * 2.
     *
     * @param {number} position - Digit index, 0-19. Maps to RAM address position * 2.
     * @param {number} segments - 7-segment byte (a/c/b/DP/f/e/g/d packed, MSB-first).
     *                           Add 0x10 to set the decimal point.
     */
    setDigit7seg(position, segments) {
        if (position < 0 || position > 19) {
            throw new RangeError('position must be in 0..19');
        }
        this.writeRaw(position * 2, [segments & 0xFF]);
    }
}

/**
 * PCF8576 full interface — extends PCF8576Minimal with drive mode, bias, and blink control.
 *
 * Adds the ability to switch drive modes (static, 1:2, 1:3, 1:4 multiplex),
 * change bias (1:2 or 1/3), configure blinking, select RAM banks for
 * static and 1:2 multiplex use, and change the device subaddress counter
 * for cascaded displays.
 *
 * @param {object} transport - Configured I2C transport pointing at the device.
 */
class PCF8576Full extends PCF8576Minimal {
    static BLINK_OFF     = 0;
    static BLINK_2_HZ    = 1;
    static BLINK_1_HZ    = 2;
    static BLINK_0_5_HZ  = 3;

    static BIAS_1_3 = 0;
    static BIAS_1_2 = 1;

    static BACKPLANES_1 = 1;
    static BACKPLANES_2 = 2;
    static BACKPLANES_3 = 3;
    static BACKPLANES_4 = 4;

    static BANK_0 = 0;
    static BANK_1 = 1;

    constructor(transport) {
        super(transport);
        this._enabled = true;
        this._bias = PCF8576Full.BIAS_1_3;
    }

    _modeCode(backplanes) {
        switch (backplanes) {
            case PCF8576Full.BACKPLANES_1: return _MODE_STATIC;
            case PCF8576Full.BACKPLANES_2: return _MODE_1_2;
            case PCF8576Full.BACKPLANES_3: return _MODE_1_3;
            case PCF8576Full.BACKPLANES_4: return _MODE_1_4;
            default:                       return _MODE_1_4;
        }
    }

    _applyMode() {
        const biasBits = (this._bias === PCF8576Full.BIAS_1_2) ? _BIAS_1_2 : _BIAS_1_3;
        this._sendCommands([this._cmdMode(this._enabled, biasBits, this._modeCode(this._backplanes))]);
    }

    /**
     * Turn the display on (E = 1). RAM contents are preserved.
     */
    enable() {
        this._enabled = true;
        this._applyMode();
    }

    /**
     * Blank the display output (E = 0). RAM contents are preserved.
     */
    disable() {
        this._enabled = false;
        this._applyMode();
    }

    /**
     * Reconfigure drive mode and bias at runtime.
     *
     * @param {number} backplanes - Number of backplanes: 1 (static), 2 (1:2), 3 (1:3), 4 (1:4).
     * @param {number} [bias=0] - 0 = 1/3 bias, 1 = 1/2 bias.
     */
    setMode(backplanes, bias = 0) {
        this._backplanes = backplanes;
        this._bias = bias;
        this._applyMode();
    }

    /**
     * Set the blink frequency.
     *
     * @param {number} frequency - 0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz.
     * @param {boolean} [alternateBank=false] - Alternate-RAM-bank blinking (static/1:2 only).
     */
    setBlink(frequency, alternateBank = false) {
        if (frequency < 0 || frequency > 3) {
            throw new RangeError('frequency must be in 0..3');
        }
        const ab = alternateBank ? 0x04 : 0x00;
        this._sendCommands([_CMD_BLINK_SELECT | ab | (frequency & 0x03)]);
    }

    /**
     * Select the active RAM bank.
     *
     * @param {number} inputBank - 0 (rows 0-1) or 1 (rows 2-3).
     * @param {number} outputBank - 0 (rows 0-1) or 1 (rows 2-3).
     */
    setBank(inputBank, outputBank) {
        this._sendCommands([_CMD_BANK_SELECT | ((inputBank & 1) << 1) | (outputBank & 1)]);
    }

    /**
     * Change the subaddress counter for cascaded displays.
     *
     * @param {number} subaddress - 0-7; must match the A0/A1/A2 pin state.
     */
    deviceSelect(subaddress) {
        if (subaddress < 0 || subaddress > 7) {
            throw new RangeError('subaddress must be in 0..7');
        }
        this._sendCommands([_CMD_DEVICE_SELECT | (subaddress & 0x07)]);
    }
}

PCF8576Minimal.SEVEN_SEG = _SEVEN_SEG;
PCF8576Full.SEVEN_SEG = _SEVEN_SEG;

module.exports = { PCF8576Minimal, PCF8576Full };
