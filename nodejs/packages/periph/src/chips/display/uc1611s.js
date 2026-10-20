'use strict';

const WIDTH = 240;
const HEIGHT = 128;
const I2C_CHUNK = 32;
const SPI_CHUNK = 240;
const MAX_ATTEMPTS = 8;
const RETRY_DELAY_MS = 2;

const _PAGES_RAM = 20;          // RAM pages cleared at init (160 rows / 8)
const _CMD_TEMP = 0x24;         // | TC[1:0]
const _CMD_PANEL = 0x28;        // | PC[1:0]
const _CMD_PUMP = 0x2C;         // | PC[3:2]
const _CMD_SCROLL_LO = 0x40;    // | SL[3:0]
const _CMD_SCROLL_HI = 0x50;    // | SL[7:4]
const _CMD_PAGE_LO = 0x60;      // | PA[3:0]
const _CMD_PAGE_HI = 0x70;      // | PA[6:4]
const _CMD_POTENTIOMETER = 0x81;
const _CMD_ISOLATION = 0x82;
const _CMD_PARTIAL = 0x84;      // | LC[9:8]
const _CMD_RAM_ADDR = 0x88;     // | AC[2:0]
const _CMD_FIXED = 0x90;        // | FL[3:0]
const _CMD_LINE_RATE = 0xA0;    // | LC[5:4]
const _CMD_ALL_ON = 0xA4;       // | DC1
const _CMD_INVERSE = 0xA6;      // | DC0
const _CMD_DISPLAY = 0xA8;      // | DC[4:2]
const _CMD_MAPPING = 0xC0;
const _CMD_NLINE = 0xC8;
const _CMD_PATTERN = 0xD0;      // | DC[7:5]
const _CMD_RESET = 0xE2;
const _CMD_NOP = 0xE3;
const _CMD_BIAS = 0xE8;         // | BR[1:0]
const _CMD_COM_END = 0xF1;
const _CMD_PART_START = 0xF2;
const _CMD_PART_END = 0xF3;

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** I²C transport: commands on the command address, RAM data on the data address (chunked). */
class I2CBus {
    constructor(command, data) {
        this._cmd = command;
        this._data = data;
        this.canRead = true;
    }

    writeCommand(bytes) { return this._cmd.write(bytes); }

    async writeData(data) {
        for (let i = 0; i < data.length; i += I2C_CHUNK) {
            await this._data.write(data.subarray(i, i + I2C_CHUNK));
        }
    }

    async readStatus() {
        await this._cmd.write(Buffer.from([_CMD_NOP]));
        return Buffer.from(await this._cmd.read(3));
    }

    async readRam(n) {
        return Buffer.from(await this._data.read(n + 1)).subarray(1);
    }

    async hardwareReset() {}
}

/** 4-wire SPI transport on a display connection: D/C# low for every command byte, high for RAM data. */
class SpiBus {
    constructor(connection) {
        this._conn = connection;
        this.canRead = false;
    }

