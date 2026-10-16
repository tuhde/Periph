'use strict';

const COLUMNS = 16;
const ROWS = 2;

const SUPPLY_3V3 = 0;
const SUPPLY_5V = 1;

const _CMD_CLEAR = 0x01;
const _CMD_HOME = 0x02;
const _CMD_ENTRY = 0x04;
const _CMD_DISPLAY = 0x08;
const _CMD_SHIFT = 0x10;
const _CMD_FUNCTION = 0x20;
const _CMD_CGRAM = 0x40;
const _CMD_DDRAM = 0x80;
const _CMD_BIAS = 0x14;       // table 1: 0x14 | BS << 3 | FX
const _CMD_ICONADDR = 0x40;   // table 1
const _CMD_POWER = 0x50;      // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
const _CMD_FOLLOWER = 0x60;   // table 1: | Fon << 3 | Rab2..0
const _CMD_CONTRAST = 0x70;   // table 1: | C3..C0
const _CMD_DHPOS = 0x10;      // table 2: | UD << 3

// [function, bias, power, follower, contrast low] per module (1, 2, 3 lines) and supply, data sheet page 7
const _PRESETS = [
    [[0x31, 0x14, 0x55, 0x6D, 0x7C], [0x31, 0x1C, 0x51, 0x6A, 0x74]],   // DOGM081
    [[0x39, 0x14, 0x55, 0x6D, 0x78], [0x39, 0x1C, 0x52, 0x69, 0x74]],   // DOGM162
    [[0x39, 0x15, 0x55, 0x6E, 0x72], [0x39, 0x1D, 0x50, 0x6C, 0x7C]],   // DOGM163
];

/**
 * ST7036 character LCD controller — minimal interface.
 *
 * Drives an EA DOGM162W-A (2 x 16, 3.3 V) over 4-wire serial: SI, SCL, CSB, RS (C/D) and RESET.
 * The HD44780-compatible instruction set has an extension table (selected by Function Set
 * IS[2:1]) for bias, booster, follower and contrast; the DOG presets from the data sheet
 * (page 7) are sent at initialisation.
 *
 * The connection is a DisplayConnection (see specs/transport_display.md) with no BUSY pin: the
 * serial mode is write-only and has no busy flag, so every instruction is followed by a fixed
 * wait (30 us, 2 ms after Clear / Return Home). Every instruction is a single byte sent with RS
 * (D/C#) low; every data byte gets its own CS window, followed by the 30 us gap.
 *
 * The constructor starts the initialisation sequence; every method waits for it, and `ready()`
 * resolves (or rejects) when it is done.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring, no BUSY pin), SPI mode 0 at
 *   about 1 MHz or less (SCL cycle >= 200 ns at <= 4.5 V).
 */
