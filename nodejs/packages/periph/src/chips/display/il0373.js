'use strict';

const BLACK = 0;
const WHITE = 1;

const _CMD_PSR = 0x00;
const _CMD_PWR = 0x01;
const _CMD_POF = 0x02;
const _CMD_PON = 0x04;
const _CMD_BTST = 0x06;
const _CMD_DSLP = 0x07;
const _CMD_DTM1 = 0x10;
const _CMD_DRF = 0x12;
const _CMD_DTM2 = 0x13;
const _CMD_LUTC = 0x20;
const _CMD_PLL = 0x30;
const _CMD_TSC = 0x40;
const _CMD_TSE = 0x41;
const _CMD_CDI = 0x50;
const _CMD_LPD = 0x51;
const _CMD_TRES = 0x61;
const _CMD_REV = 0x70;
const _CMD_FLG = 0x71;
const _CMD_VV = 0x81;
const _CMD_VDCS = 0x82;
const _CMD_PTL = 0x90;
const _CMD_PTIN = 0x91;
const _CMD_PTOUT = 0x92;
const _CMD_ROTP = 0xA2;

const MAX_WIDTH = 160;
const MAX_HEIGHT = 296;

// Frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet.
const _PLL_HZ = [
    [29, 14, 10, 7, 6, 5, 4],
    [57, 29, 19, 14, 11, 10, 8],
    [86, 43, 29, 21, 17, 14, 12],
    [114, 57, 38, 29, 23, 19, 16],
    [150, 72, 48, 36, 29, 24, 20],
    [171, 86, 57, 43, 34, 29, 24],
    [200, 100, 67, 50, 40, 33, 29],
];

function _code(value, base, step, maxCode) {
    const c = Math.floor((value - base) / step + 0.5);
    return Math.max(0, Math.min(maxCode, c));
}

function _invert(buf) {
    const out = Buffer.allocUnsafe(buf.length);
    for (let i = 0; i < buf.length; i++) out[i] = buf[i] ^ 0xFF;
    return out;
}

/**
 * IL0373 black/white e-paper controller — minimal interface.
 *
 * Drives a 104 x 212 (up to 160 source x 296 gate) black/white electrophoretic
 * panel such as the Waveshare 2.13inch e-Paper (D). The driver keeps the
 * picture in a framebuffer and sends it on show(). Every update follows the
 * vendor reference flow: PON (if the booster is off), DTM1 + DTM2, DRF and
 * wait on BUSY, CDI border floating, POF and wait; show() resolves only when
 * the whole refresh is done.
 *
 * The connection is a DisplayConnection (see specs/transport_display.md) that
 * owns CS#, D/C#, RES# and BUSY. BUSY is active low.
 *
 * The constructor starts the initialisation sequence; every method waits for
 * it, and `ready()` resolves (or rejects, e.g. on a BUSY timeout) when it is
 * done.
 *
 * Pixel polarity: bit 1 = white, bit 0 = black (CDI.DDX[0] = 1, KW mode). The
 * controller drives a pixel only when its NEW and OLD bits differ, so the
 * driver sends OLD = NOT NEW: every pixel is driven to its final state and a
 * refresh does not depend on the previous picture.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring is enough).
 * @param {object} [options]
 * @param {number} [options.width=104] - Pixels per row, a multiple of 8, 8..160.
 * @param {number} [options.height=212] - Rows, 1..296.
 * @param {number|null} [options.busyTimeoutMs=30000] - Limit for every BUSY wait in
 *   milliseconds, or null for no limit (the panel datasheet gives no refresh time).
 * @throws {RangeError} If width or height is out of range.
 */