    async writeCommand(bytes) {
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
 * UC1611s graphic LCD controller-driver — minimal interface.
 *
 * Drives an EA DOGXL240-7 (240 x 128 dots, 3.3 V) in 1 bit per pixel black/white mode over I²C.
 * The chip carries the command/data bit in its I²C address, so it answers on two consecutive
 * 7-bit addresses: 0x38 + 2n for commands and 0x39 + 2n for display RAM data (n = A3 << 1 | A2,
 * the two address pins). The driver therefore takes two connections on the same bus.
 *
 * All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel
 * of the page, 3840 bytes at 240 x 128); `show()` sends it to the display RAM. The initialization
 * follows the DOG module's bottom-view example: System Reset, temperature compensation
 * -0.10 %/degC, COM end 127, potentiometer (contrast) 0x8F, 1 bit per pixel, RAM cleared, display on.
 * The constructor starts the initialisation; every method that talks to the chip waits for it,
 * and `ready()` resolves (or rejects) when it is done.
 *
 * Error handling: the chip occasionally NACKs a transfer (seen on long cables and for about 6 ms
 * while the charge pump restarts), which the bus layer reports as a rejected promise. Every
 * transfer is retried up to MAX_ATTEMPTS times, RETRY_DELAY_MS apart: a command stream is
 * repeated and a RAM page is rewritten from its address command (a partial write cannot be
 * continued). Repeated attempts are counted (`retryCount()`); a transfer that still fails
 * rejects with its error and is counted in `errorCount()`.
 *
 * @param {object} command - I2CConnection at the command address (0x38, 0x3A, 0x3C, 0x3E).
 * @param {object} data - I2CConnection at the data address (command address + 1).
 * @param {number} [width=240] - Columns, 1..240.
 * @param {number} [height=128] - Rows, a multiple of 8 and at most 128.
 * @param {object} [options] - Used by UC1611sFull: `{ topView, contrast }`.
 */
class UC1611sMinimal {
    constructor(command, data, width = WIDTH, height = HEIGHT, options = {}) {
        if (!(width >= 1 && width <= WIDTH)) throw new RangeError('width must be 1..240');
        if (!(height >= 8 && height <= HEIGHT && height % 8 === 0)) throw new RangeError('height must be a multiple of 8, 8..128');
        this._bus = options.bus || new I2CBus(command, data);
        this.width = width;
        this.height = height;
        this._pages = height / 8;
        this._fb = Buffer.alloc(width * this._pages);
        const topView = !!options.topView;
        this._contrast = options.contrast === undefined ? 0x8F : options.contrast;
        this._mx = topView ? 0 : 1;
        this._my = topView ? 1 : 0;
        this._pattern = 0;
        this._retries = 0;
        this._errors = 0;
        this._ready = this._initSequence();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /**
     * Resolves when initialisation has finished; rejects if it failed (for example when the
     * chip does not acknowledge the System Reset).
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    /** @returns {boolean} True if no transfer has failed after all retries since construction or clearErrors(). */
    ok() { return this._errors === 0; }

    /** @returns {number} Number of transfer attempts that had to be repeated. */
    retryCount() { return this._retries; }

    /** @returns {number} Number of transfers that still failed after MAX_ATTEMPTS attempts. */
    errorCount() { return this._errors; }

    /** Reset the retry and error counters. */
    clearErrors() {
        this._retries = 0;
        this._errors = 0;
    }

    // ---- internals -------------------------------------------------------------------------

    /** Run `fn` up to MAX_ATTEMPTS times (RETRY_DELAY_MS apart); count retries and the final failure. */
    async _retry(fn) {
        let err;
        for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return await fn();
            } catch (e) {
                err = e;
                this._retries++;
                await sleepMs(RETRY_DELAY_MS);
            }
        }
        this._errors++;
        throw err;
    }

    /** Send bytes as one command transaction on the command address, retried on failure. */
    _command(...values) {
        const bytes = Buffer.from(values);
        return this._retry(() => this._bus.writeCommand(bytes));
    }

    /** One pass over the data in transport-sized chunks (rejects on a NACK). */
    _writeChunks(data) {
        return this._bus.writeData(data);
    }

    /** Send RAM data without retry (the write position of a partial write is unknown). */
    async _writeData(data) {
        try {
            await this._writeChunks(data);
        } catch (e) {
            this._errors++;
            throw e;
        }
    }

    _addressBytes(page, column) {
        const pa = (this._pattern << 5) | (page & 0x1F);
        return Buffer.from([_CMD_PAGE_LO | (pa & 0x0F), _CMD_PAGE_HI | ((pa >> 4) & 0x07), column & 0x0F, 0x10 | (column >> 4)]);
    }

    /** Write one page: address command, then the data; a failure repeats both. */
    _writePage(page, data) {
        return this._retry(async () => {
            await this._bus.writeCommand(this._addressBytes(page, 0));
            await this._writeChunks(data);
        });
    }

    /** Read Status1..3; a NOP write first restarts the chip's status cycle (it advances by n + 1 per read). */
    _readStatus() {
        if (!this._bus.canRead) return Promise.reject(new Error('the SPI interface of the module is write-only'));
        return this._retry(() => this._bus.readStatus());
    }

    /** Poll Status1.DE until the pump is up (SPI cannot read it: wait a fixed 10 ms); NACKs while it starts are not counted. */
    async _waitDisplayEnabled(timeoutMs) {
        if (!this._bus.canRead) {
            await sleepMs(Math.min(timeoutMs, 10));
            return;
        }
        for (let waited = 0; waited <= timeoutMs; waited += RETRY_DELAY_MS) {
            try {
                const s = await this._bus.readStatus();
                if (s[0] & 0x08) return;
            } catch (e) {
                // expected while the charge pump restarts
            }
            await sleepMs(RETRY_DELAY_MS);
        }
    }

    _mapping() {
        return (this._my << 2) | (this._mx << 1);
    }

