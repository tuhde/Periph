'use strict';

const WIDTH = 132;
const HEIGHT = 64;
const I2C_CHUNK = 32;       // largest write in bytes including the control byte
const SPI_CHUNK = 132;

const _CMD_COL_LO = 0x00;       // | A[3:0]
const _CMD_COL_HI = 0x10;       // | A[7:4]
const _CMD_ADDR_MODE = 0x20;
const _CMD_COL_RANGE = 0x21;
const _CMD_PAGE_RANGE = 0x22;
const _CMD_START_LINE = 0x40;   // | A[5:0]
const _CMD_CONTRAST = 0x81;
const _CMD_BRIGHTNESS = 0x82;
const _CMD_LUT = 0x91;
const _CMD_BANK_1_16 = 0x92;
const _CMD_BANK_17_32 = 0x93;
const _CMD_SEG_REMAP = 0xA0;    // | ADC
const _CMD_SCROLL_AREA = 0xA3;
const _CMD_ALL_ON = 0xA4;       // | 1
const _CMD_INVERT = 0xA6;       // | 1
const _CMD_MULTIPLEX = 0xA8;
const _CMD_DIM_MODE = 0xAB;
const _CMD_DIM_ON = 0xAC;
const _CMD_MASTER = 0xAD;
const _CMD_DISPLAY = 0xAE;      // | 1
const _CMD_PAGE = 0xB0;         // | A[2:0]
const _CMD_SCAN = 0xC0;         // | (scan << 3)
const _CMD_OFFSET = 0xD3;
const _CMD_CLOCK = 0xD5;
const _CMD_AREA_COLOR = 0xD8;
const _CMD_PRECHARGE = 0xD9;
const _CMD_COM = 0xDA;
const _CMD_VCOMH = 0xDB;
const _INTERVALS = [0, 1, 2, 3, 4, 5, 6];   // table index -> 6, 32, 64, 128, 3, 4, 2 frames

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data). */
class I2CBus {
    constructor(connection) {
        this._conn = connection;
        this.canRead = true;
    }

    writeCommands(bytes) { return this._conn.write(Buffer.concat([Buffer.from([0x00]), Buffer.from(bytes)])); }

    async writeData(data) {
        const step = I2C_CHUNK - 1;
        for (let i = 0; i < data.length; i += step) {
            await this._conn.write(Buffer.concat([Buffer.from([0x40]), data.subarray(i, i + step)]));
        }
    }

    async readStatus() {
        await this._conn.write(Buffer.from([0x00]));
        return Buffer.from(await this._conn.read(1))[0];
    }

    async readRam(n) {
        await this._conn.write(Buffer.from([0x40]));
        return Buffer.from(await this._conn.read(n + 1)).subarray(1);
    }

    async hardwareReset() {}
}

/** 4-wire SPI transport on a display connection: D/C low for every command byte, high for RAM data. */
class SpiBus {
    constructor(connection) {
        this._conn = connection;
        this.canRead = false;
    }

    async writeCommands(bytes) {
        for (const b of bytes) await this._conn.sendCommand(b);
    }

    async writeData(data) {
        for (let i = 0; i < data.length; i += SPI_CHUNK) {
            await this._conn.sendData(data.subarray(i, i + SPI_CHUNK));
        }
    }

    hardwareReset() { return this._conn.reset(); }
}

/**
 * SSD1305 132 x 64 OLED controller-driver — minimal interface (I²C).
 *
 * Drives a monochrome SSD1305 OLED panel (2.23" 128 x 32 or 1.3" 128 x 64 modules) over I²C at
 * 0x3C (SA0 = low) or 0x3D (SA0 = high). The chip has 132 RAM columns; 128-pixel-wide modules
 * usually show RAM columns 4..131, so `columnOffset` defaults to 4.
 *
 * All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of
 * the page); `show()` sends it page by page (page addressing, the reset default). The chip has no
 * charge pump: VCC (7..15 V) must be supplied by the board. The initialization follows the
 * datasheet power-on flow: setup stream with the display off, RAM cleared, display on + 100 ms.
 * The constructor starts the initialisation;
 * every method that talks to the chip waits for it, and `ready()` resolves (or rejects) when done.
 *
 * @param {object} connection - I2CConnection at 0x3C or 0x3D.
 * @param {number} [width=128] - Visible columns, 1..132.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring).
 * @param {number} [columnOffset=4] - First RAM column shown (0 for a true 132-column panel).
 * @param {object} [bus] - Internal: transport override used by the SPI classes.
 */