class IL0373Minimal {
    constructor(connection, { width = 104, height = 212, busyTimeoutMs = 30000 } = {}) {
        if (!Number.isInteger(width) || width < 8 || width > MAX_WIDTH || width % 8 !== 0) {
            throw new RangeError(`width must be a multiple of 8 in 8..${MAX_WIDTH}`);
        }
        if (!Number.isInteger(height) || height < 1 || height > MAX_HEIGHT) {
            throw new RangeError(`height must be in 1..${MAX_HEIGHT}`);
        }
        this._conn = connection;
        this._width = width;
        this._height = height;
        this._timeout = busyTimeoutMs;
        this._rowBytes = width / 8;
        this._size = this._rowBytes * height;
        this._fb = Buffer.alloc(this._size, 0xFF);
        this._psr = 0x1F;
        this._powered = false;
        this._asleep = false;
        this._ready = this._initSequence();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /** @returns {number} Panel width in pixels. */
    get width() { return this._width; }

    /** @returns {number} Panel height in pixels. */
    get height() { return this._height; }

    /**
     * Resolves when initialisation has finished; rejects if it failed.
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until show().
     * @param {number} [color=WHITE] - WHITE or BLACK.
     */
    clear(color = WHITE) {
        this._fb.fill(color === WHITE ? 0xFF : 0x00);
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param {number} x - Column, 0 <= x < width.
     * @param {number} y - Row, 0 <= y < height.
     * @param {number} [color=BLACK] - BLACK or WHITE.
     */
    setPixel(x, y, color = BLACK) {
        if (x < 0 || y < 0 || x >= this._width || y >= this._height) return;
        const index = y * this._rowBytes + (x >> 3);
        const mask = 0x80 >> (x & 7);
        if (color === WHITE) this._fb[index] |= mask;
        else this._fb[index] &= ~mask & 0xFF;
    }

    /**
     * Write OLD and NEW data to the controller and run a full display update.
     *
     * PON (if the booster is off), DTM1 (OLD = NOT NEW), DTM2 (NEW), DRF, wait
     * BUSY, CDI 0xF7 (border floating), POF, wait BUSY. A sleeping controller
     * is woken (hardware reset + initialisation) first.
     * @returns {Promise<void>}
     * @throws {Error} If a BUSY wait exceeds busyTimeoutMs.
     */
    async show() {
        await this._ready;
        if (this._asleep) await this._wake();
        await this._ensurePower();
        await this._writePlanes();
        await this._refreshPowerOff();
    }

    /**
     * Put the controller into deep sleep (border floating, POF, DSLP 0xA5).
     * Only a hardware reset restores communication; show() and Full.wake() do
     * that. The panel keeps its image.
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._ready;
        if (this._powered) await this._powerOff();
        await this._conn.sendCommand(_CMD_DSLP, [0xA5]);
        this._asleep = true;
    }

    async _waitReady(timeoutMs = this._timeout) {
        if (!(await this._conn.waitBusy(false, timeoutMs))) {
            throw new Error(`IL0373: BUSY did not go high within ${timeoutMs} ms`);
        }
    }

    async _initSequence() {
        if (typeof this._conn.ready === 'function') await this._conn.ready();
        // Vendor OTP reference flow: reset, booster soft start, power on, panel
        // setting, resolution, VCOM and data interval.
        await this._conn.reset();
        await this._waitReady();
        await this._conn.sendCommand(_CMD_BTST, [0x17, 0x17, 0x17]);
        await this._conn.sendCommand(_CMD_PON);
        await this._waitReady();
        this._powered = true;
        await this._conn.sendCommand(_CMD_PSR, [this._psr]);
        await this._conn.sendCommand(_CMD_TRES, [
            this._width & 0xF8, (this._height >> 8) & 1, this._height & 0xFF,
        ]);
        await this._conn.sendCommand(_CMD_CDI, [0x97]);
    }

    async _wake() {
        this._asleep = false;
        this._powered = false;
        await this._initSequence();
    }

    async _ensurePower() {
        if (this._powered) return;
        await this._conn.sendCommand(_CMD_PON);
        await this._waitReady();
        this._powered = true;
        await this._conn.sendCommand(_CMD_CDI, [0x97]);
    }

    async _powerOff() {
        await this._conn.sendCommand(_CMD_CDI, [0xF7]);
        await this._conn.sendCommand(_CMD_POF);
        this._powered = false;
        await this._waitReady();
    }

    async _refreshPowerOff() {
        await this._conn.sendCommand(_CMD_DRF);
        await this._waitReady();
        await this._powerOff();
    }

    async _writePlanes() {
        await this._conn.sendCommand(_CMD_DTM1);
        await this._conn.sendData(_invert(this._fb));
        await this._conn.sendCommand(_CMD_DTM2);
        await this._conn.sendData(this._fb);
    }
}

/**
 * IL0373 black/white e-paper controller — full interface.
 *
 * Extends IL0373Minimal with drawing helpers, partial-window updates, scan
 * direction, power and VCOM settings, frame rate, user LUTs and the read
 * commands (status, temperature, revision, VCOM, low power, OTP).
 *
 * The read* methods need a half-duplex wire mode (the controller drives the
 * shared DIN/DOUT line); with a SIMPLEX connection they reject.
 */
class IL0373Full extends IL0373Minimal {
    // -- framebuffer ------------------------------------------------------

    /**
     * Colour of one framebuffer pixel.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {number} BLACK or WHITE (BLACK when out of range).
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= this._width || y >= this._height) return BLACK;
        const index = y * this._rowBytes + (x >> 3);
        const mask = 0x80 >> (x & 7);
        return (this._fb[index] & mask) ? WHITE : BLACK;
    }

    /**
     * Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {number} [color=BLACK] - BLACK or WHITE.
     */
    drawLine(x0, y0, x1, y1, color = BLACK) {
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
     * @param {number} [color=BLACK] - BLACK or WHITE.
     * @param {boolean} [fill=false] - true fills the rectangle.
     */
    drawRect(x, y, w, h, color = BLACK, fill = false) {
        if (w <= 0 || h <= 0) return;
        if (fill) {
            for (let j = 0; j < h; j++) {
                for (let i = 0; i < w; i++) this.setPixel(x + i, y + j, color);
            }
            return;
        }
        this.drawLine(x, y, x + w - 1, y, color);
        this.drawLine(x, y + h - 1, x + w - 1, y + h - 1, color);
        this.drawLine(x, y, x, y + h - 1, color);
        this.drawLine(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    /**
     * Copy a 1-bit-per-pixel bitmap into the framebuffer. Row-major, MSB-first,
     * rows padded to whole bytes. Set bits are drawn in color, clear bits leave
     * the framebuffer unchanged.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {Buffer|Uint8Array|number[]} data - Bitmap bytes.
     * @param {number} w - Bitmap width in pixels.
     * @param {number} h - Bitmap height in pixels.
     * @param {number} [color=BLACK] - BLACK or WHITE.
     */
    blit(x, y, data, w, h, color = BLACK) {
        if (w <= 0 || h <= 0) return;
        const rowBytes = Math.ceil(w / 8);
        for (let j = 0; j < h; j++) {
            for (let i = 0; i < w; i++) {
                if (data[j * rowBytes + (i >> 3)] & (0x80 >> (i & 7))) this.setPixel(x + i, y + j, color);
            }
        }
    }

    // -- updates ----------------------------------------------------------

    /**
     * Update only a window: write just its rows, then refresh.
     *
     * PTIN, PTL (window widened to whole 8-pixel columns), the window's bytes of
     * both planes, DRF, power off, PTOUT. Saves SPI time; the waveform is the
     * OTP full waveform, so the panel still flashes.
     * @param {number} x - Left column.
     * @param {number} y - Top row.
     * @param {number} w - Width in pixels.
     * @param {number} h - Height in pixels.
     * @returns {Promise<void>}
     * @throws {RangeError} If the window is empty or outside the panel.
     */
    async showRegion(x, y, w, h) {
        if (w <= 0 || h <= 0) throw new RangeError('empty window');
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x >= this._width || y >= this._height || w <= 0 || h <= 0) {
            throw new RangeError('window outside the panel');
        }
        const x0 = x & ~7;
        const x1 = Math.min((x + w - 1) | 7, this._width - 1);
        const y0 = y;
        const y1 = Math.min(y + h - 1, this._height - 1);
        await this._ready;
        if (this._asleep) await this._wake();
        await this._ensurePower();
        await this._conn.sendCommand(_CMD_PTIN);
        await this._conn.sendCommand(_CMD_PTL, [
            x0 & 0xF8, (x1 & 0xF8) | 0x07,
            (y0 >> 8) & 1, y0 & 0xFF,
            (y1 >> 8) & 1, y1 & 0xFF,
            0x00, // PT_SCAN = 0: scan only inside the window
        ]);
        const winBytes = (x1 - x0 + 1) / 8;
        for (const [cmd, invert] of [[_CMD_DTM1, true], [_CMD_DTM2, false]]) {
            await this._conn.sendCommand(cmd);
            for (let row = y0; row <= y1; row++) {
                const start = row * this._rowBytes + (x0 >> 3);
                const chunk = this._fb.subarray(start, start + winBytes);
                await this._conn.sendData(invert ? _invert(chunk) : chunk);
            }
        }
        try {
            await this._refreshPowerOff();
        } finally {
            await this._conn.sendCommand(_CMD_PTOUT);
        }
    }

    /**
     * Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept.
     * @returns {Promise<void>}
     */
    async wake() {
        await this._ready;
        await this._wake();
    }

    /**
     * Wait for BUSY to go high.
     * @param {number|null} [timeoutMs] - Limit in milliseconds, null for no limit;
     *   the constructor's busyTimeoutMs when omitted.
     * @returns {Promise<boolean>} true when ready, false on timeout.
     */
    async waitBusy(timeoutMs = this._timeout) {
        await this._ready;
        return this._conn.waitBusy(false, timeoutMs);
    }

    // -- settings ---------------------------------------------------------

    /**
     * Mirror the picture: horizontal clears SHL, vertical clears UD. The
     * framebuffer is unchanged.
     * @param {boolean} horizontal - Mirror left/right.
     * @param {boolean} vertical - Mirror top/bottom.
     * @returns {Promise<void>}
     */
    async setFlip(horizontal, vertical) {
        if (horizontal) this._psr &= ~0x04 & 0xFF;
        else this._psr |= 0x04;
        if (vertical) this._psr &= ~0x08 & 0xFF;
        else this._psr |= 0x08;
        await this._writePsr();
    }

    /**
     * Source/gate power (PWR, internal DC-DC).
     *
     * Advanced: the values must match the panel vendor's; a wrong set can drive
     * the panel outside its rating. The driver does not write PWR by default.
     * @param {number} vdh - Positive source voltage, 6.4..15.0 V (0.2 V steps).
     * @param {number} vdl - Negative source voltage magnitude, 6.4..15.0 V.
     * @param {number} [vgh=20] - Gate voltage magnitude: 20, 19, 18 or 17 V (VGL = -VGH).
     * @returns {Promise<void>}
     */
    async setPower(vdh, vdl, vgh = 20.0) {
        await this._ready;
        const lv = Math.max(0, Math.min(3, Math.floor(20.0 - vgh + 0.5)));
        await this._conn.sendCommand(_CMD_PWR, [
            0x03, // VDS_EN | VDG_EN
            lv,   // VCOM_HV = 0, VGHL_LV[1:0]
            _code(vdh, 6.4, 0.2, 0x2B),
            _code(Math.abs(vdl), 6.4, 0.2, 0x2B),
            0x03, // VDHR (red): unused in KW mode
        ]);
    }

    /**
     * VCOM_DC (VDCS): -0.10 .. -3.00 V in 0.05 V steps.
     * @param {number} volts - VCOM voltage.
     * @returns {Promise<void>}
     */
    async setVcomDc(volts) {
        await this._ready;
        await this._conn.sendCommand(_CMD_VDCS, [_code(-0.10 - volts, 0.0, 0.05, 0x3A)]);
    }

    /**
     * Frame rate (PLL, M/N layout): the nearest rate of the table.
     * @param {number} hz - Requested rate, 4..200 Hz.
     * @returns {Promise<void>}
     */
    async setFrameRate(hz) {
        await this._ready;
        let best = 0x3C; // M = 7, N = 4 (50 Hz)
        let bestDiff = 10000;
        for (let m = 1; m <= 7; m++) {
            for (let n = 1; n <= 7; n++) {
                const diff = Math.abs(_PLL_HZ[m - 1][n - 1] - hz);
                if (diff < bestDiff) { bestDiff = diff; best = (m << 3) | n; }
            }
        }
        await this._conn.sendCommand(_CMD_PLL, [best]);
    }

    /**
     * Booster soft start (BTST).
     * @param {number} a - Phase A byte.
     * @param {number} b - Phase B byte.
     * @param {number} c - Phase C byte.
     * @returns {Promise<void>}
     */
    async setSoftStart(a, b, c) {
        await this._ready;
        await this._conn.sendCommand(_CMD_BTST, [a, b, c]);
    }

    /**
     * VCOM and data interval (CDI).
     * @param {number} cdi - Interval, 0..15 (0b0111 = 10 hsync).
     * @param {number} [border=2] - Border data selection VBD, 0..3 (2 as at initialisation).
     * @param {number} [polarity=1] - Data polarity DDX, 0..3. The framebuffer mapping
     *   assumes 1; changing it is the caller's responsibility.
     * @returns {Promise<void>}
     */
    async setInterval(cdi, border = 2, polarity = 1) {
        await this._ready;
        await this._conn.sendCommand(_CMD_CDI, [((border & 3) << 6) | ((polarity & 3) << 4) | (cdi & 0x0F)]);
    }

    /**
     * Temperature sensor source (TSE): internal (default) or external LM75.
     * @param {boolean} external - true selects the external sensor.
     * @param {number} [offset=0] - Temperature offset, -8..+7 degrees Celsius.
     * @returns {Promise<void>}
     */
    async setTemperatureSensor(external, offset = 0) {
        await this._ready;
        await this._conn.sendCommand(_CMD_TSE, [(external ? 0x80 : 0x00) | (offset & 0x0F)]);
    }

    /**
     * Load one user waveform table and select register LUTs (PSR.REG = 1).
     *
     * The chip datasheet gives 44 (VCOM) / 42 bytes per table, the panel
     * datasheet 60 / 36 / 60 / 36 / 36; the bytes are sent as given.
     * @param {number} table - 0 = VCOM, 1 = W-W, 2 = B-W, 3 = W-B, 4 = B-B (commands 0x20..0x24).
     * @param {Buffer|number[]} data - Table bytes.
     * @returns {Promise<void>}
     * @throws {RangeError} If table is not 0..4.
     */
    async writeLut(table, data) {
        if (!Number.isInteger(table) || table < 0 || table > 4) throw new RangeError('table must be 0..4');
        await this._ready;
        await this._conn.sendCommand(_CMD_LUTC + table, data);
        this._psr |= 0x20;
        await this._writePsr();
    }

    /**
     * Send a raw command and parameters (escape hatch).
     * @param {number} command - Command byte.
     * @param {Buffer|number[]} [data=[]] - Parameter bytes.
     * @returns {Promise<void>}
     */
    async sendCommand(command, data = []) {
        await this._ready;
        await this._conn.sendCommand(command, data);
    }

    // -- reads (half-duplex wiring only) ----------------------------------

    /**
     * Read FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY.
     * @returns {Promise<number>} The status byte.
     */
    async readStatus() {
        return (await this._read(_CMD_FLG, 1))[0];
    }

    /**
     * Read the internal temperature sensor (TSC).
     * @returns {Promise<number>} Signed degrees Celsius.
     */
    async readTemperature() {
        const v = (await this._read(_CMD_TSC, 2))[0];
        return v > 127 ? v - 256 : v;
    }

    /**
     * Read REV (LUT_REV, one byte).
     * @returns {Promise<number>} The LUT revision.
     */
    async readRevision() {
        return (await this._read(_CMD_REV, 1))[0];
    }

    /**
     * Read the measured VCOM (VV).
     * @returns {Promise<number>} Volts, -0.10 V - 0.05 V x VV[5:0].
     */
    async readVcom() {
        return -0.10 - 0.05 * ((await this._read(_CMD_VV, 1))[0] & 0x3F);
    }

    /**
     * Read low-power detection (LPD).
     * @returns {Promise<boolean>} true when the supply is low.
     */
    async readLowPower() {
        return ((await this._read(_CMD_LPD, 1))[0] & 1) === 0;
    }

    /**
     * Read OTP bytes from address 0 (ROTP; the dummy byte is dropped).
     * @param {number} length - Number of bytes.
     * @returns {Promise<Buffer>} The OTP contents.
     */
    async readOtp(length) {
        await this._ready;
        await this._conn.sendCommand(_CMD_ROTP);
        await this._conn.readData(1);
        return this._conn.readData(length);
    }

    // -- internals --------------------------------------------------------

    async _writePsr() {
        await this._ready;
        await this._conn.sendCommand(_CMD_PSR, [this._psr]);
    }

    async _read(cmd, length) {
        await this._ready;
        await this._conn.sendCommand(cmd);
        return this._conn.readData(length);
    }
}

module.exports = {
    IL0373Minimal, IL0373Full,
    BLACK, WHITE, MAX_WIDTH, MAX_HEIGHT,
};
