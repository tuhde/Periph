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
 * PCF8579 LCD column driver — minimal interface.
 *
 * The PCF8579 is the column-driver companion of the PCF8578 and cannot run alone: the clock,
 * frame sync and row waveforms come from a PCF8578 (or an equivalent source). This driver
 * writes the display RAM of one PCF8579 (4 banks x 40 columns, X = n drives output Cn). The
 * chip has no register map; this driver streams command bytes and display data with the plain
 * connection `write` / `writeRead` calls. The I2C address is 0x3C (SA0 = VSS) or 0x3D
 * (SA0 = VDD); the chip supports 100 kHz at most.
 *
 * The constructor starts the initialisation (waits for the internal reset, clears this device's
 * RAM, then switches the display on in normal video with start bank 0); every method waits for
 * it and `ready()` resolves (or rejects) when it is done. If the subaddress pins A3:A0 do not
 * match `subaddress`, the data bytes are not acknowledged and `ready()` rejects. A shadow copy
 * of the 4 x 40 byte RAM is kept so single-pixel updates need only one byte on the bus. One RAM
 * byte is 8 vertical pixels of one column; bit 0 is the top row of the bank.
 *
 * device-select, set-mode and set-start-bank reach every device on the I2C address, so all
 * drivers on one bus must use the same `rows` / `mixed` settings.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C
 *   connection pointing at the device (100 kHz maximum).
 * @param {number} [subaddress=0] - Hardware subaddress set on pins A3:A0, 0 .. 15.
 * @param {number} [rows=16] - Number of display rows: 8, 16, 24 or 32.
 * @param {boolean} [mixed=false] - true if the PCF8578 in the system runs in mixed mode (T bit).
 */
