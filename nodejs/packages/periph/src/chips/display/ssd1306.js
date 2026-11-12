'use strict';

const { MonoFramebuffer, I2cOledBus, SpiOledBus, I2C_CHUNK, SPI_CHUNK } = require('./mono_oled');

const WIDTH = 128;
const HEIGHT = 64;

const _CMD_ADDR_MODE = 0x20;
const _CMD_COL_RANGE = 0x21;
const _CMD_PAGE_RANGE = 0x22;
const _CMD_START_LINE = 0x40;   // | A[5:0]
const _CMD_CONTRAST = 0x81;
const _CMD_CHARGE_PUMP = 0x8D;
const _CMD_SEG_REMAP = 0xA0;    // | ADC
const _CMD_SCROLL_AREA = 0xA3;
const _CMD_ALL_ON = 0xA4;       // | 1
const _CMD_INVERT = 0xA6;       // | 1
const _CMD_MULTIPLEX = 0xA8;
const _CMD_DISPLAY = 0xAE;      // | 1
const _CMD_PAGE = 0xB0;         // | A[2:0]
const _CMD_SCAN = 0xC0;         // | (scan << 3)
const _CMD_OFFSET = 0xD3;
const _CMD_CLOCK = 0xD5;
const _CMD_PRECHARGE = 0xD9;
const _CMD_COM = 0xDA;
const _CMD_VCOMH = 0xDB;

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * SSD1306 128 x 64 OLED controller-driver — minimal interface (I²C).
 *
 * Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I²C at 0x3C
 * (SA0 = low) or 0x3D (SA0 = high). The chip has an exact 128-column RAM, so there is no column offset.
 *
 * All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of
 * the page); `show()` sends it page by page (page addressing, the reset default). The initialization
 * follows the datasheet and the charge-pump application note: setup stream with the display off (charge
 * pump, page addressing), RAM cleared, display on + 100 ms. With `externalVcc = false` (default) the
 * internal charge pump makes the OLED supply VCC from VBAT. The constructor starts the initialisation;
 * every method that talks to the chip waits for it, and `ready()` resolves (or rejects) when done.
 *
 * @param {object} connection - I2CConnection at 0x3C or 0x3D.
 * @param {number} [width=128] - Visible columns, 1..128.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64 (up to 32 uses sequential COM wiring).
 * @param {boolean} [externalVcc=false] - True when the board supplies VCC (charge pump stays off).
 * @param {object} [bus] - Internal: transport override used by the SPI classes.
 */
