'use strict';

const WIDTH = 96;
const HEIGHT = 64;
const BUFFER_SIZE = WIDTH * HEIGHT * 2;

const BLACK = 0x0000;
const WHITE = 0xFFFF;
const RED = 0xF800;
const GREEN = 0x07E0;
const BLUE = 0x001F;
const YELLOW = 0xFFE0;
const CYAN = 0x07FF;
const MAGENTA = 0xF81F;

const MODE_NORMAL = 0xA4;
const MODE_ALL_ON = 0xA5;
const MODE_ALL_OFF = 0xA6;
const MODE_INVERSE = 0xA7;

const _CMD_COLUMN = 0x15;
const _CMD_ROW = 0x75;
const _CMD_CONTRAST_A = 0x81;
const _CMD_CONTRAST_B = 0x82;
const _CMD_CONTRAST_C = 0x83;
const _CMD_MASTER_CURRENT = 0x87;
const _CMD_PRECHARGE_A = 0x8A;
const _CMD_PRECHARGE_B = 0x8B;
const _CMD_PRECHARGE_C = 0x8C;
const _CMD_REMAP = 0xA0;
const _CMD_START_LINE = 0xA1;
const _CMD_OFFSET = 0xA2;
const _CMD_MODE_NORMAL = 0xA4;
const _CMD_MUX = 0xA8;
const _CMD_DIM_SETTING = 0xAB;
const _CMD_MASTER_CONFIG = 0xAD;
const _CMD_DISPLAY_DIM = 0xAC;
const _CMD_DISPLAY_OFF = 0xAE;
const _CMD_DISPLAY_ON = 0xAF;
const _CMD_POWER_SAVE = 0xB0;
const _CMD_PHASE = 0xB1;
const _CMD_CLOCK = 0xB3;
const _CMD_GRAY_LINEAR = 0xB9;
const _CMD_PRECHARGE_LEVEL = 0xBB;
const _CMD_VCOMH = 0xBE;
const _CMD_LINE = 0x21;
const _CMD_RECT = 0x22;
const _CMD_COPY = 0x23;
const _CMD_DIM = 0x24;
const _CMD_CLEAR = 0x25;
const _CMD_FILL = 0x26;
const _CMD_SCROLL_SETUP = 0x27;
const _CMD_SCROLL_OFF = 0x2E;
const _CMD_SCROLL_ON = 0x2F;

/**
 * Pack 8-bit R, G, B into an RGB565 value.
 * @param {number} r - Red 0..255.
 * @param {number} g - Green 0..255.
 * @param {number} b - Blue 0..255.
 * @returns {number} RGB565.
 */
function rgb(r, g, b) {
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | ((b & 0xFF) >> 3);
}

// RGB565 -> three 5/6/5-bit operands (C = red, B = green, A = blue).
function _color(c) {
    return [((c >> 11) & 0x1F) << 1, (c >> 5) & 0x3F, (c & 0x1F) << 1];
}

/**
 * SSD1331 96 x 64 RGB OLED controller — minimal interface.
 *
 * Drives the Waveshare 0.95inch RGB OLED over 4-wire SPI. The picture is kept
 * in a 96 x 64 RGB565 framebuffer (12 288 bytes, high byte first) and sent to
 * the display RAM by show().
 *
 * The connection is a DisplayConnection (see specs/transport_display.md) with
 * no BUSY pin. The SSD1331 takes command parameters with D/C# low, so the
 * driver sends every byte of a command as its own sendCommand(byte); only
 * pixel data uses sendData().
 *
 * The constructor starts the initialisation sequence; every method waits for
 * it, and `ready()` resolves (or rejects) when it is done.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring, no BUSY pin).
 */