class SSD1305Minimal {
    constructor(connection, width = 128, height = 64, columnOffset = 4, bus = null) {
        if (!(width >= 1 && width <= WIDTH)) throw new RangeError('width must be 1..132');
        if (!(height >= 16 && height <= HEIGHT && height % 8 === 0)) throw new RangeError('height must be a multiple of 8, 16..64');
        if (!(columnOffset >= 0 && columnOffset + width <= WIDTH)) throw new RangeError('columnOffset + width must be at most 132');
        this._bus = bus || new I2CBus(connection);
        this.width = width;
        this.height = height;
        this._offset = columnOffset;
        this._mode = 'page';
        this._pages = height / 8;
        this._fb = Buffer.alloc(width * this._pages);
        this._adc = 0;
        this._scan = 0;
        this._ready = this._initSequence();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /**
     * Resolves when initialisation has finished; rejects if it failed.
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    // ---- internals -------------------------------------------------------------------------

    _command(...values) {
        return this._bus.writeCommands(Buffer.from(values));
    }

    _addressBytes(page, column) {
        return Buffer.from([_CMD_PAGE | (page & 7), _CMD_COL_LO | (column & 0x0F), _CMD_COL_HI | ((column >> 4) & 0x0F)]);
    }

    async _writePage(page, data) {
        await this._bus.writeCommands(this._addressBytes(page, this._offset));
        await this._bus.writeData(data);
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    async _initSequence() {
        await this._bus.hardwareReset();
        await sleepMs(1);
        await this._command(_CMD_DISPLAY,
            _CMD_CLOCK, 0x70,
            _CMD_MULTIPLEX, this.height - 1,
            _CMD_OFFSET, 0x00,
            _CMD_START_LINE,
            _CMD_SEG_REMAP | this._adc,
            _CMD_SCAN | (this._scan << 3),
            _CMD_COM, this.height > 32 ? 0x12 : 0x02,
            _CMD_CONTRAST, 0x80,
            _CMD_PRECHARGE, 0x22,
            _CMD_VCOMH, 0x34,
            _CMD_MASTER, 0x8E,
            _CMD_ALL_ON,
            _CMD_INVERT,
            _CMD_ADDR_MODE, 0x02);
        const blank = Buffer.alloc(WIDTH);
        for (let page = 0; page < 8; page++) {
            await this._bus.writeCommands(this._addressBytes(page, 0));
            await this._bus.writeData(blank);
        }
        await this._command(_CMD_DISPLAY | 1);
        await sleepMs(100);
    }

    _showPage(page) {
        const start = page * this.width;
        return this._writePage(page, this._fb.subarray(start, start + this.width));
    }

    async _showAll() {
        if (this._mode === 'page') {
            for (let page = 0; page < this._pages; page++) await this._showPage(page);
            return;
        }
        await this._command(_CMD_COL_RANGE, this._offset, this._offset + this.width - 1,
            _CMD_PAGE_RANGE, 0, this._pages - 1);
        if (this._mode === 'horizontal') {
            await this._bus.writeData(this._fb);
            return;
        }
        const out = Buffer.alloc(this._fb.length);
        let n = 0;
        for (let x = 0; x < this.width; x++) {
            for (let p = 0; p < this._pages; p++) out[n++] = this._fb[p * this.width + x];
        }
        await this._bus.writeData(out);
    }

    // ---- public API ------------------------------------------------------------------------

    /** Zero the framebuffer. Call `show()` to update the panel. */
    clear() {
        this._fb.fill(0);
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param {number} x - Column, 0 .. width - 1.
     * @param {number} y - Row, 0 .. height - 1.
     * @param {boolean} [on=true] - True to set the pixel, false to clear it.
     */
    setPixel(x, y, on = true) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
        const i = (y >> 3) * this.width + x;
        if (on) this._fb[i] |= 1 << (y & 7);
        else this._fb[i] &= ~(1 << (y & 7)) & 0xFF;
    }

    /**
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page +
     * column command stream, then width data bytes. Horizontal / vertical addressing: column and
     * page range commands, then one data stream.
     * @returns {Promise<void>}
     */
    async show() {
        await this._ready;
        await this._showAll();
    }
}

/**
 * SSD1305 OLED controller-driver — full interface (I²C).
 *
 * Extends SSD1305Minimal with drawing primitives, contrast, brightness, dim mode, sleep, invert,
 * all-on test, flip, start line, display offset, multiplex ratio, clock / oscillator, pre-charge,
 * COM configuration, VCOMH level, addressing mode, hardware scrolling, area-colour mode with
 * LUT / bank colours, status and RAM reads (I²C only) and raw commands.
 *
 * @param {object} connection - I2CConnection at 0x3C or 0x3D.
 * @param {number} [width=128] - Visible columns, 1..132.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {number} [columnOffset=4] - First RAM column shown.
 * @param {object} [bus] - Internal: transport override used by the SPI classes.
 */
class SSD1305Full extends SSD1305Minimal {
    // ---- framebuffer -----------------------------------------------------------------------