class SSD1306Minimal {
    constructor(connection, width = 128, height = 64, externalVcc = false, bus = null) {
        if (!(width >= 1 && width <= WIDTH)) throw new RangeError('width must be 1..128');
        if (!(height >= 16 && height <= HEIGHT && height % 8 === 0)) throw new RangeError('height must be a multiple of 8, 16..64');
        this._bus = bus || new I2cOledBus(connection);
        this._canvas = new MonoFramebuffer(width, height);
        this.width = width;
        this.height = height;
        this._pages = height / 8;
        this._externalVcc = externalVcc;
        this._adc = 0;
        this._scan = 0;
        this._mode = 'page';
        this._scrolling = false;
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

    _addressBytes(page) {
        return Buffer.from([_CMD_PAGE | (page & 7), 0x00, 0x10]);
    }

    /** Datasheet power-on flow: reset, setup stream, RAM clear, display on. */
    async _initSequence() {
        await this._bus.hardwareReset();
        await sleepMs(1);
        await this._command(_CMD_DISPLAY,
            _CMD_CLOCK, 0x80,
            _CMD_MULTIPLEX, this.height - 1,
            _CMD_OFFSET, 0x00,
            _CMD_START_LINE,
            _CMD_CHARGE_PUMP, this._externalVcc ? 0x10 : 0x14,
            _CMD_ADDR_MODE, 0x02,
            _CMD_SEG_REMAP | this._adc,
            _CMD_SCAN | (this._scan << 3),
            _CMD_COM, this.height > 32 ? 0x12 : 0x02,
            _CMD_CONTRAST, 0x7F,
            _CMD_PRECHARGE, 0x22,
            _CMD_VCOMH, 0x20,
            _CMD_ALL_ON,
            _CMD_INVERT);
        const blank = Buffer.alloc(WIDTH);
        for (let page = 0; page < 8; page++) {
            await this._bus.writeCommands(this._addressBytes(page));
            await this._bus.writeData(blank);
        }
        await this._command(_CMD_DISPLAY | 1);
        await sleepMs(100);
    }

    _checkRamAccess() {
        if (this._scrolling) throw new Error('RAM access while a scroll is active; call scrollStop() first');
    }

    async _showPage(page) {
        await this._bus.writeCommands(this._addressBytes(page));
        await this._bus.writeData(this._canvas.page(page));
    }

    async _showAll() {
        this._checkRamAccess();
        if (this._mode === 'page') {
            for (let page = 0; page < this._pages; page++) await this._showPage(page);
            return;
        }
        await this._command(_CMD_COL_RANGE, 0, this.width - 1, _CMD_PAGE_RANGE, 0, this._pages - 1);
        await this._bus.writeData(this._mode === 'horizontal' ? this._canvas.data : this._canvas.transposed());
    }

    // ---- public API ------------------------------------------------------------------------

    /** Zero the framebuffer. Call `show()` to update the panel. */
    clear() {
        this._canvas.clear();
    }

    /**
     * Set or clear one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param {number} x - Column, 0 .. width - 1.
     * @param {number} y - Row, 0 .. height - 1.
     * @param {boolean} [on=true] - True to set the pixel, false to clear it.
     */
    setPixel(x, y, on = true) {
        this._canvas.setPixel(x, y, on);
    }

    /**
     * Write the whole framebuffer to the display RAM. Page addressing (default): per page a page +
     * column command stream, then width data bytes. Horizontal / vertical addressing: column and
     * page range commands, then one data stream.
     * @returns {Promise<void>}
     * @throws {Error} While a hardware scroll is active (call `scrollStop()` first).
     */
    async show() {
        await this._ready;
        await this._showAll();
    }
}

/**
 * SSD1306 OLED controller-driver — full interface (I²C).
 *
 * Extends SSD1306Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip, start
 * line, display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH
 * level, charge pump, addressing mode, hardware scrolling and raw commands. The SSD1306 cannot be
 * read over its serial interfaces.
 *
 * @param {object} connection - I2CConnection at 0x3C or 0x3D.
 * @param {number} [width=128] - Visible columns, 1..128.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {boolean} [externalVcc=false] - True when the board supplies VCC (charge pump off).
 * @param {object} [bus] - Internal: transport override used by the SPI classes.
 */
class SSD1306Full extends SSD1306Minimal {
    // ---- framebuffer -----------------------------------------------------------------------

    /**
     * Return a framebuffer pixel; out-of-range coordinates read as false.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {boolean} True if the pixel is set.
     */
    getPixel(x, y) { return this._canvas.getPixel(x, y); }

    /** Set (or clear) the whole framebuffer. @param {boolean} [on=true] */
    fill(on = true) { this._canvas.fill(on); }

    /**
     * Draw a Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {boolean} [on=true] - True to set pixels, false to clear them.
     */
    drawLine(x0, y0, x1, y1, on = true) { this._canvas.drawLine(x0, y0, x1, y1, on); }

    /**
     * Draw a rectangle outline or a filled rectangle into the framebuffer.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels.
     * @param {boolean} [on=true] - True to set pixels, false to clear them.
     * @param {boolean} [fill=false] - True for a filled rectangle.
     */
    drawRect(x, y, w, h, on = true, fill = false) { this._canvas.drawRect(x, y, w, h, on, fill); }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer; y must be a multiple of 8.
     * @param {number} x - Left column of the bitmap.
     * @param {number} y - Top row, a multiple of 8.
     * @param {Buffer|number[]} columns - Bitmap bytes, `heightPages` groups of columns page by page.
     * @param {number} [heightPages=1] - Number of 8-pixel-high rows of the bitmap.
     */
    blit(x, y, columns, heightPages = 1) { this._canvas.blit(x, y, columns, heightPages); }

    /**
     * Write one framebuffer page to the display RAM (cheaper partial update).
     * @param {number} page - Page 0 .. height / 8 - 1.
     * @returns {Promise<void>}
     * @throws {Error} While a hardware scroll is active (call `scrollStop()` first).
     */
    async showPage(page) {
        await this._ready;
        this._checkRamAccess();
        if (!(page >= 0 && page < this._pages)) return;
        if (this._mode === 'page') {
            await this._showPage(page);
            return;
        }
        await this._command(_CMD_COL_RANGE, 0, this.width - 1, _CMD_PAGE_RANGE, page, page);
        await this._bus.writeData(this._canvas.page(page));
    }

