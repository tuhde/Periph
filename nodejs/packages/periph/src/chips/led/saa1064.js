'use strict';

const _SEGMENTS = {
    0: 'abcdef', 1: 'bc', 2: 'abdeg', 3: 'abcdg', 4: 'bcfg',
    5: 'acdfg', 6: 'acdefg', 7: 'abc', 8: 'abcdefg', 9: 'abcdfg',
};
const _KEYS = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'dp'];
const _DEFAULT_MAP = { a: 0, b: 1, c: 2, d: 3, e: 4, f: 5, g: 6, dp: 7 };

const _SUB_CONTROL = 0x00;
const _C_DYNAMIC = 0x01;
const _C_UNBLANK_13 = 0x02;
const _C_UNBLANK_24 = 0x04;
const _C_TEST = 0x08;
const _C_CURRENT = 0x70;

/**
 * SAA1064 4-digit LED driver — minimal interface.
 *
 * Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
 * decimal points. I2C address 0x38-0x3B selected by the voltage on the ADR
 * pin. The constructor starts the initialization (control byte 0x47: dynamic
 * mode, all digits unblanked, 12 mA; digits blanked); every bus method waits
 * for it, and `ready` exposes it.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device.
 */
class SAA1064Minimal {
    constructor(connection) {
        this._conn = connection;
        this._control = 0x47;
        this._patterns = [0, 0, 0, 0];
        this._map = Object.assign({}, _DEFAULT_MAP);
        this._glyphs = {};
        this._rebuildGlyphs();
        this._ready = this._conn.write(Buffer.from([_SUB_CONTROL, this._control, 0, 0, 0, 0]));
    }

    /** Wait for the init sequence started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    _rebuildGlyphs() {
        this._glyphs = {};
        for (const value of Object.keys(_SEGMENTS)) {
            let pattern = 0;
            for (const s of _SEGMENTS[value]) pattern |= 1 << this._map[s];
            this._glyphs[value] = pattern;
        }
    }

    _pattern(value) {
        if (value === null || value === undefined) return 0;
        if (!Number.isInteger(value) || value < 0 || value > 9) throw new RangeError('digit must be 0-9 or null');
        return this._glyphs[value];
    }

    async _writeDigits(first) {
        await this._ready;
        await this._conn.write(Buffer.from([first].concat(this._patterns.slice(first - 1))));
    }

    /**
     * Show four digits using the segment mapping.
     * @param {Array<number|null>} digits - Four values, each 0-9 or null (blank).
     * @returns {Promise<void>}
     */
    async showDigits(digits) {
        if (digits.length !== 4) throw new RangeError('digits must have 4 values');
        this._patterns = digits.map((d) => this._pattern(d));
        await this._writeDigits(1);
    }

    /**
     * Show a right-aligned decimal number with leading zeros blanked.
     * @param {number} value - Integer 0-9999.
     * @returns {Promise<void>}
     */
    async showNumber(value) {
        if (!Number.isInteger(value) || value < 0 || value > 9999) throw new RangeError('value must be in 0..9999');
        const digits = [null, null, null, value % 10];
        let v = Math.floor(value / 10);
        for (const i of [2, 1, 0]) {
            if (v > 0) { digits[i] = v % 10; v = Math.floor(v / 10); }
        }
        await this.showDigits(digits);
    }

    /** Blank all four digits. @returns {Promise<void>} */
    async clear() {
        this._patterns = [0, 0, 0, 0];
        await this._writeDigits(1);
    }

    /**
     * Write raw segment bytes starting at a digit.
     * @param {number[]} patterns - 1-4 segment bytes (bit 0 = P1/P9 ... bit 7 = P8/P16).
     * @param {number} [firstDigit=1] - First digit to write, 1-4.
     * @returns {Promise<void>}
     */
    async writeRaw(patterns, firstDigit = 1) {
        patterns = Array.from(patterns);
        if (!Number.isInteger(firstDigit) || firstDigit < 1 || firstDigit > 4) throw new RangeError('firstDigit must be in 1..4');
        if (patterns.length < 1 || firstDigit - 1 + patterns.length > 4) throw new RangeError('patterns must fit within digits 1..4');
        for (const p of patterns) {
            if (!Number.isInteger(p) || p < 0 || p > 255) throw new RangeError('pattern must be 0..255');
        }
        for (let i = 0; i < patterns.length; i++) this._patterns[firstDigit - 1 + i] = patterns[i];
        await this._ready;
        await this._conn.write(Buffer.from([firstDigit].concat(patterns)));
    }
}

/**
 * SAA1064 full interface — mode, blanking, current, test, segment map, status.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device.
 */