    /**
     * Return a framebuffer pixel; out-of-range coordinates read as false.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {boolean} True if the pixel is set.
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) return false;
        return (this._fb[(y >> 3) * this.width + x] & (1 << (y & 7))) !== 0;
    }

    /** Set (or clear) the whole framebuffer. @param {boolean} [on=true] */
    fill(on = true) {
        this._fb.fill(on ? 0xFF : 0);
    }

    /**
     * Draw a Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {boolean} [on=true] - True to set pixels, false to clear them.
     */
    drawLine(x0, y0, x1, y1, on = true) {
        const dx = Math.abs(x1 - x0);
        const dy = -Math.abs(y1 - y0);
        const sx = x0 < x1 ? 1 : -1;
        const sy = y0 < y1 ? 1 : -1;
        let err = dx + dy;
        for (;;) {
            this.setPixel(x0, y0, on);
            if (x0 === x1 && y0 === y1) break;
            const e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /**
     * Draw a rectangle outline or a filled rectangle into the framebuffer.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels.
     * @param {boolean} [on=true] - True to set pixels, false to clear them.
     * @param {boolean} [fill=false] - True for a filled rectangle.
     */
    drawRect(x, y, w, h, on = true, fill = false) {
        if (w <= 0 || h <= 0) return;
        if (fill) {
            for (let yy = y; yy < y + h; yy++) for (let xx = x; xx < x + w; xx++) this.setPixel(xx, yy, on);
            return;
        }
        this.drawLine(x, y, x + w - 1, y, on);
        this.drawLine(x, y + h - 1, x + w - 1, y + h - 1, on);
        this.drawLine(x, y, x, y + h - 1, on);
        this.drawLine(x + w - 1, y, x + w - 1, y + h - 1, on);
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. `columns`
     * holds `heightPages` groups of columns, page by page: byte p * n + c is column c of page row p
     * (n = length / heightPages). The bitmap is clipped to the framebuffer; y must be a multiple of 8.
     * @param {number} x - Left column of the bitmap.
     * @param {number} y - Top row, a multiple of 8.
     * @param {Buffer|number[]} columns - Bitmap bytes.
     * @param {number} [heightPages=1] - Number of 8-pixel-high rows of the bitmap.
     */
    blit(x, y, columns, heightPages = 1) {
        if (y % 8 !== 0) throw new RangeError('y must be a multiple of 8');
        const n = Math.floor(columns.length / heightPages);
        for (let p = 0; p < heightPages; p++) {
            const page = y / 8 + p;
            if (page < 0 || page >= this._pages) continue;
            for (let c = 0; c < n; c++) {
                const xx = x + c;
                if (xx >= 0 && xx < this.width) this._fb[page * this.width + xx] = columns[p * n + c];
            }
        }
    }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update).
     * @param {number} page - Page 0 .. height / 8 - 1.
     * @returns {Promise<void>}
     */
    async showPage(page) {
        await this._ready;
        if (!(page >= 0 && page < this._pages)) return;
        if (this._mode === 'page') {
            await this._showPage(page);
            return;
        }
        const start = page * this.width;
        await this._command(_CMD_COL_RANGE, this._offset, this._offset + this.width - 1, _CMD_PAGE_RANGE, page, page);
        await this._bus.writeData(this._fb.subarray(start, start + this.width));
    }

    // ---- display control -------------------------------------------------------------------

    /** Set the segment output current (contrast). @param {number} value - 0..255 (reset 0x80). @returns {Promise<void>} */
    async setContrast(value) {
        await this._ready;
        await this._command(_CMD_CONTRAST, value & 0xFF);
    }

    /** Switch the display on or off (RAM and settings are kept). @param {boolean} on @returns {Promise<void>} */
    async setDisplay(on) {
        await this._ready;
        await this._command(_CMD_DISPLAY | (on ? 1 : 0));
    }

    /** Display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards. @returns {Promise<void>} */
    sleep() { return this.setDisplay(false); }

    /** Display on, then wait 100 ms (tAF) for SEG / COM to turn on. @returns {Promise<void>} */
    async wake() {
        await this.setDisplay(true);
        await sleepMs(100);
    }

    /**
     * Switch between dim mode (0xAC) and normal display on (0xAF); configure the levels with `setDimMode` first.
     * @param {boolean} on
     * @returns {Promise<void>}
     */
    async setDim(on) {
        await this._ready;
        await this._command(on ? _CMD_DIM_ON : _CMD_DISPLAY | 1);
    }

    /**
     * Configure the BANK0 contrast and colour-bank brightness used in dim mode.
     * @param {number} contrast - 0..255.
     * @param {number} brightness - 0..255.
     * @returns {Promise<void>}
     */
    async setDimMode(contrast, brightness) {
        await this._ready;
        await this._command(_CMD_DIM_MODE, 0x00, contrast & 0xFF, brightness & 0xFF);
    }

    /** Invert the displayed image without changing RAM. @param {boolean} on @returns {Promise<void>} */
    async setInvert(on) {
        await this._ready;
        await this._command(_CMD_INVERT | (on ? 1 : 0));
    }

    /** Force all pixels on (test mode, RAM unchanged). @param {boolean} on @returns {Promise<void>} */
    async setAllOn(on) {
        await this._ready;
        await this._command(_CMD_ALL_ON | (on ? 1 : 0));
    }

    /**
     * Mirror columns (segment re-map) and/or rows (COM scan direction); the framebuffer is unchanged.
     * @param {boolean} horizontal - True to reverse the segment mapping (ADC = 1).
     * @param {boolean} vertical - True to reverse the COM scan direction (0xC8).
     * @returns {Promise<void>}
     */
    async setFlip(horizontal, vertical) {
        await this._ready;
        this._adc = horizontal ? 1 : 0;
        this._scan = vertical ? 1 : 0;
        await this._command(_CMD_SEG_REMAP | this._adc, _CMD_SCAN | (this._scan << 3));
    }

    /** Set the RAM line shown at COM0 (hardware vertical scroll). @param {number} line - 0..63. @returns {Promise<void>} */
    async setStartLine(line) {
        await this._ready;
        await this._command(_CMD_START_LINE | (line & 0x3F));
    }

    /** Shift the COM mapping. @param {number} offset - 0..63. @returns {Promise<void>} */
    async setDisplayOffset(offset) {
        await this._ready;
        await this._command(_CMD_OFFSET, offset & 0x3F);
    }

    /** Set the multiplex ratio. @param {number} ratio - 16..64. @returns {Promise<void>} */
    async setMultiplex(ratio) {
        await this._ready;
        await this._command(_CMD_MULTIPLEX, (ratio - 1) & 0x3F);
    }

    /**
     * Set the clock divide ratio and oscillator frequency.
     * @param {number} divide - Divide ratio, 1..16.
     * @param {number} osc - Oscillator setting, 0..15 (7 = reset value).
     * @returns {Promise<void>}
     */
    async setClock(divide, osc) {
        await this._ready;
        await this._command(_CMD_CLOCK, ((osc & 0x0F) << 4) | ((divide - 1) & 0x0F));
    }

    /**
     * Set the pre-charge phase 1 and phase 2 periods in display clocks.
     * @param {number} phase1 - 1..15.
     * @param {number} phase2 - 1..15.
     * @returns {Promise<void>}
     */
    async setPrecharge(phase1, phase2) {
        await this._ready;
        await this._command(_CMD_PRECHARGE, ((phase2 & 0x0F) << 4) | (phase1 & 0x0F));
    }

    /**
     * Select the COM pin configuration.
     * @param {boolean} alternative - True alternative (reset), false sequential.
     * @param {boolean} [remap=false] - True to enable the COM left / right remap.
     * @returns {Promise<void>}
     */
    async setComConfig(alternative, remap = false) {
        await this._ready;
        await this._command(_CMD_COM, 0x02 | (alternative ? 0x10 : 0) | (remap ? 0x20 : 0));
    }

    /** Set the VCOMH deselect level (only bits 5:2 used). @param {number} value - 0x00..0x3C (reset 0x34). @returns {Promise<void>} */
    async setVcomh(value) {
        await this._ready;
        await this._command(_CMD_VCOMH, value & 0x3C);
    }

    /**
     * Select how `show()` writes the display RAM.
     * @param {'page'|'horizontal'|'vertical'} mode
     * @returns {Promise<void>}
     */
    async setAddressingMode(mode) {
        const codes = { horizontal: 0, vertical: 1, page: 2 };
        if (!(mode in codes)) throw new RangeError('mode must be page, horizontal or vertical');
        await this._ready;
        this._mode = mode;
        await this._command(_CMD_ADDR_MODE, codes[mode]);
    }

    // ---- hardware scrolling ------------------------------------------------------------------

    /**
     * Set up a continuous horizontal scroll (start it with `scrollStart`). Do not call while scrolling.
     * @param {boolean} right - True to scroll right.
     * @param {number} startPage - 0..7.
     * @param {number} endPage - startPage..7.
     * @param {number} interval - Table index 0..6 = 6, 32, 64, 128, 3, 4, 2 frames per step.
     * @param {number} [columns=1] - Columns per step, 1..4.
     * @returns {Promise<void>}
     */
    async setScrollHorizontal(right, startPage, endPage, interval, columns = 1) {
        await this._ready;
        await this._command(right ? 0x26 : 0x27, columns & 7, startPage & 7, _INTERVALS[interval], endPage & 7);
    }

    /**
     * Set up a continuous vertical + horizontal scroll (start it with `scrollStart`).
     * @param {boolean} right - True to scroll right.
     * @param {number} startPage - 0..7.
     * @param {number} endPage - startPage..7.
     * @param {number} interval - Table index 0..6.
     * @param {number} columns - Horizontal columns per step, 0..4.
     * @param {number} rows - Vertical rows per step, 1..63.
     * @returns {Promise<void>}
     */
    async setScrollVertical(right, startPage, endPage, interval, columns, rows) {
        await this._ready;
        await this._command(right ? 0x29 : 0x2A, columns & 7, startPage & 7, _INTERVALS[interval], endPage & 7, rows & 0x3F);
    }

    /**
     * Set the vertical scroll area.
     * @param {number} topFixed - Rows in the fixed top area, 0..63.
     * @param {number} scrollRows - Rows in the scrolling area, 1..64.
     * @returns {Promise<void>}
     */
    async setScrollArea(topFixed, scrollRows) {
        await this._ready;
        await this._command(_CMD_SCROLL_AREA, topFixed & 0x3F, scrollRows & 0x7F);
    }

    /** Activate the configured scroll (0x2F). @returns {Promise<void>} */
    async scrollStart() {
        await this._ready;
        await this._command(0x2F);
    }

    /** Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. @returns {Promise<void>} */
    async scrollStop() {
        await this._ready;
        await this._command(0x2E);
        await this._showAll();
    }

    // ---- area colour -------------------------------------------------------------------------

    /**
     * Enable the area-colour mode and/or the low-power display mode.
     * @param {boolean} enable - True to enable area colour.
     * @param {boolean} [lowPower=false] - True for low-power display mode.
     * @returns {Promise<void>}
     */
    async setAreaColor(enable, lowPower = false) {
        await this._ready;
        await this._command(_CMD_AREA_COLOR, (enable ? 0x30 : 0) | (lowPower ? 0x05 : 0));
    }

    /** Set the colour-bank brightness. @param {number} value - 0..255 (reset 0x80). @returns {Promise<void>} */
    async setBrightness(value) {
        await this._ready;
        await this._command(_CMD_BRIGHTNESS, value & 0xFF);
    }

    /**
     * Set the drive pulse width codes (31..63 = 32..64 clocks) of BANK0 and colours A, B, C.
     * @param {number} bank0
     * @param {number} a
     * @param {number} b
     * @param {number} c
     * @returns {Promise<void>}
     */
    async setLut(bank0, a, b, c) {
        await this._ready;
        await this._command(_CMD_LUT, bank0 & 0x3F, a & 0x3F, b & 0x3F, c & 0x3F);
    }

    /**
     * Assign colours A..D (2 bits per bank) to the area-colour banks.
     * @param {Buffer|number[]} banks1to16 - 4 bytes for banks 1..16.
     * @param {Buffer|number[]|null} [banks17to32=null] - Optional 4 bytes for banks 17..32.
     * @returns {Promise<void>}
     */
    async setBankColors(banks1to16, banks17to32 = null) {
        if (banks1to16.length !== 4 || (banks17to32 !== null && banks17to32.length !== 4)) {
            throw new RangeError('bank colour groups need 4 bytes');
        }
        await this._ready;
        await this._command(_CMD_BANK_1_16, ...banks1to16);
        if (banks17to32 !== null) await this._command(_CMD_BANK_17_32, ...banks17to32);
    }

    // ---- reads and raw access --------------------------------------------------------------

    /**
     * Read the status byte (I²C only).
     * @returns {Promise<number>} Bit 6 = 1 when the display is off; the other bits are reserved.
     */
    async readStatus() {
        await this._ready;
        if (!this._bus.canRead) throw new Error('the SPI interface is write-only');
        return this._bus.readStatus();
    }

    /**
     * Read `n` bytes of display RAM (I²C only); the dummy byte after an address change is discarded.
     * @param {number} page - RAM page 0..7.
     * @param {number} column - Start RAM column.
     * @param {number} n - Number of bytes.
     * @returns {Promise<Buffer>} The RAM bytes.
     */
    async readPage(page, column, n) {
        await this._ready;
        if (!this._bus.canRead) throw new Error('the SPI interface is write-only');
        await this._bus.writeCommands(this._addressBytes(page, column));
        return this._bus.readRam(n);
    }

    /** Send raw bytes as one command stream. @param {...number} bytes @returns {Promise<void>} */
    async sendCommand(...bytes) {
        await this._ready;
        await this._command(...bytes);
    }
}

/**
 * SSD1305 over 4-wire SPI — minimal interface.
 *
 * Same framebuffer, drawing and initialization as SSD1305Minimal, driven through a display connection
 * (CS, D/C, RES optional, no BUSY). The chip must be strapped for 4-wire SPI. SPI mode 0, up to
 * 4 MHz (2 MHz recommended). Every command byte is sent with D/C low, RAM data with D/C high. SPI cannot read:
 * `readStatus()` / `readPage()` of the full class reject.
 *
 * @param {object} connection - DisplayConnection.
 * @param {number} [width=128] - Visible columns, 1..132.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {number} [columnOffset=4] - First RAM column shown.
 */
class SSD1305SpiMinimal extends SSD1305Minimal {
    constructor(connection, width = 128, height = 64, columnOffset = 4) {
        super(null, width, height, columnOffset, new SpiBus(connection));
    }
}

/**
 * SSD1305 over 4-wire SPI — full interface (see SSD1305Full; `readStatus()` and `readPage()` reject).
 *
 * @param {object} connection - DisplayConnection.
 * @param {number} [width=128] - Visible columns, 1..132.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {number} [columnOffset=4] - First RAM column shown.
 */
class SSD1305SpiFull extends SSD1305Full {
    constructor(connection, width = 128, height = 64, columnOffset = 4) {
        super(null, width, height, columnOffset, new SpiBus(connection));
    }
}

module.exports = { SSD1305Minimal, SSD1305Full, SSD1305SpiMinimal, SSD1305SpiFull, WIDTH, HEIGHT, I2C_CHUNK, SPI_CHUNK };