class SSD1331Minimal {
    constructor(connection) {
        this._conn = connection;
        this._fb = Buffer.alloc(BUFFER_SIZE);
        this._remap = 0x72;
        this._ready = this._initSequence();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /** @returns {number} Panel width in pixels (96). */
    get width() { return WIDTH; }

    /** @returns {number} Panel height in pixels (64). */
    get height() { return HEIGHT; }

    /**
     * Resolves when initialisation has finished; rejects if it failed.
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    /**
     * Fill the framebuffer with one colour. The display is unchanged until show().
     * @param {number} [color=BLACK] - RGB565 colour.
     */
    clear(color = BLACK) {
        const hi = (color >> 8) & 0xFF;
        const lo = color & 0xFF;
        for (let i = 0; i < BUFFER_SIZE; i += 2) {
            this._fb[i] = hi;
            this._fb[i + 1] = lo;
        }
    }

    /**
     * Set one framebuffer pixel. Out-of-range coordinates are ignored.
     * @param {number} x - Column 0..95.
     * @param {number} y - Row 0..63.
     * @param {number} [color=WHITE] - RGB565 colour.
     */
    setPixel(x, y, color = WHITE) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return;
        const i = (y * WIDTH + x) * 2;
        this._fb[i] = (color >> 8) & 0xFF;
        this._fb[i + 1] = color & 0xFF;
    }

    /**
     * Send the whole framebuffer to the display RAM: window 0..95 x 0..63 and
     * 12 288 bytes in 192-byte (one row) transfers.
     * @returns {Promise<void>}
     */
    async show() {
        await this._ready;
        await this._window(0, 0, WIDTH - 1, HEIGHT - 1);
        for (let off = 0; off < BUFFER_SIZE; off += 2 * WIDTH) {
            await this._conn.sendData(this._fb.subarray(off, off + 2 * WIDTH));
        }
    }

    /** Switch the display on (0xAF); the RAM content is shown. @returns {Promise<void>} */
    async displayOn() { await this._ready; await this._cmd(_CMD_DISPLAY_ON); }

    /** Switch the display off (0xAE); the RAM content is kept. @returns {Promise<void>} */
    async displayOff() { await this._ready; await this._cmd(_CMD_DISPLAY_OFF); }

    // One command byte or parameter each, all with D/C# low.
    async _cmd(...values) {
        for (const v of values) await this._conn.sendCommand(v & 0xFF);
    }

    async _window(x0, y0, x1, y1) {
        await this._cmd(_CMD_COLUMN, x0, x1, _CMD_ROW, y0, y1);
    }

    async _initSequence() {
        if (typeof this._conn.ready === 'function') await this._conn.ready();
        await this._conn.reset();
        await this._conn.delayMs(100);
        await this._cmd(_CMD_DISPLAY_OFF);
        await this._cmd(_CMD_CONTRAST_A, 0xFF, _CMD_CONTRAST_B, 0xFF, _CMD_CONTRAST_C, 0xFF);
        await this._cmd(_CMD_MASTER_CURRENT, 0x06);
        await this._cmd(_CMD_PRECHARGE_A, 0x64, _CMD_PRECHARGE_B, 0x78, _CMD_PRECHARGE_C, 0x64);
        await this._cmd(_CMD_REMAP, this._remap);
        await this._cmd(_CMD_START_LINE, 0x00, _CMD_OFFSET, 0x00);
        await this._cmd(_CMD_MODE_NORMAL);
        await this._cmd(_CMD_MUX, 0x3F, _CMD_MASTER_CONFIG, 0x8E);
        await this._cmd(_CMD_POWER_SAVE, 0x0B, _CMD_PHASE, 0x31, _CMD_CLOCK, 0xF0);
        await this._cmd(_CMD_PRECHARGE_LEVEL, 0x3A, _CMD_VCOMH, 0x3E);
        await this._cmd(_CMD_SCROLL_OFF);
        await this._cmd(_CMD_CLEAR, 0x00, 0x00, 0x5F, 0x3F);
        await this._conn.delayMs(100);
        await this._cmd(_CMD_DISPLAY_ON);
    }
}

/**
 * SSD1331 96 x 64 RGB OLED controller — full interface.
 *
 * Extends SSD1331Minimal with software drawing into the framebuffer, partial
 * transfers, the controller's graphic acceleration commands (line, rectangle
 * with fill, copy, dim and clear window, scrolling — these act on the display
 * RAM only, not on the host framebuffer), and the electrical settings
 * (contrast, master current, flip, display mode, power save, dim mode, gray
 * table).
 */