class ST7036Minimal {
    constructor(connection, preset = null) {
        const p = preset || {
            lines: ROWS, columns: COLUMNS, n: 1, bon: 1, contrast: 0x18, rab: 5, quarter: false, fx: 0,
            seq: [0x39, 0x14, 0x55, 0x6D, 0x78],
        };
        this._conn = connection;
        this._lines = p.lines;
        this._columns = p.columns;
        this._n = p.n;              // Function Set N
        this._dh = 0;               // Function Set DH
        this._bon = p.bon;          // booster on
        this._ion = 0;              // ICON display on
        this._contrast = p.contrast;   // C5..C0
        this._fon = 1;              // follower on
        this._rab = p.rab;          // Rab2..0
        this._quarter = p.quarter;  // bias 1/4
        this._fx = p.fx;            // bias FX bit
        this._display = 0x0C;       // Display ON/OFF byte
        this._entry = 0x06;         // Entry Mode byte
        this._addr = 0;             // cached DDRAM address
        this._ready = this._initSequence(...p.seq);
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /** @returns {number} Characters per line. */
    get columns() { return this._columns; }

    /** @returns {number} Number of lines. */
    get rows() { return this._lines; }

    /**
     * Resolves when initialisation has finished; rejects if it failed.
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    // ---- internals -------------------------------------------------------------------------

    /** Send one instruction byte (RS low) and wait `waitUs` microseconds. */
    async _instr(byte, waitUs = 30) {
        await this._conn.sendCommand(byte);
        if (waitUs >= 1000) await this._conn.delayMs(Math.ceil(waitUs / 1000));
        else await this._conn.delayUs(waitUs);
    }

    /** Send one data byte (RS high) in its own CS window, then wait 30 us. */
    async _data(byte) {
        await this._conn.sendData([byte & 0xFF]);
        await this._conn.delayUs(30);
    }

    _rowStart(row) { return this._lines === 3 ? row * 0x10 : row * 0x40; }

    async _setDdram(addr) {
        this._addr = addr & 0x7F;
        await this._instr(_CMD_DDRAM | this._addr);
    }

    /** Function Set byte for the cached N / DH and instruction table `table` (0..2). */
    _function(table) {
        return _CMD_FUNCTION | 0x10 | (this._n << 3) | (this._dh << 2) | (table & 3);
    }

    /** Power/ICON/Contrast high byte from the cached Ion, Bon and contrast. */
    _powerByte() {
        return _CMD_POWER | (this._ion << 3) | (this._bon << 2) | ((this._contrast >> 4) & 3);
    }

    /** Hardware reset, power-up wait and the instruction sequence of a preset. */
    async _initSequence(func, bias, power, follower, contrast) {
        await this._conn.reset();
        await this._conn.delayMs(40);
        await this._instr(func);
        await this._instr(bias);
        await this._instr(contrast);
        await this._instr(power);
        await this._instr(follower);
        await this._conn.delayMs(200);           // booster / V0 settle
        await this._instr(func & ~0x01);         // back to instruction table 0
        await this._instr(this._display);
        await this._instr(_CMD_CLEAR, 2000);
        await this._instr(this._entry);
        this._addr = 0;
    }

    // ---- public API ------------------------------------------------------------------------

    /** Clear the display (Clear Display, 2 ms); the cursor goes to the top left. @returns {Promise<void>} */
    async clear() {
        await this._ready;
        await this._instr(_CMD_CLEAR, 2000);
        this._addr = 0;
    }

    /** Return the cursor (and a shifted display) to the home position (Return Home, 2 ms). @returns {Promise<void>} */
    async home() {
        await this._ready;
        await this._instr(_CMD_HOME, 2000);
        this._addr = 0;
    }

    /**
     * Move the cursor to (col, row). Out-of-range positions are ignored.
     * @param {number} col - Column, 0 .. columns - 1.
     * @param {number} row - Row, 0 .. rows - 1.
     * @returns {Promise<void>}
     */
    async setCursor(col, row) {
        await this._ready;
        if (col < 0 || row < 0 || col >= this._columns || row >= this._lines) return;
        await this._setDdram(this._rowStart(row) + col);
    }

    /**
     * Write text at the cursor, one data byte per transfer with a 30 us gap. ASCII 0x20 .. 0x7D is
     * written as is; any other character becomes '?'. The cursor does not wrap to the next line.
     * @param {string} text - Text to write.
     * @returns {Promise<void>}
     */
    async write(text) {
        await this._ready;
        if (text === null || text === undefined) return;
        for (const ch of String(text)) {
            const c = ch.codePointAt(0);
            await this._writeCode(c >= 0x20 && c <= 0x7D ? c : 0x3F);
        }
    }

    /**
     * Write one raw character code (including the CGRAM codes 0..7) at the cursor.
     * @param {number} code - Character code 0..255.
     * @returns {Promise<void>}
     */
    async writeChar(code) {
        await this._ready;
        await this._writeCode(code);
    }

    async _writeCode(code) {
        await this._data(code);
        this._addr = (this._addr + 1) & 0x7F;
    }

    /**
     * Move the cursor to (col, row) and write text.
     * @param {number} col - Column.
     * @param {number} row - Row.
     * @param {string} text - Text to write.
     * @returns {Promise<void>}
     */
    async printAt(col, row, text) {
        await this.setCursor(col, row);
        await this.write(text);
    }
}

/**
 * ST7036 character LCD controller — full interface.
 *
 * Extends ST7036Minimal with the DOGM081 / DOGM162 / DOGM163 modules at 3.3 V or 5 V, display
 * control (on/off, cursor, blink, entry mode, shift), contrast, booster, follower and bias,
 * custom characters (CGRAM), double height, ICON RAM and raw instructions.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring, no BUSY pin).
 * @param {number} [lines=2] - 1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16).
 * @param {number|null} [columns=null] - Characters per line, or null for the module default (8 or 16).
 * @param {number} [supply=SUPPLY_3V3] - SUPPLY_3V3 (booster on) or SUPPLY_5V (booster off).
 * @param {number|null} [contrast=null] - Contrast C5..C0 0..63, or null for the data sheet preset.
 */
class ST7036Full extends ST7036Minimal {
    constructor(connection, lines = 2, columns = null, supply = SUPPLY_3V3, contrast = null) {
        if (![1, 2, 3].includes(lines)) throw new RangeError('lines must be 1, 2 or 3');
        let [func, bias, power, follower, low] = _PRESETS[lines - 1][supply];
        let c = ((power & 3) << 4) | (low & 0x0F);
        if (contrast !== null && contrast !== undefined) {
            c = contrast & 0x3F;
            power = (power & ~3) | ((c >> 4) & 3);
            low = _CMD_CONTRAST | (c & 0x0F);
        }
        super(connection, {
            lines, columns: columns !== null && columns !== undefined ? columns : (lines === 1 ? 8 : 16),
            n: (func >> 3) & 1, bon: (power >> 2) & 1, contrast: c, rab: follower & 7,
            quarter: ((bias >> 3) & 1) === 1, fx: bias & 1,
            seq: [func, bias, power, follower, low],
        });
    }

