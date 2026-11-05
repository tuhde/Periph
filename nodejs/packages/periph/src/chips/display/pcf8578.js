'use strict';

const _ADDR_SA0_LOW  = 0x3C;
const _ADDR_SA0_HIGH = 0x3D;

const _CMD_SET_MODE       = 0x40;
const _CMD_DEVICE_SELECT  = 0x60;
const _CMD_RAM_ACCESS     = 0x70;
const _CMD_SET_START_BANK = 0x7C;
const _CONT               = 0x80;

const STATUS_BLANK   = 0;
const STATUS_NORMAL  = 1;
const STATUS_ALL_ON  = 2;
const STATUS_INVERSE = 3;

const ACCESS_CHARACTER    = 0;
const ACCESS_HALF_GRAPHIC = 1;
const ACCESS_FULL_GRAPHIC = 2;

const RAM_COLUMNS = 40;
const RAM_BANKS   = 4;

// multiplex rate (rows) -> M[1:0]
const _MUX_CODE = { 8: 1, 16: 2, 24: 3, 32: 0 };
const _PER_COL  = [1, 2, 4];

/**
 * PCF8578 row/column LCD dot-matrix driver — minimal interface.
 *
 * Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both rows and columns) out
 * of the box. With `rows` rows the panel is `40 - rows` columns wide (8x32, 16x24, 24x16 or
 * 32x8). The chip has no register map; this driver streams command bytes and display data with
 * the plain connection `write` / `writeRead` calls. The I2C address is 0x3C (SA0 = VSS) or 0x3D
 * (SA0 = VDD); the chip supports 100 kHz at most.
 *
 * The constructor starts the initialisation (waits for the internal reset, clears the whole
 * display RAM, then switches the display on in normal video with start bank 0); every method
 * waits for it and `ready()` resolves (or rejects) when it is done. A shadow copy of the 4 x 40
 * byte RAM is kept so single-pixel updates need only one byte on the bus. One RAM byte is
 * 8 vertical pixels of one column; bit 0 is the top row of the bank.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C
 *   connection pointing at the device (100 kHz maximum).
 * @param {number} [rows=16] - Number of display rows: 8, 16, 24 or 32.
 */