class SAA1064Full extends SAA1064Minimal {
    async _writeControl() {
        await this._ready;
        await this._conn.write(Buffer.from([_SUB_CONTROL, this._control]));
    }

    async _setBit(mask, on) {
        if (on) this._control |= mask;
        else this._control &= ~mask & 0x7F;
        await this._writeControl();
    }

    /**
     * Select dynamic (all four digits) or static (digits 1 and 2) mode.
     * @param {boolean} dynamic - true for dynamic mode.
     * @returns {Promise<void>}
     */
    async setMode(dynamic) { await this._setBit(_C_DYNAMIC, dynamic); }

    /**
     * Blank digit pairs.
     * @param {boolean} digits13 - true to blank digits 1 and 3.
     * @param {boolean} digits24 - true to blank digits 2 and 4.
     * @returns {Promise<void>}
     */
    async blank(digits13, digits24) {
        this._control = digits13 ? this._control & ~_C_UNBLANK_13 & 0x7F : this._control | _C_UNBLANK_13;
        this._control = digits24 ? this._control & ~_C_UNBLANK_24 & 0x7F : this._control | _C_UNBLANK_24;
        await this._writeControl();
    }

    /**
     * Set the segment output current.
     * @param {number} ma - 0, 3, 6, 9, 12, 15, 18 or 21 (mA).
     * @returns {Promise<void>}
     */
    async setCurrent(ma) {
        if (![0, 3, 6, 9, 12, 15, 18, 21].includes(ma)) throw new RangeError('ma must be one of 0, 3, 6, 9, 12, 15, 18, 21');
        this._control = (this._control & ~_C_CURRENT & 0x7F) | ((ma / 3) << 4);
        await this._writeControl();
    }

    /**
     * Switch all segment outputs on (segment test).
     * @param {boolean} on - true to start the test.
     * @returns {Promise<void>}
     */
    async segmentTest(on) { await this._setBit(_C_TEST, on); }

    /**
     * Remap the helper's segment bits to the module wiring.
     * @param {{a:number,b:number,c:number,d:number,e:number,f:number,g:number,dp:number}} mapping - bit numbers 0-7.
     */
    setSegmentMap(mapping) {
        for (const k of _KEYS) {
            if (!(k in mapping)) throw new RangeError('mapping needs keys a-g and dp');
            if (!Number.isInteger(mapping[k]) || mapping[k] < 0 || mapping[k] > 7) throw new RangeError('bit numbers must be in 0..7');
        }
        this._map = {};
        for (const k of _KEYS) this._map[k] = mapping[k];
        this._rebuildGlyphs();
    }

    /**
     * Show one digit with an optional decimal point.
     * @param {number} position - Digit 1-4.
     * @param {number|null} value - 0-9 or null (blank).
     * @param {boolean} [dp=false] - true to light the decimal point.
     * @returns {Promise<void>}
     */
    async showDigit(position, value, dp = false) {
        if (!Number.isInteger(position) || position < 1 || position > 4) throw new RangeError('position must be in 1..4');
        let pattern = this._pattern(value);
        if (dp) pattern |= 1 << this._map.dp;
        this._patterns[position - 1] = pattern;
        await this._ready;
        await this._conn.write(Buffer.from([position, pattern]));
    }

    /**
     * Switch a decimal point, keeping the cached segments.
     * @param {number} position - Digit 1-4.
     * @param {boolean} on - true to light the decimal point.
     * @returns {Promise<void>}
     */
    async setDecimalPoint(position, on) {
        if (!Number.isInteger(position) || position < 1 || position > 4) throw new RangeError('position must be in 1..4');
        const bit = 1 << this._map.dp;
        if (on) this._patterns[position - 1] |= bit;
        else this._patterns[position - 1] &= ~bit & 0xFF;
        await this._ready;
        await this._conn.write(Buffer.from([position, this._patterns[position - 1]]));
    }

    /**
     * Read the status byte (bit 7 = PR power-reset flag, cleared by the read).
     * @returns {Promise<number>} Status byte, 0-255.
     */
    async readStatus() {
        await this._ready;
        const data = await this._conn.read(1);
        return data[0];
    }

    /** Read the status byte and return its PR flag. @returns {Promise<boolean>} */
    async powerResetFlag() {
        return ((await this.readStatus()) & 0x80) !== 0;
    }

    /**
     * Write a raw control byte (bit 7 forced to 0).
     * @param {number} value - Control byte 0-127.
     * @returns {Promise<void>}
     */
    async writeControl(value) {
        this._control = value & 0x7F;
        await this._writeControl();
    }
}

SAA1064Minimal.DIGITS = 4;
SAA1064Full.DIGITS = 4;

module.exports = { SAA1064Minimal, SAA1064Full };