class PCF8579Minimal {
    constructor(connection, subaddress = 0, rows = 16, mixed = false) {
        if (!(rows in _MUX_CODE)) {
            throw new RangeError('rows must be 8, 16, 24 or 32');
        }
        if (subaddress < 0 || subaddress > 15) {
            throw new RangeError('subaddress must be in 0..15');
        }
        this._conn = connection;
        this._subaddress = subaddress;
        this._rows = rows;
        this._mixed = !!mixed;
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

    _selectByte() {
        return _CONT | _CMD_DEVICE_SELECT | this._subaddress;
    }

    _zeroShadow() {
        for (const bank of this._shadow) bank.fill(0);
    }

    async _init() {
        await new Promise((resolve) => setTimeout(resolve, 2)); // >= 1 ms internal power-on reset
        this._startBank = 0;
        const out = [
            this._selectByte(),                                      // device-select
            this._modeByte(STATUS_BLANK, true),                      // blank
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

    // Write data at RAM column x / bank and mirror it in the shadow.
    async _ramWrite(x, bank, data, access = ACCESS_CHARACTER) {
        const out = [
            this._selectByte(),
            this._ramAccessByte(access, bank),
            x & 0x3F,
            ...data,
        ];
        await this._conn.write(Buffer.from(out));
        const perCol = _PER_COL[access];
        for (let i = 0; i < data.length; i++) {
            const col = x + Math.floor(i / perCol);
            if (col < RAM_COLUMNS) this._shadow[bank + (i % perCol)][col] = data[i];
        }
    }

    /**
     * Zero this device's whole display RAM (4 banks x 40 columns) and the shadow.
     * @returns {Promise<void>}
     */
    async clear() {
        await this._ready;
        const out = [
            this._selectByte(),
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
     * @param {number} x - Column, 0 .. 39.
     * @param {number} y - Row, 0 .. rows - 1.
     * @param {boolean} [on=true] - true lights the pixel, false clears it.
     * @returns {Promise<void>}
     */
    async setPixel(x, y, on = true) {
        await this._ready;
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= this._rows) return;
        const bank = y >> 3;
        const bit = 1 << (y & 7);
        const value = on ? (this._shadow[bank][x] | bit) : (this._shadow[bank][x] & ~bit & 0xFF);
        await this._ramWrite(x, bank, [value]);
    }

    /**
     * Write consecutive column bytes in character access mode.
     *
     * @param {number} x - First column, 0 .. 39.
     * @param {number} bank - Bank (8-row band), 0 .. rows/8 - 1.
     * @param {number[]|Buffer} data - Column bytes; bit 0 is the top row of the bank. The write
     *   is truncated at column 39.
     * @returns {Promise<void>}
     */
    async writeColumns(x, bank, data) {
        await this._ready;
        if (bank < 0 || bank >= (this._rows >> 3)) {
            throw new RangeError('bank out of range for the configured rows');
        }
        if (x < 0 || x >= RAM_COLUMNS) return;
        const bytes = Array.from(data).slice(0, RAM_COLUMNS - x);
        if (bytes.length === 0) return;
        await this._ramWrite(x, bank, bytes);
    }
}

/**
 * PCF8579 full interface — extends PCF8579Minimal.
 *
 * Adds pixel read-back from the shadow, fill, line/rectangle/bitmap drawing, display status
 * (blank / normal / all-on / inverse), multiplex rate and PCF8578 mode bit, hardware vertical
 * scrolling via the start bank, and raw RAM write/read in all three access modes including
 * transfers that run on into the following cascaded devices.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C
 *   connection pointing at the device (100 kHz maximum).
 * @param {number} [subaddress=0] - Hardware subaddress set on pins A3:A0, 0 .. 15.
 * @param {number} [rows=16] - Number of display rows: 8, 16, 24 or 32.
 * @param {boolean} [mixed=false] - true if the PCF8578 in the system runs in mixed mode.
 */
class PCF8579Full extends PCF8579Minimal {
    _plot(x, y, on, dirty) {
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= this._rows) return;
        const bank = y >> 3;
        const bit = 1 << (y & 7);
        this._shadow[bank][x] = on ? (this._shadow[bank][x] | bit) : (this._shadow[bank][x] & ~bit & 0xFF);
        dirty.add(bank * RAM_COLUMNS + x);
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
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= this._rows) return false;
        return (this._shadow[y >> 3][x] & (1 << (y & 7))) !== 0;
    }

    /**
     * Set every displayed pixel (rows x 40) on or off.
     * @param {boolean} [on=true] - true lights all pixels, false clears them.
     * @returns {Promise<void>}
     */
    async fill(on = true) {
        await this._ready;
        for (let bank = 0; bank < (this._rows >> 3); bank++) {
            await this._ramWrite(0, bank, new Array(RAM_COLUMNS).fill(on ? 0xFF : 0x00));
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
     * Blit 8-pixel-high column bytes aligned to a bank boundary.
     *
     * @param {number} x - Left column.
     * @param {number} y - Top row; must be a non-negative multiple of 8.
     * @param {number[]|Buffer} columns - One byte per column (bit 0 = top row); clipped at
     *   column 39.
     * @returns {Promise<void>}
     */
    async showBitmap(x, y, columns) {
        if (y % 8 !== 0 || y < 0) {
            throw new RangeError('y must be a non-negative multiple of 8');
        }
        await this.writeColumns(x, y >> 3, columns);
    }

    /**
     * Set the display status; RAM contents are preserved. Affects all devices on the address.
     * @param {number} status - PCF8579Full.DISPLAY_BLANK / _NORMAL / _ALL_ON / _INVERSE.
     * @returns {Promise<void>}
     */
    async setDisplay(status) {
        await this._ready;
        if (status < 0 || status > 3) throw new RangeError('status must be in 0..3');
        this._status = status;
        await this._conn.write(Buffer.from([this._modeByte(status, false)]));
    }

    /**
     * Change the multiplex rate and the PCF8578 mode bit T (affects all devices).
     *
     * @param {number} rows - 8, 16, 24 or 32.
     * @param {boolean} [mixed=false] - true if the PCF8578 runs in mixed mode.
     * @returns {Promise<void>}
     */
    async setMode(rows, mixed = false) {
        await this._ready;
        if (!(rows in _MUX_CODE)) throw new RangeError('rows must be 8, 16, 24 or 32');
        this._rows = rows;
        this._mixed = !!mixed;
        await this._conn.write(Buffer.from([this._modeByte(this._status, false)]));
    }

    /**
     * Select the RAM bank shown at the top of the display (vertical scroll, all devices).
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

    _checkRamArgs(x, bank, count, access, cascade = false) {
        if (access < 0 || access > 2) throw new RangeError('access must be in 0..2');
        if (x < 0 || x > 39 || bank < 0 || bank > 3) throw new RangeError('x must be in 0..39 and bank in 0..3');
        if (access === 1 && bank % 2) throw new RangeError('half-graphic access must start at an even bank');
        if (access === 2 && bank !== 0) throw new RangeError('full-graphic access must start at bank 0');
        if (!cascade && x + Math.ceil(count / _PER_COL[access]) > 40) throw new RangeError('write would pass column 39');
    }

    /**
     * Raw RAM write of this device in character, half-graphic or full-graphic order.
     *
     * @param {number} x - RAM column, 0 .. 39.
     * @param {number} bank - RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic).
     * @param {number[]|Buffer} data - Bytes to write; must not pass column 39.
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
     * Write from column 0, bank 0 of this device and run on into the following subaddresses (the
     * chip advances its subaddress counter after column 39). The shadow only tracks this
     * device's first 40 columns.
     *
     * @param {number[]|Buffer} data - Bytes to write.
     * @param {number} [mode=2] - ACCESS_CHARACTER, ACCESS_HALF_GRAPHIC or ACCESS_FULL_GRAPHIC.
     * @returns {Promise<void>}
     */
    async writeRamCascade(data, mode = ACCESS_FULL_GRAPHIC) {
        await this._ready;
        const bytes = Array.from(data);
        this._checkRamArgs(0, 0, bytes.length, mode, true);
        if (bytes.length === 0) return;
        await this._ramWrite(0, 0, bytes, mode);
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
        this._checkRamArgs(x, bank, n, mode);
        const cmds = Buffer.from([
            this._selectByte(),
            this._ramAccessByte(mode, bank),
            x & 0x3F,
        ]);
        return this._conn.writeRead(cmds, n);
    }

    /**
     * Re-run initialisation (clears this device's RAM, normal video, start bank 0).
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        this._ready = this._init();
        this._ready.catch(() => {});
        await this._ready;
    }
}

PCF8579Full.DISPLAY_BLANK   = STATUS_BLANK;
PCF8579Full.DISPLAY_NORMAL  = STATUS_NORMAL;
PCF8579Full.DISPLAY_ALL_ON  = STATUS_ALL_ON;
PCF8579Full.DISPLAY_INVERSE = STATUS_INVERSE;
PCF8579Full.ACCESS_CHARACTER    = ACCESS_CHARACTER;
PCF8579Full.ACCESS_HALF_GRAPHIC = ACCESS_HALF_GRAPHIC;
PCF8579Full.ACCESS_FULL_GRAPHIC = ACCESS_FULL_GRAPHIC;

module.exports = { PCF8579Minimal, PCF8579Full };