    // ---- display control -------------------------------------------------------------------

    async _setDisplayBit(mask, on) {
        this._display = on ? (this._display | mask) : (this._display & ~mask);
        this._display = (this._display & 0x07) | _CMD_DISPLAY;
        await this._instr(this._display);
    }

    /** Turn the display on or off (the DDRAM content is kept). @param {boolean} on @returns {Promise<void>} */
    async display(on) { await this._ready; await this._setDisplayBit(0x04, on); }

    /**
     * Show or hide the cursor and its blinking block.
     * @param {boolean} on - Underline cursor.
     * @param {boolean} [blink=false] - Blinking character block.
     * @returns {Promise<void>}
     */
    async cursor(on, blink = false) {
        await this._ready;
        await this._setDisplayBit(0x02, on);
        await this._setDisplayBit(0x01, blink);
    }

    /**
     * Set the entry mode: cursor direction and display shift on write.
     * @param {boolean} increment - true = cursor moves right (address + 1), false = left.
     * @param {boolean} [shift=false] - true = shift the display on every write.
     * @returns {Promise<void>}
     */
    async setEntryMode(increment, shift = false) {
        await this._ready;
        this._entry = _CMD_ENTRY | (increment ? 2 : 0) | (shift ? 1 : 0);
        await this._instr(this._entry);
    }

    /** Shift the cursor one position. @param {boolean} right @returns {Promise<void>} */
    async shiftCursor(right) { await this._ready; await this._instr(_CMD_SHIFT | (right ? 0x04 : 0)); }

    /** Shift the whole display one position (the cursor follows). @param {boolean} right @returns {Promise<void>} */
    async shiftDisplay(right) { await this._ready; await this._instr(_CMD_SHIFT | 0x08 | (right ? 0x04 : 0)); }

    /** Return the cursor and a shifted display to the home position (2 ms). @returns {Promise<void>} */
    async returnHome() { await this.home(); }

    /** Set a raw DDRAM address (for use with display shift). @param {number} address 0..0x7F @returns {Promise<void>} */
    async setDdramAddress(address) { await this._ready; await this._setDdram(address); }

    // ---- contrast and supply ---------------------------------------------------------------

    async _extBegin() { await this._instr(this._function(1)); }
    async _extEnd() { await this._instr(this._function(0)); }