    // ---- display control -------------------------------------------------------------------

    /** Set the segment output current (contrast). @param {number} value - 0..255 (reset 0x7F). @returns {Promise<void>} */
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
     * @param {number} osc - Oscillator setting, 0..15 (8 = reset value).
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

    /** Set the VCOMH deselect level (only bits 6:4 used). @param {number} value - 0x00..0x70 (reset 0x20). @returns {Promise<void>} */
    async setVcomh(value) {
        await this._ready;
        await this._command(_CMD_VCOMH, value & 0x70);
    }

    /**
     * Enable (0x14) or disable (0x10) the internal charge pump; takes effect with the display on.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setChargePump(enabled) {
        await this._ready;
        await this._command(_CMD_CHARGE_PUMP, enabled ? 0x14 : 0x10);
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
     * Set up a continuous horizontal scroll, one column per step (start it with `scrollStart`). Do not call while scrolling.
     * @param {boolean} right - True to scroll right.
     * @param {number} startPage - 0..7.
     * @param {number} endPage - startPage..7.
     * @param {number} interval - Table index 0..7 = 5, 64, 128, 256, 3, 4, 25, 2 frames per step.
     * @returns {Promise<void>}
     */
    async setScrollHorizontal(right, startPage, endPage, interval) {
        await this._ready;
        await this._command(right ? 0x26 : 0x27, 0x00, startPage & 7, interval & 7, endPage & 7, 0x00, 0xFF);
    }

    /**
     * Set up a continuous vertical + horizontal (diagonal) scroll (start it with `scrollStart`).
     * @param {boolean} right - True to scroll right.
     * @param {number} startPage - 0..7.
     * @param {number} endPage - startPage..7.
     * @param {number} interval - Table index 0..7.
     * @param {number} rows - Vertical rows per step, 1..63.
     * @returns {Promise<void>}
     */
    async setScrollVertical(right, startPage, endPage, interval, rows) {
        await this._ready;
        await this._command(right ? 0x29 : 0x2A, 0x00, startPage & 7, interval & 7, endPage & 7, rows & 0x3F);
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

    /** Activate the configured scroll (0x2F); `show()` / `showPage()` are refused until `scrollStop()`. @returns {Promise<void>} */
    async scrollStart() {
        await this._ready;
        await this._command(0x2F);
        this._scrolling = true;
    }

    /** Deactivate scrolling (0x2E) and rewrite the RAM from the framebuffer, as the datasheet requires. @returns {Promise<void>} */
    async scrollStop() {
        await this._ready;
        await this._command(0x2E);
        this._scrolling = false;
        await this._showAll();
    }

    /** Send raw bytes as one command stream. @param {...number} bytes @returns {Promise<void>} */
    async sendCommand(...bytes) {
        await this._ready;
        await this._command(...bytes);
    }
}

/**
 * SSD1306 over 4-wire SPI — minimal interface.
 *
 * Same framebuffer, drawing and initialization as SSD1306Minimal, driven through a display connection
 * (CS, D/C, RES optional, no BUSY). The chip must be strapped for 4-wire SPI. SPI mode 0, up to 10 MHz
 * (4 MHz recommended). Every command byte is sent with D/C low, RAM data with D/C high.
 *
 * @param {object} connection - DisplayConnection.
 * @param {number} [width=128] - Visible columns, 1..128.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {boolean} [externalVcc=false] - True when the board supplies VCC (charge pump off).
 */
class SSD1306SpiMinimal extends SSD1306Minimal {
    constructor(connection, width = 128, height = 64, externalVcc = false) {
        super(null, width, height, externalVcc, new SpiOledBus(connection));
    }
}

/**
 * SSD1306 over 4-wire SPI — full interface (see SSD1306Full).
 *
 * @param {object} connection - DisplayConnection.
 * @param {number} [width=128] - Visible columns, 1..128.
 * @param {number} [height=64] - Rows, a multiple of 8, 16..64.
 * @param {boolean} [externalVcc=false] - True when the board supplies VCC (charge pump off).
 */
class SSD1306SpiFull extends SSD1306Full {
    constructor(connection, width = 128, height = 64, externalVcc = false) {
        super(null, width, height, externalVcc, new SpiOledBus(connection));
    }
}

module.exports = { SSD1306Minimal, SSD1306Full, SSD1306SpiMinimal, SSD1306SpiFull, WIDTH, HEIGHT, I2C_CHUNK, SPI_CHUNK };