    /** System Reset in its own transaction, then the conservative 150 ms wait. */
    async _resetChip() {
        await this._command(_CMD_RESET);
        await sleepMs(150);
    }

    /** Configuration stream of the DOG bottom-view example (display stays off). */
    _configure() {
        const last = this.height - 1;
        return this._command(
            _CMD_TEMP | 1,
            _CMD_MAPPING, this._mapping(),
            _CMD_LINE_RATE | 3,
            _CMD_COM_END, last,
            _CMD_PART_START, 0,
            _CMD_PART_END, last,
            _CMD_POTENTIOMETER, this._contrast,
            _CMD_RAM_ADDR | 1,
            _CMD_PATTERN | 1);
    }

    _enable() {
        return this._command(_CMD_DISPLAY | 1);
    }

    /** Power-up wait, System Reset, configuration, RAM clear, display on. */
    async _initSequence() {
        await sleepMs(150);
        await this._bus.hardwareReset();
        await this._resetChip();
        await this._configure();
        const blank = Buffer.alloc(this.width);
        for (let page = 0; page < _PAGES_RAM; page++) await this._writePage(page, blank);
        await this._enable();
        await this._waitDisplayEnabled(200);
    }

    _showPage(page) {
        const start = page * this.width;
        return this._writePage(page, this._fb.subarray(start, start + this.width));
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
     * Write the whole framebuffer to the display RAM (page + column, then width data bytes per page).
     * @returns {Promise<void>}
     */
    async show() {
        await this._ready;
        for (let page = 0; page < this._pages; page++) await this._showPage(page);
    }
}

/**
 * UC1611s graphic LCD controller-driver — full interface.
 *
 * Extends UC1611sMinimal with drawing primitives, contrast, sleep, invert, flip / top view,
 * hardware scroll with fixed lines, bias ratio, temperature compensation, line rate, charge
 * pump / external VLCD, panel loading, partial display, N-line inversion, isolation clock, the
 * four RAM patterns, status and RAM reads, and raw commands.
 *
 * @param {object} command - I2CConnection at the command address.
 * @param {object} data - I2CConnection at the data address (command address + 1).
 * @param {number} [width=240] - Columns, 1..240.
 * @param {number} [height=128] - Rows, a multiple of 8 and at most 128.
 * @param {boolean} [topView=false] - True for the 12:00 viewing direction (MY = 1, MX = 0).
 * @param {number} [contrast=0x8F] - Potentiometer PM 0..255 (the DOG example value).
 */
class UC1611sFull extends UC1611sMinimal {
    constructor(command, data, width = WIDTH, height = HEIGHT, topView = false, contrast = 0x8F, bus = null) {
        if (!(contrast >= 0 && contrast <= 255)) throw new RangeError('contrast must be 0..255');
        super(command, data, width, height, { topView, contrast, bus });
    }

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
        if (page >= 0 && page < this._pages) await this._showPage(page);
    }

    // ---- display control -------------------------------------------------------------------

    /** Set the potentiometer PM that adjusts VLCD (contrast). @param {number} value - PM, 0..255. @returns {Promise<void>} */
    async setContrast(value) {
        await this._ready;
        this._contrast = value & 0xFF;
        await this._command(_CMD_POTENTIOMETER, this._contrast);
    }

    /** Switch the display on, or into sleep (registers and RAM kept, below 1 uA). @param {boolean} on @returns {Promise<void>} */
    async setDisplay(on) {
        await this._ready;
        await this._command(_CMD_DISPLAY | (on ? 1 : 0));
    }

    /** Enter sleep mode: drivers, charge pump and clock stop; the host interface stays active. @returns {Promise<void>} */
    sleep() { return this.setDisplay(false); }

    /**
     * Leave sleep mode. The chip NACKs and Status1.DE stays 0 for about 6 ms while the charge pump
     * restarts, so the driver polls DE (at most 50 ms) instead of sleeping.
     * @returns {Promise<void>}
     */
    async wake() {
        await this.setDisplay(true);
        await this._waitDisplayEnabled(50);
    }

    /** Invert the displayed image without changing RAM. @param {boolean} on @returns {Promise<void>} */
    async setInvert(on) {
        await this._ready;
        await this._command(_CMD_INVERSE | (on ? 1 : 0));
    }

    /** Force all pixels on (test mode, RAM unchanged). @param {boolean} on @returns {Promise<void>} */
    async setAllOn(on) {
        await this._ready;
        await this._command(_CMD_ALL_ON | (on ? 1 : 0));
    }