class PCF8578Minimal {
    constructor(connection, rows = 16) {
        if (!(rows in _MUX_CODE)) {
            throw new RangeError('rows must be 8, 16, 24 or 32');
        }
        this._conn = connection;
        this._rows = rows;
        this._mixed = true;
        this._status = STATUS_NORMAL;
        this._startBank = 0;
        this._shadow = [];
        for (let b = 0; b < RAM_BANKS; b++) this._shadow.push(new Uint8Array(RAM_COLUMNS));
        this._ready = this._init();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /**
     * Resolves when the initialisation sequence is finished (rejects if it failed).
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    _modeByte(status, cont) {
        return (cont ? _CONT : 0) | _CMD_SET_MODE | ((this._mixed ? 1 : 0) << 4)
            | (status << 2) | _MUX_CODE[this._rows];
    }

    _ramAccessByte(access, bank, cont = true) {
        return (cont ? _CONT : 0) | _CMD_RAM_ACCESS | (access << 2) | bank;
    }

    _zeroShadow() {
        for (const bank of this._shadow) bank.fill(0);
    }

    async _init() {
        await new Promise((resolve) => setTimeout(resolve, 2)); // >= 1 ms internal power-on reset
        this._mixed = true;
        this._startBank = 0;
        const out = [
            _CONT | _CMD_DEVICE_SELECT,                              // device-select 0
            this._modeByte(STATUS_BLANK, true),                      // mixed, blank
            _CONT | _CMD_SET_START_BANK,                             // start bank 0
            this._ramAccessByte(ACCESS_FULL_GRAPHIC, 0),             // full-graphic, bank 0
            0x00,                                                    // load-X 0 (C = 0)
            ...new Array(RAM_COLUMNS * RAM_BANKS).fill(0),
        ];
        await this._conn.write(Buffer.from(out));
        this._zeroShadow();
        this._status = STATUS_NORMAL;
        await this._conn.write(Buffer.from([this._modeByte(STATUS_NORMAL, false)]));
    }

    _requireMixed() {
        if (!this._mixed) {
            throw new Error('display RAM is only accessible in mixed mode');
        }
    }

    // Write data at RAM column x / bank and mirror it in the shadow.
    async _ramWrite(x, bank, data, access = ACCESS_CHARACTER) {
        this._requireMixed();
        const out = [
            _CONT | _CMD_DEVICE_SELECT,
            this._ramAccessByte(access, bank),
            x & 0x3F,
            ...data,
        ];
        await this._conn.write(Buffer.from(out));
        const perCol = _PER_COL[access];
        for (let i = 0; i < data.length; i++) {
            this._shadow[bank + (i % perCol)][x + Math.floor(i / perCol)] = data[i];
        }
    }

    /**
     * Zero the whole display RAM (4 banks x 40 columns) and the shadow.
     * @returns {Promise<void>}
     */
    async clear() {
        await this._ready;
        this._requireMixed();
        const out = [
            _CONT | _CMD_DEVICE_SELECT,
            this._ramAccessByte(ACCESS_FULL_GRAPHIC, 0),
            0x00,
            ...new Array(RAM_COLUMNS * RAM_BANKS).fill(0),
        ];
        await this._conn.write(Buffer.from(out));
        this._zeroShadow();
    }

    /**
     * Set or clear one pixel. Out-of-range coordinates are ignored. Writes exactly one RAM byte.
     *
     * @param {number} x - Display column, 0 .. (40 - rows - 1).
     * @param {number} y - Display row, 0 .. rows - 1.
     * @param {boolean} [on=true] - true lights the pixel, false clears it.
     * @returns {Promise<void>}
     */
    async setPixel(x, y, on = true) {
        await this._ready;
        this._requireMixed();
        if (x < 0 || x >= RAM_COLUMNS - this._rows || y < 0 || y >= this._rows) return;
        const ramX = this._rows + x;
        const bank = y >> 3;
        const bit = 1 << (y & 7);
        const value = on ? (this._shadow[bank][ramX] | bit) : (this._shadow[bank][ramX] & ~bit & 0xFF);
        await this._ramWrite(ramX, bank, [value]);
    }

    /**
     * Write consecutive column bytes in character access mode.
     *
     * @param {number} x - First display column, 0 .. (40 - rows - 1).
     * @param {number} bank - Display bank (8-row band), 0 .. rows/8 - 1.
     * @param {number[]|Buffer} data - Column bytes; bit 0 is the top row of the bank. The write
     *   is truncated at the last display column.
     * @returns {Promise<void>}
     */
    async writeColumns(x, bank, data) {
        await this._ready;
        if (bank < 0 || bank >= (this._rows >> 3)) {
            throw new RangeError('bank out of range for the configured rows');
        }
        const columns = RAM_COLUMNS - this._rows;
        if (x < 0 || x >= columns) return;
        const bytes = Array.from(data).slice(0, columns - x);
        if (bytes.length === 0) return;
        await this._ramWrite(this._rows + x, bank, bytes);
    }
}

/**
 * PCF8578 full interface — extends PCF8578Minimal.
 *
 * Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing, display status
 * (blank / normal / all-on / inverse), multiplex rate and row mode, hardware vertical scrolling
 * via the start bank, raw RAM write/read in all three access modes, and the device-select
 * command for PCF8579 cascades.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C
 *   connection pointing at the device (100 kHz maximum).
 * @param {number} [rows=16] - Number of display rows: 8, 16, 24 or 32.
 */
class PCF8578Full extends PCF8578Minimal {
    _plot(x, y, on, dirty) {
        if (x < 0 || x >= RAM_COLUMNS - this._rows || y < 0 || y >= this._rows) return;
        const ramX = this._rows + x;
        const bank = y >> 3;
        const bit = 1 << (y & 7);
        this._shadow[bank][ramX] = on ? (this._shadow[bank][ramX] | bit) : (this._shadow[bank][ramX] & ~bit & 0xFF);
        dirty.add(bank * RAM_COLUMNS + ramX);
    }

    // One transaction per run of consecutive changed columns in a bank.
    async _flush(dirty) {
        const items = Array.from(dirty).sort((a, b) => a - b);
        let i = 0;
        while (i < items.length) {
            const bank = Math.floor(items[i] / RAM_COLUMNS);
            const start = items[i] % RAM_COLUMNS;
            let end = start;
            while (i + 1 < items.length && items[i + 1] === items[i] + 1
                   && Math.floor(items[i + 1] / RAM_COLUMNS) === bank) {
                i++;
                end++;
            }
            await this._ramWrite(start, bank, Array.from(this._shadow[bank].slice(start, end + 1)));
            i++;
        }
    }