class SSD1331Full extends SSD1331Minimal {
    // -- framebuffer ------------------------------------------------------

    /**
     * RGB565 colour of one framebuffer pixel.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {number} RGB565 (0 when out of range).
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return 0;
        const i = (y * WIDTH + x) * 2;
        return (this._fb[i] << 8) | this._fb[i + 1];
    }

    /**
     * Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {number} [color=WHITE] - RGB565 colour.
     */
    drawLine(x0, y0, x1, y1, color = WHITE) {
        const dx = Math.abs(x1 - x0);
        const dy = -Math.abs(y1 - y0);
        const sx = x0 < x1 ? 1 : -1;
        const sy = y0 < y1 ? 1 : -1;
        let err = dx + dy;
        for (;;) {
            this.setPixel(x0, y0, color);
            if (x0 === x1 && y0 === y1) break;
            const e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /**
     * Rectangle outline or filled rectangle into the framebuffer.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels.
     * @param {number} [color=WHITE] - RGB565 colour.
     * @param {boolean} [fill=false] - true fills the rectangle.
     */
    drawRect(x, y, w, h, color = WHITE, fill = false) {
        if (w <= 0 || h <= 0) return;
        for (let j = 0; j < h; j++) {
            for (let i = 0; i < w; i++) {
                if (fill || i === 0 || j === 0 || i === w - 1 || j === h - 1) this.setPixel(x + i, y + j, color);
            }
        }
    }

    /**
     * Copy an RGB565 bitmap into the framebuffer.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {ArrayLike<number>} pixels - Row-major w * h RGB565 values.
     * @param {number} w - Bitmap width in pixels.
     * @param {number} h - Bitmap height in pixels.
     */
    blit(x, y, pixels, w, h) {
        for (let j = 0; j < h; j++) {
            for (let i = 0; i < w; i++) this.setPixel(x + i, y + j, pixels[j * w + i]);
        }
    }

    /**
     * Send only a rectangle of the framebuffer (one window, row by row).
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels. The region is clipped to the panel.
     * @returns {Promise<void>}
     */
    async showRegion(x, y, w, h) {
        await this._ready;
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        w = Math.min(w, WIDTH - x);
        h = Math.min(h, HEIGHT - y);
        if (w <= 0 || h <= 0) return;
        await this._window(x, y, x + w - 1, y + h - 1);
        for (let j = 0; j < h; j++) {
            const start = ((y + j) * WIDTH + x) * 2;
            await this._conn.sendData(this._fb.subarray(start, start + w * 2));
        }
    }

    // -- graphic acceleration (display RAM only) ---------------------------

    /**
     * Hardware line (0x21) on the display RAM.
     * @param {number} x0 - Start column 0..95.
     * @param {number} y0 - Start row 0..63.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {number} color - RGB565 colour.
     * @returns {Promise<void>}
     */
    async hwLine(x0, y0, x1, y1, color) {
        await this._ready;
        await this._cmd(_CMD_LINE, x0, y0, x1, y1, ..._color(color));
    }

    /**
     * Hardware rectangle (0x26 fill enable + 0x22) on the display RAM.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width (>= 1).
     * @param {number} h - Height (>= 1).
     * @param {number} lineColor - Outline RGB565.
     * @param {number} [fillColor=0] - Fill RGB565 (used when fill is true).
     * @param {boolean} [fill=false] - Fill the interior.
     * @returns {Promise<void>}
     */
    async hwRect(x, y, w, h, lineColor, fillColor = 0, fill = false) {
        await this._ready;
        if (w <= 0 || h <= 0) return;
        await this._cmd(_CMD_FILL, fill ? 1 : 0);
        await this._cmd(_CMD_RECT, x, y, x + w - 1, y + h - 1, ..._color(lineColor), ..._color(fillColor));
    }

    /** Clear a window of the display RAM (0x25). @returns {Promise<void>} */
    async hwClear(x0, y0, x1, y1) { await this._ready; await this._cmd(_CMD_CLEAR, x0, y0, x1, y1); }

    /** Dim a window of the display RAM (0x24). @returns {Promise<void>} */
    async hwDim(x0, y0, x1, y1) { await this._ready; await this._cmd(_CMD_DIM, x0, y0, x1, y1); }

    /** Copy a window of the display RAM to (x, y) (0x23). @returns {Promise<void>} */
    async hwCopy(x0, y0, x1, y1, x, y) { await this._ready; await this._cmd(_CMD_COPY, x0, y0, x1, y1, x, y); }

    /**
     * Configure and start continuous scrolling (0x27, 0x2F).
     * @param {number} horizontal - Columns per step 0..95 (0 = none).
     * @param {number} startRow - First scrolled row.
     * @param {number} rows - Number of scrolled rows.
     * @param {number} vertical - Rows per step 0..63 (0 = none).
     * @param {number} [interval=0] - 0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step.
     * @returns {Promise<void>}
     */
    async scroll(horizontal, startRow, rows, vertical, interval = 0) {
        await this._ready;
        await this._cmd(_CMD_SCROLL_SETUP, horizontal, startRow, rows, vertical, interval & 3, _CMD_SCROLL_ON);
    }

    /** Stop scrolling (0x2E). The display RAM must be rewritten afterwards. @returns {Promise<void>} */
    async scrollStop() { await this._ready; await this._cmd(_CMD_SCROLL_OFF); }

    // -- settings ----------------------------------------------------------

    /** Contrast of colour channels A, B, C (0x81..0x83), 0..255. @returns {Promise<void>} */
    async setContrast(a, b, c) {
        await this._ready;
        await this._cmd(_CMD_CONTRAST_A, a, _CMD_CONTRAST_B, b, _CMD_CONTRAST_C, c);
    }

    /** Master current (0x87), n/16 for n = 1..16 (clamped). @returns {Promise<void>} */
    async setMasterCurrent(n) {
        await this._ready;
        await this._cmd(_CMD_MASTER_CURRENT, Math.min(16, Math.max(1, n)) - 1);
    }

    /**
     * Display mode.
     * @param {number} mode - MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF or MODE_INVERSE.
     * @returns {Promise<void>}
     */
    async setDisplayMode(mode) { await this._ready; await this._cmd(mode); }

    /** Raw remap and colour depth byte (0xA0). @returns {Promise<void>} */
    async setRemap(flags) {
        await this._ready;
        this._remap = flags & 0xFF;
        await this._cmd(_CMD_REMAP, this._remap);
    }

    /**
     * Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4).
     * @returns {Promise<void>}
     */
    async setFlip(horizontal, vertical) {
        let r = this._remap & ~0x12;
        if (horizontal) r |= 0x02;
        if (vertical) r |= 0x10;
        await this.setRemap(r);
    }

    /** Power save mode (0xB0): true = 0x1A enable, false = 0x0B disable. @returns {Promise<void>} */
    async setPowerSave(on) { await this._ready; await this._cmd(_CMD_POWER_SAVE, on ? 0x1A : 0x0B); }

    /** Dim mode (0xAB + 0xAC): contrasts A/B/C and pre-charge voltage 0..31. @returns {Promise<void>} */
    async dimMode(a, b, c, precharge) {
        await this._ready;
        await this._cmd(_CMD_DIM_SETTING, 0x00, a, b, c, precharge & 0x1F, _CMD_DISPLAY_DIM);
    }

    /** Restore the linear gray scale table (0xB9). @returns {Promise<void>} */
    async grayLinear() { await this._ready; await this._cmd(_CMD_GRAY_LINEAR); }

    /**
     * Send any command with parameters (all with D/C# low).
     * @param {number} command - Command byte.
     * @param {number[]} [params=[]] - Parameter bytes.
     * @returns {Promise<void>}
     */
    async sendCommand(command, params = []) { await this._ready; await this._cmd(command, ...params); }
}

module.exports = {
    SSD1331Minimal, SSD1331Full, rgb,
    WIDTH, HEIGHT, BUFFER_SIZE,
    BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA,
    MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF, MODE_INVERSE,
};