    /**
     * Mirror columns and/or rows. MY acts at once; MX is applied to RAM writes, so call `show()`
     * afterwards to re-send the framebuffer.
     * @param {boolean} mirrorX - True to mirror the columns (SEG order; the bottom-view default).
     * @param {boolean} mirrorY - True to mirror the rows (COM order).
     * @returns {Promise<void>}
     */
    async setFlip(mirrorX, mirrorY) {
        await this._ready;
        this._mx = mirrorX ? 1 : 0;
        this._my = mirrorY ? 1 : 0;
        await this._command(_CMD_MAPPING, this._mapping());
    }

    /** Scroll the image up by `line` rows (hardware scroll, wraps at 160 rows). @param {number} line - SL, 0 .. 159 - 2 * fixed lines. @returns {Promise<void>} */
    async setScrollLine(line) {
        await this._ready;
        await this._command(_CMD_SCROLL_LO | (line & 0x0F), _CMD_SCROLL_HI | ((line >> 4) & 0x0F));
    }

    /** Keep the first 2 * lines rows out of the scroll (soft-icon area). @param {number} lines - FL, 0..15. @returns {Promise<void>} */
    async setFixedLines(lines) {
        await this._ready;
        await this._command(_CMD_FIXED | (lines & 0x0F));
    }

    /** Select the LCD bias ratio. @param {number} ratio - 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12. @returns {Promise<void>} */
    async setBiasRatio(ratio) {
        await this._ready;
        await this._command(_CMD_BIAS | (ratio & 3));
    }

    /** Select the VBIAS temperature compensation. @param {number} curve - 0 = -0.05 %/degC, 1 = -0.10, 2 = -0.15, 3 = 0.00. @returns {Promise<void>} */
    async setTemperatureCompensation(curve) {
        await this._ready;
        await this._command(_CMD_TEMP | (curve & 3));
    }

    /** Select the line rate (On/Off mode). @param {number} rate - 0 = 5.9, 1 = 7.1, 2 = 8.2, 3 = 9.4 kilo-lines per second. @returns {Promise<void>} */
    async setLineRate(rate) {
        await this._ready;
        await this._command(_CMD_LINE_RATE | (rate & 3));
    }

    /** Internal 11x charge pump or an external VLCD supply. @param {boolean} internal @returns {Promise<void>} */
    async setPump(internal) {
        await this._ready;
        await this._command(_CMD_PUMP | (internal ? 3 : 0));
    }

    /** Tell the chip the panel capacitance. @param {boolean} high - True for 33..55 nF (reset), false for up to 33 nF. @returns {Promise<void>} */
    async setPanelLoading(high) {
        await this._ready;
        await this._command(_CMD_PANEL | (high ? 3 : 0));
    }

    /**
     * Scan only rows `start` .. `end` (the rest sleeps), or return to full scan.
     * @param {boolean} on - True to enable partial display.
     * @param {number} [start=0] - First scanned row (DST).
     * @param {number} [end] - Last scanned row (DEN); default height - 1.
     * @returns {Promise<void>}
     */
    async setPartialDisplay(on, start = 0, end = undefined) {
        await this._ready;
        if (on) {
            await this._command(_CMD_PART_START, start & 0xFF,
                _CMD_PART_END, (end === undefined ? this.height - 1 : end) & 0xFF,
                _CMD_PARTIAL | 3);
        } else {
            await this._command(_CMD_PARTIAL);
        }
    }

    /** Set N-line inversion (cross-talk tuning). @param {number} lines - 0 (off) .. 63. @param {boolean} [xor=false] @returns {Promise<void>} */
    async setNLineInversion(lines, xor = false) {
        await this._ready;
        await this._command(_CMD_NLINE, (xor ? 0x40 : 0) | (lines & 0x3F));
    }

    /** Set the isolation clock before (front) and after (back) the COM pulse, 0..15 each. @returns {Promise<void>} */
    async setIsolationClock(front, back) {
        await this._ready;
        await this._command(_CMD_ISOLATION, 0x13, front & 0x0F);
        await this._command(_CMD_ISOLATION, 0x14, back & 0x0F);
    }

    /** Select which of the four RAM screens (patterns) is displayed. @param {number} pattern - 0..3. @returns {Promise<void>} */
    async setDisplayPattern(pattern) {
        await this._ready;
        await this._command(_CMD_PATTERN | ((pattern & 3) << 1) | 1);
    }