    /**
     * Return the state of one pixel from the shadow (no bus traffic).
     *
     * @param {number} x - Display column.
     * @param {number} y - Display row.
     * @returns {Promise<boolean>} true if the pixel is set; false if clear or out of range.
     */
    async getPixel(x, y) {
        await this._ready;
        if (x < 0 || x >= RAM_COLUMNS - this._rows || y < 0 || y >= this._rows) return false;
        return (this._shadow[y >> 3][this._rows + x] & (1 << (y & 7))) !== 0;
    }

    /**
     * Set every displayed pixel on or off.
     * @param {boolean} [on=true] - true lights all pixels, false clears them.
     * @returns {Promise<void>}
     */
    async fill(on = true) {
        await this._ready;
        const columns = RAM_COLUMNS - this._rows;
        for (let bank = 0; bank < (this._rows >> 3); bank++) {
            await this._ramWrite(this._rows, bank, new Array(columns).fill(on ? 0xFF : 0x00));
        }
    }

    /**
     * Draw a Bresenham line, written as one batch of changed bytes.
     *
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {boolean} [on=true] - true draws, false erases.
     * @returns {Promise<void>}
     */
    async drawLine(x0, y0, x1, y1, on = true) {
        await this._ready;
        this._requireMixed();
        const dirty = new Set();
        const dx = Math.abs(x1 - x0);
        const dy = -Math.abs(y1 - y0);
        const sx = x0 < x1 ? 1 : -1;
        const sy = y0 < y1 ? 1 : -1;
        let err = dx + dy;
        for (;;) {
            this._plot(x0, y0, on, dirty);
            if (x0 === x1 && y0 === y1) break;
            const e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
        await this._flush(dirty);
    }

    /**
     * Draw a rectangle outline or a filled rectangle.
     *
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels.
     * @param {boolean} [on=true] - true draws, false erases.
     * @param {boolean} [fill=false] - true fills the interior.
     * @returns {Promise<void>}
     */
    async drawRect(x, y, w, h, on = true, fill = false) {
        await this._ready;
        this._requireMixed();
        if (w <= 0 || h <= 0) return;
        const dirty = new Set();
        for (let yy = y; yy < y + h; yy++) {
            for (let xx = x; xx < x + w; xx++) {
                if (fill || yy === y || yy === y + h - 1 || xx === x || xx === x + w - 1) {
                    this._plot(xx, yy, on, dirty);
                }
            }
        }
        await this._flush(dirty);
    }

    /**
     * Blit a column-major bitmap aligned to a bank boundary.
     *
     * @param {number} x - Left display column.
     * @param {number} y - Top display row; must be a multiple of 8.
     * @param {Array<number[]|Buffer>|Buffer} bands - One array per 8-pixel-high band from the
     *   top, each holding one byte per column (bit 0 = top row). A single Buffer is treated as one
     *   band. Content outside the display is clipped.
     * @returns {Promise<void>}
     */
    async showBitmap(x, y, bands) {
        if (y % 8 !== 0 || y < 0) {
            throw new RangeError('y must be a non-negative multiple of 8');
        }
        const list = Buffer.isBuffer(bands) ? [bands] : bands;
        for (let i = 0; i < list.length; i++) {
            const bank = (y >> 3) + i;
            if (bank >= (this._rows >> 3)) break;
            await this.writeColumns(x, bank, list[i]);
        }
    }

    /**
     * Set the display status; RAM contents are preserved.
     * @param {number} status - PCF8578Full.DISPLAY_BLANK / _NORMAL / _ALL_ON / _INVERSE.
     * @returns {Promise<void>}
     */
    async setDisplay(status) {
        await this._ready;
        if (status < 0 || status > 3) throw new RangeError('status must be in 0..3');
        this._status = status;
        await this._conn.write(Buffer.from([this._modeByte(status, false)]));
    }

    /**
     * Change the multiplex rate and the row/mixed mode. In row mode (`mixed = false`) the RAM
     * cannot be accessed; pixel and RAM calls throw until mixed mode is restored.
     *
     * @param {number} rows - 8, 16, 24 or 32.
     * @param {boolean} [mixed=true] - true for mixed mode, false for row mode.
     * @returns {Promise<void>}
     */
    async setMode(rows, mixed = true) {
        await this._ready;
        if (!(rows in _MUX_CODE)) throw new RangeError('rows must be 8, 16, 24 or 32');
        this._rows = rows;
        this._mixed = !!mixed;
        await this._conn.write(Buffer.from([this._modeByte(this._status, false)]));
    }

    /**
     * Select the RAM bank shown at the top of the display (vertical scroll).
     * @param {number} bank - 0 .. 3.
     * @returns {Promise<void>}
     */
    async setStartBank(bank) {
        await this._ready;
        if (bank < 0 || bank > 3) throw new RangeError('bank must be in 0..3');
        this._startBank = bank;
        await this._conn.write(Buffer.from([_CMD_SET_START_BANK | bank]));
    }

    /**
     * Add `banks` (may be negative) to the start bank, modulo the banks in use.
     * @param {number} banks - Number of 8-row steps.
     * @returns {Promise<void>}
     */
    async scroll(banks) {
        await this._ready;
        const n = this._rows >> 3;
        await this.setStartBank((((this._startBank + banks) % n) + n) % n);
    }

    _checkRamArgs(x, bank, count, access) {
        if (access < 0 || access > 2) throw new RangeError('access must be in 0..2');
        if (x < 0 || x > 39 || bank < 0 || bank > 3) throw new RangeError('x must be in 0..39 and bank in 0..3');
        if (access === 1 && bank % 2) throw new RangeError('half-graphic access must start at an even bank');
        if (access === 2 && bank !== 0) throw new RangeError('full-graphic access must start at bank 0');
        if (x + Math.ceil(count / _PER_COL[access]) > 40) throw new RangeError('write would pass column 39');
    }

    /**
     * Raw RAM write in character, half-graphic or full-graphic order.
     *
     * @param {number} x - RAM column, 0 .. 39.
     * @param {number} bank - RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic).
     * @param {number[]|Buffer} data - Bytes to write.
     * @param {number} [mode=0] - ACCESS_CHARACTER, ACCESS_HALF_GRAPHIC or ACCESS_FULL_GRAPHIC.
     * @returns {Promise<void>}
     */
    async writeRam(x, bank, data, mode = ACCESS_CHARACTER) {
        await this._ready;
        const bytes = Array.from(data);
        this._checkRamArgs(x, bank, bytes.length, mode);
        if (bytes.length === 0) return;
        await this._ramWrite(x, bank, bytes, mode);
    }

    /**
     * Raw RAM read: address-set commands, repeated start, then `n` bytes.
     *
     * @param {number} x - RAM column, 0 .. 39.
     * @param {number} bank - RAM bank, 0 .. 3.
     * @param {number} n - Number of bytes to read.
     * @param {number} [mode=0] - Access mode as for writeRam.
     * @returns {Promise<Buffer>} The bytes read.
     */
    async readRam(x, bank, n, mode = ACCESS_CHARACTER) {
        await this._ready;
        this._requireMixed();
        this._checkRamArgs(x, bank, n, mode);
        const cmds = Buffer.from([
            _CONT | _CMD_DEVICE_SELECT,
            this._ramAccessByte(mode, bank),
            x & 0x3F,
        ]);
        return this._conn.writeRead(cmds, n);
    }

    /**
     * Load the subaddress counter (for PCF8579 cascades). Subaddress 0 addresses the PCF8578
     * itself; pixel and RAM calls restore subaddress 0 before their own accesses.
     * @param {number} subaddress - 0 .. 15.
     * @returns {Promise<void>}
     */
    async deviceSelect(subaddress) {
        await this._ready;
        if (subaddress < 0 || subaddress > 15) throw new RangeError('subaddress must be in 0..15');
        await this._conn.write(Buffer.from([_CMD_DEVICE_SELECT | subaddress]));
    }

    /**
     * Re-run initialisation at the current row count (clears RAM, mixed mode, normal video).
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        this._ready = this._init();
        this._ready.catch(() => {});
        await this._ready;
    }
}

PCF8578Full.DISPLAY_BLANK   = STATUS_BLANK;
PCF8578Full.DISPLAY_NORMAL  = STATUS_NORMAL;
PCF8578Full.DISPLAY_ALL_ON  = STATUS_ALL_ON;
PCF8578Full.DISPLAY_INVERSE = STATUS_INVERSE;
PCF8578Full.ACCESS_CHARACTER    = ACCESS_CHARACTER;
PCF8578Full.ACCESS_HALF_GRAPHIC = ACCESS_HALF_GRAPHIC;
PCF8578Full.ACCESS_FULL_GRAPHIC = ACCESS_FULL_GRAPHIC;

module.exports = { PCF8578Minimal, PCF8578Full };