    /** Set the contrast C5..C0. @param {number} value - 0..63. @returns {Promise<void>} */
    async setContrast(value) {
        await this._ready;
        this._contrast = value & 0x3F;
        await this._extBegin();
        await this._instr(this._powerByte());
        await this._instr(_CMD_CONTRAST | (this._contrast & 0x0F));
        await this._extEnd();
    }

    /** Switch the voltage booster on or off (needed at 3.3 V). @param {boolean} on @returns {Promise<void>} */
    async setBooster(on) {
        await this._ready;
        this._bon = on ? 1 : 0;
        await this._extBegin();
        await this._instr(this._powerByte());
        await this._extEnd();
    }

    /**
     * Set the voltage follower and the V0 amplification.
     * @param {boolean} on - Follower on.
     * @param {number} ratio - Rab2..Rab0 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75.
     * @returns {Promise<void>}
     */
    async setFollower(on, ratio) {
        await this._ready;
        this._fon = on ? 1 : 0;
        this._rab = ratio & 7;
        await this._extBegin();
        await this._instr(_CMD_FOLLOWER | (this._fon << 3) | this._rab);
        await this._extEnd();
    }

    /**
     * Set the LCD bias: 1/5 (default) or 1/4. FX follows the line count.
     * @param {boolean} quarter - true = 1/4 bias, false = 1/5 bias.
     * @returns {Promise<void>}
     */
    async setBias(quarter) {
        await this._ready;
        this._quarter = !!quarter;
        await this._extBegin();
        await this._instr(_CMD_BIAS | (quarter ? 8 : 0) | this._fx);
        await this._extEnd();
    }

    // ---- custom characters and special modes -----------------------------------------------

    /**
     * Define a user character (CGRAM) and restore the DDRAM address.
     * @param {number} index - Character code 0..7.
     * @param {number[]} rows - 8 row patterns, 5 bits each (bit 4 = leftmost pixel).
     * @returns {Promise<void>}
     */
    async createChar(index, rows) {
        await this._ready;
        const saved = this._addr;
        await this._instr(_CMD_CGRAM | ((index & 7) << 3));
        for (let i = 0; i < 8; i++) await this._data(rows[i] & 0x1F);
        await this._setDdram(saved);
    }

    /**
     * Double-height font (1-line module: 5 x 16 font; 3-line module: top or bottom pair of lines).
     * Ignored on the 2-line module (the combination is forbidden by the data sheet).
     * @param {boolean} on - Double height on.
     * @param {boolean} [upper=true] - 3-line module only: true = COM1..16 (UD = 1), false = COM9..24.
     * @returns {Promise<void>}
     */
    async setDoubleHeight(on, upper = true) {
        await this._ready;
        if (this._lines === 2) return;
        this._dh = on ? 1 : 0;
        await this._instr(this._function(this._lines === 1 ? 0 : 2));
        if (this._lines === 3) await this._instr(_CMD_DHPOS | (upper ? 8 : 0));
        await this._instr(this._function(0));
    }

    /** Turn the ICON display on or off (no icons are wired on the DOG modules). @param {boolean} on @returns {Promise<void>} */
    async icon(on) {
        await this._ready;
        this._ion = on ? 1 : 0;
        await this._extBegin();
        await this._instr(this._powerByte());
        await this._extEnd();
    }

    /**
     * Write one ICON RAM byte.
     * @param {number} address - ICON RAM address 0..15.
     * @param {number} value - Byte to store.
     * @returns {Promise<void>}
     */
    async writeIcon(address, value) {
        await this._ready;
        const saved = this._addr;
        await this._extBegin();
        await this._instr(_CMD_ICONADDR | (address & 0x0F));
        await this._data(value);
        await this._extEnd();
        await this._setDdram(saved);
    }

    /** Send any instruction byte (RS low) and wait 30 us. @param {number} instruction @returns {Promise<void>} */
    async sendCommand(instruction) { await this._ready; await this._instr(instruction & 0xFF); }

    /** Send any data byte (RS high) and wait 30 us. @param {number} value @returns {Promise<void>} */
    async sendData(value) { await this.writeChar(value); }
}

module.exports = { ST7036Minimal, ST7036Full, COLUMNS, ROWS, SUPPLY_3V3, SUPPLY_5V };