    /** Select the RAM screen that `show()` / `showPage()` write to (PA[6:5]). @param {number} pattern - 0..3. */
    setWritePattern(pattern) {
        this._pattern = pattern & 3;
    }

    // ---- reads and raw access ----------------------------------------------------------------

    /**
     * Read the three status bytes (a NOP is sent first to restart the chip's status cycle).
     * @returns {Promise<Buffer>} Status1 (Ver, MX, MY, WA, DE, WS, MD, MS), Status2 (ID[1:0], PMO[5:0]),
     *   Status3 (product code 1 in bits 7..4, ESD flag in bit 0).
     */
    async readStatus() {
        await this._ready;
        return this._readStatus();
    }

    /**
     * Read `n` bytes of display RAM (the dummy byte after an address change is discarded).
     * @param {number} page - RAM page.
     * @param {number} column - Start column.
     * @param {number} n - Number of bytes.
     * @returns {Promise<Buffer>} The RAM bytes.
     */
    async readRam(page, column, n) {
        await this._ready;
        if (!this._bus.canRead) throw new Error('the SPI interface of the module is write-only');
        return this._retry(async () => {
            await this._bus.writeCommand(this._addressBytes(page, column));
            return this._bus.readRam(n);
        });
    }

    /** System Reset and reconfigure with the cached contrast and mapping (RAM is kept). @returns {Promise<void>} */
    async reset() {
        await this._ready;
        await this._resetChip();
        await this._configure();
        await this._enable();
        await this._waitDisplayEnabled(50);
    }

    /** Send raw bytes as one command transaction. @param {...number} bytes @returns {Promise<void>} */
    async sendCommand(...bytes) {
        await this._ready;
        await this._command(...bytes);
    }

    /** Send raw bytes to the display RAM (chunked) at the current RAM address. @param {Buffer|number[]} bytes @returns {Promise<void>} */
    async sendData(bytes) {
        await this._ready;
        await this._writeData(Buffer.from(bytes));
    }
}

/**
 * UC1611s over 4-wire SPI — minimal interface.
 *
 * Same framebuffer, drawing and initialization as UC1611sMinimal, but the module is driven through
 * a display connection (CS#, D/C# = CD, RES# = RST, optional). The module must be strapped for
 * 4-wire SPI (BM0 = D13 = VSS). SPI mode 0, up to 8 MHz. Every command byte, including the arguments
 * of two- and three-byte commands, is sent with D/C# low as its own CS window; RAM data goes out
 * with D/C# high. There is no read-back and no acknowledge: nothing is retried (`retryCount()` stays
 * 0), the driver waits a fixed 10 ms for the charge pump after reset or wake instead of polling, and
 * `readStatus()` / `readRam()` of the full class reject.
 *
 * @param {object} connection - DisplayConnection (4-wire, D/C# required, RES# optional, no BUSY).
 * @param {number} [width=240] - Columns, 1..240.
 * @param {number} [height=128] - Rows, a multiple of 8 and at most 128.
 * @param {object} [options] - Used by UC1611sSpiFull: `{ topView, contrast }`.
 */
class UC1611sSpiMinimal extends UC1611sMinimal {
    constructor(connection, width = WIDTH, height = HEIGHT, options = {}) {
        super(null, null, width, height, { ...options, bus: new SpiBus(connection) });
    }
}

/**
 * UC1611s over 4-wire SPI — full interface (see UC1611sFull; `readStatus()` and `readRam()` reject).
 *
 * @param {object} connection - DisplayConnection (4-wire, D/C# required, RES# optional, no BUSY).
 * @param {number} [width=240] - Columns, 1..240.
 * @param {number} [height=128] - Rows, a multiple of 8 and at most 128.
 * @param {boolean} [topView=false] - True for the 12:00 viewing direction (MY = 1, MX = 0).
 * @param {number} [contrast=0x8F] - Potentiometer PM 0..255 (the DOG example value).
 */
class UC1611sSpiFull extends UC1611sFull {
    constructor(connection, width = WIDTH, height = HEIGHT, topView = false, contrast = 0x8F) {
        super(null, null, width, height, topView, contrast, new SpiBus(connection));
    }
}

module.exports = { UC1611sMinimal, UC1611sFull, UC1611sSpiMinimal, UC1611sSpiFull, WIDTH, HEIGHT, I2C_CHUNK, SPI_CHUNK, MAX_ATTEMPTS, RETRY_DELAY_MS };
