'use strict';

const BLACK = 0;
const WHITE = 1;
const RED = 2;

const MODE_KWR = 0;
const MODE_KW = 1;

const _CMD_PSR = 0x00;
const _CMD_PWR = 0x01;
const _CMD_POF = 0x02;
const _CMD_PON = 0x04;
const _CMD_BTST = 0x06;
const _CMD_DSLP = 0x07;
const _CMD_DTM1 = 0x10;
const _CMD_DRF = 0x12;
const _CMD_DTM2 = 0x13;
const _CMD_AUTO = 0x17;
const _CMD_LUTC = 0x20;
const _CMD_LUTWW = 0x21;
const _CMD_LUTKW = 0x22;
const _CMD_LUTWK = 0x23;
const _CMD_LUTKK = 0x24;
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

const MAX_WIDTH = 400;
const MAX_HEIGHT = 300;

const _ZEROS = Buffer.alloc(512);

// Frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet p. 24.
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

/**
 * UC8276C black/white/red e-paper controller — minimal interface.
 *
 * Drives a 400 x 300 (up to 400 source x 300 gate) white/black/red
 * electrophoretic panel such as the Waveshare 4.2inch e-Paper (B). The driver
 * keeps the picture in framebuffers and sends it on show(). Every update is
 * the datasheet sequence DTM1 + DTM2 -> PON -> DRF -> POF, waiting on BUSY_N
 * after each power/refresh step; show() resolves only when the whole refresh
 * is done (about 17 s on the reference panel).
 *
 * The connection is a DisplayConnection (see specs/transport_display.md) that
 * owns CS#, D/C#, RES# and BUSY_N. BUSY_N is active low.
 *
 * The constructor starts the initialisation sequence; every method waits for
 * it, and `ready()` resolves (or rejects, e.g. on a BUSY_N timeout) when it is
 * done.
 *
 * Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 = red)
 * holds for CDI.DDX = 01, which the driver writes explicitly at
 * initialisation instead of relying on the OTP default.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring is enough).
 * @param {object} [options]
 * @param {number} [options.width=400] - Pixels per row, a multiple of 8, 8..400.
 * @param {number} [options.height=300] - Rows, 1..300.
 * @param {number|null} [options.busyTimeoutMs=30000] - Limit for every BUSY_N wait in
 *   milliseconds, or null for no limit. The full refresh was measured at 16.8 s.
 * @throws {RangeError} If width or height is out of range.
 */
class UC8276CMinimal {
    constructor(connection, { width = MAX_WIDTH, height = MAX_HEIGHT, busyTimeoutMs = 30000 } = {}) {
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
        this._bw = Buffer.alloc(this._size, 0xFF);
        this._red = null;
        this._psr = 0x0F;
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
     * Fill the framebuffers with one colour. The panel is unchanged until show().
     * @param {number} [color=WHITE] - WHITE, BLACK or RED.
     */
    clear(color = WHITE) {
        this._bw.fill(color === WHITE ? 0xFF : 0x00);
        if (color === RED) {
            this._red = Buffer.alloc(this._size, 0xFF);
        } else if (this._red !== null) {
            this._red.fill(0);
        }
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * The red plane is allocated on the first RED pixel.
     * @param {number} x - Column, 0 <= x < width.
     * @param {number} y - Row, 0 <= y < height.
     * @param {number} [color=BLACK] - BLACK, WHITE or RED.
     */
    setPixel(x, y, color = BLACK) {
        if (x < 0 || y < 0 || x >= this._width || y >= this._height) return;
        const index = y * this._rowBytes + (x >> 3);
        const mask = 0x80 >> (x & 7);
        if (color === WHITE) this._bw[index] |= mask;
        else this._bw[index] &= ~mask & 0xFF;
        if (color === RED) {
            if (this._red === null) this._red = Buffer.alloc(this._size);
            this._red[index] |= mask;
        } else if (this._red !== null) {
            this._red[index] &= ~mask & 0xFF;
        }
    }

    /**
     * Write both planes to the controller and run a full display update.
     *
     * DTM1, DTM2, PON, wait BUSY_N, DRF, wait BUSY_N, POF, wait BUSY_N. A
     * sleeping controller is woken (hardware reset + initialisation) first.
     * @returns {Promise<void>}
     * @throws {Error} If a BUSY_N wait exceeds busyTimeoutMs.
     */
    async show() {
        await this._ready;
        if (this._asleep) await this._wake();
        await this._writePlanes();
        await this._powerCycleRefresh();
    }

    /**
     * Put the controller into deep sleep (DSLP with check code 0xA5).
     * Only a hardware reset restores communication; show() and Full.wake() do
     * that. The panel keeps its image.
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._ready;
        await this._conn.sendCommand(_CMD_DSLP, [0xA5]);
        this._asleep = true;
    }

    async _waitReady(timeoutMs = this._timeout) {
        if (!(await this._conn.waitBusy(false, timeoutMs))) {
            throw new Error(`UC8276C: BUSY_N did not go high within ${timeoutMs} ms`);
        }
    }

    async _initSequence() {
        if (typeof this._conn.ready === 'function') await this._conn.ready();
        await this._conn.reset();
        await this._waitReady();
        await this._conn.sendCommand(_CMD_PSR, [this._psr]);
        if (this._width !== MAX_WIDTH || this._height !== MAX_HEIGHT) {
            await this._conn.sendCommand(_CMD_TRES, [
                (this._width >> 8) & 1, this._width & 0xF8,
                (this._height >> 8) & 1, this._height & 0xFF,
            ]);
        }
        // VBD = 11, DDX = 01, CDI = 0111: the framebuffer polarity holds
        await this._conn.sendCommand(_CMD_CDI, [0xD7]);
    }

    async _wake() {
        this._asleep = false;
        await this._initSequence();
    }

    async _sendZeros(total) {
        while (total > 0) {
            const n = Math.min(total, _ZEROS.length);
            await this._conn.sendData(_ZEROS.subarray(0, n));
            total -= n;
        }
    }

    async _sendPlane(cmd, plane) {
        await this._conn.sendCommand(cmd);
        if (plane === null) await this._sendZeros(this._size);
        else await this._conn.sendData(plane);
    }

    async _writePlanes() {
        await this._sendPlane(_CMD_DTM1, this._bw);
        await this._sendPlane(_CMD_DTM2, this._red);
    }

    async _powerCycleRefresh() {
        await this._conn.sendCommand(_CMD_PON);
        await this._waitReady();
        await this._conn.sendCommand(_CMD_DRF);
        await this._waitReady();
        await this._conn.sendCommand(_CMD_POF);
        await this._waitReady();
    }
}

/**
 * UC8276C black/white/red e-paper controller — full interface.
 *
 * Extends UC8276CMinimal with drawing helpers, partial-window updates, the
 * auto sequence, pixel mode / scan direction, power and VCOM settings, frame
 * rate, user LUTs and the read commands (status, temperature, revision, VCOM,
 * low power, OTP).
 *
 * Register layouts follow the panel datasheet where it disagrees with the chip
 * datasheet (PWR, PLL, VCOM_DC); see specs/display/uc8276c.md. The read*
 * methods need a half-duplex wire mode (the controller drives the shared
 * DIN/DOUT line); with a SIMPLEX connection they reject.
 */
class UC8276CFull extends UC8276CMinimal {
    // -- framebuffer ------------------------------------------------------

    /**
     * Colour of one framebuffer pixel.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {number} BLACK, WHITE or RED (BLACK when out of range).
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= this._width || y >= this._height) return BLACK;
        const index = y * this._rowBytes + (x >> 3);
        const mask = 0x80 >> (x & 7);
        if (this._red !== null && (this._red[index] & mask)) return RED;
        return (this._bw[index] & mask) ? WHITE : BLACK;
    }

    /**
     * Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {number} [color=BLACK] - BLACK, WHITE or RED.
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
     * @param {number} [color=BLACK] - BLACK, WHITE or RED.
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
     * @param {number} [color=BLACK] - BLACK, WHITE or RED.
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
     * both planes, PON, DRF, POF, PTOUT. Saves SPI time; the waveform is the OTP
     * full waveform, so the panel still flashes.
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
        await this._conn.sendCommand(_CMD_PTIN);
        await this._conn.sendCommand(_CMD_PTL, [
            (x0 >> 8) & 1, x0 & 0xF8,
            (x1 >> 8) & 1, (x1 & 0xF8) | 0x07,
            (y0 >> 8) & 1, y0 & 0xFF,
            (y1 >> 8) & 1, y1 & 0xFF,
            0x00, // PT_SCAN = 0: scan only inside the window
        ]);
        const winBytes = (x1 - x0 + 1) / 8;
        for (const [cmd, plane] of [[_CMD_DTM1, this._bw], [_CMD_DTM2, this._red]]) {
            await this._conn.sendCommand(cmd);
            for (let row = y0; row <= y1; row++) {
                if (plane === null) {
                    await this._sendZeros(winBytes);
                } else {
                    const start = row * this._rowBytes + (x0 >> 3);
                    await this._conn.sendData(plane.subarray(start, start + winBytes));
                }
            }
        }
        try {
            await this._powerCycleRefresh();
        } finally {
            await this._conn.sendCommand(_CMD_PTOUT);
        }
    }

    /**
     * Full-frame update using the AUTO sequence instead of PON/DRF/POF.
     *
     * AUTO is listed only in the chip datasheet. On the reference panel
     * (Waveshare 4.2inch e-Paper (B)) it starts no refresh: the call returns
     * after the plane transfer and the panel keeps its picture. Use show()
     * unless your panel is known to accept it.
     * @param {boolean} [deepSleep=false] - true runs AUTO 0xA7 (also enters deep sleep afterwards).
     * @returns {Promise<void>}
     */
    async refreshAuto(deepSleep = false) {
        await this._ready;
        if (this._asleep) await this._wake();
        await this._writePlanes();
        await this._conn.sendCommand(_CMD_AUTO, [deepSleep ? 0xA7 : 0xA5]);
        await this._waitReady();
        if (deepSleep) this._asleep = true;
    }

    /**
     * Hardware reset and initialisation (exit from deep sleep). Framebuffers are kept.
     * @returns {Promise<void>}
     */
    async wake() {
        await this._ready;
        await this._wake();
    }

    /**
     * Wait for BUSY_N to go high.
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
     * Select the pixel mode.
     * @param {number} mode - MODE_KWR (black/white/red, default) or MODE_KW
     *   (black/white with NEW/OLD; needs KW LUTs in OTP).
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        if (mode === MODE_KW) this._psr |= 0x10;
        else this._psr &= ~0x10 & 0xFF;
        await this._writePsr();
    }

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
     * Source/gate power (PWR, panel-datasheet layout, internal DC-DC).
     *
     * Advanced: the values must match the panel vendor's; a wrong set can drive
     * the panel outside its rating. The driver does not write PWR by default.
     * @param {number} vdh - Positive source voltage, 2.4..11.0 V (0.2 V steps).
     * @param {number} vdl - Negative source voltage magnitude, 2.4..11.0 V.
     * @param {number} vdhr - Red source voltage, 2.4..11.0 V.
     * @param {number} [vgh=16] - Gate voltage magnitude: 16, 15, 14 or 13 V (VGL = -VGH).
     * @returns {Promise<void>}
     */
    async setPower(vdh, vdl, vdhr, vgh = 16.0) {
        await this._ready;
        const lv = Math.max(0, Math.min(3, Math.floor(16.0 - vgh + 0.5)));
        await this._conn.sendCommand(_CMD_PWR, [
            0x03, // VDS_EN | VDG_EN
            lv,   // VCOM_HV = 0, VGHL_LV[1:0]
            _code(vdh, 2.4, 0.2, 0x2B),
            _code(Math.abs(vdl), 2.4, 0.2, 0x2B),
            _code(vdhr, 2.4, 0.2, 0x2B),
        ]);
    }

    /**
     * VCOM_DC (VDCS): -0.10 .. -3.25 V in 0.05 V steps (panel layout).
     * @param {number} volts - VCOM voltage, e.g. the value printed on the panel label.
     * @returns {Promise<void>}
     */
    async setVcomDc(volts) {
        await this._ready;
        await this._conn.sendCommand(_CMD_VDCS, [_code(-0.10 - volts, 0.0, 0.05, 0x3F)]);
    }

    /**
     * Frame rate (PLL, M/N layout): the nearest rate of the panel's table.
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
     * @param {number} cdi - Interval, 0..15 (0b0111 = 10 hsync, the default).
     * @param {number} [border=3] - Border data selection VBD, 0..3 (3 = floating in KWR).
     * @param {number} [polarity=1] - Data polarity DDX, 0..3. The framebuffer mapping
     *   assumes 1; changing it is the caller's responsibility.
     * @returns {Promise<void>}
     */
    async setInterval(cdi, border = 3, polarity = 1) {
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
     * Load user waveform tables and select them (PSR.REG = 1).
     *
     * The LUT commands exist only in the chip datasheet; verify the panel
     * accepts them. Lengths: lutC 56, lutWW 42, lutKW 56, lutWK 56, lutKK 56
     * bytes. Any table may be null to leave it alone.
     * @param {Buffer|null} lutC - VCOM LUT.
     * @param {Buffer|null} lutWW - White-to-white LUT.
     * @param {Buffer|null} lutKW - Black-to-white (red in KWR) LUT.
     * @param {Buffer|null} lutWK - White-to-black LUT.
     * @param {Buffer|null} lutKK - Black-to-black LUT.
     * @returns {Promise<void>}
     */
    async writeLut(lutC, lutWW, lutKW, lutWK, lutKK) {
        await this._ready;
        const tables = [[_CMD_LUTC, lutC], [_CMD_LUTWW, lutWW], [_CMD_LUTKW, lutKW],
            [_CMD_LUTWK, lutWK], [_CMD_LUTKK, lutKK]];
        for (const [cmd, table] of tables) {
            if (table) await this._conn.sendCommand(cmd, table);
        }
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
     * Read FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.
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
     * Read REV. The panel datasheet gives a 1-byte LUT revision, the chip
     * datasheet 5 bytes (reserved, CHIP_REV, LUT_REV[7:0], [15:8], [23:16]).
     * @param {number} [length=1] - 1 (panel form) or 5 (chip form).
     * @returns {Promise<Array>} [chipRev, lutVersion], or [null, lutRev] for the 1-byte form.
     */
    async readRevision(length = 1) {
        const raw = await this._read(_CMD_REV, length);
        if (length === 1) return [null, raw[0]];
        return [raw[1], raw[2] | (raw[3] << 8) | (raw[4] << 16)];
    }

    /**
     * Read the measured VCOM (VV, panel layout).
     * @returns {Promise<number>} Volts, -0.10 V - 0.05 V x VV[5:0].
     */
    async readVcom() {
        return -0.10 - 0.05 * ((await this._read(_CMD_VV, 1))[0] & 0x3F);
    }

    /**
     * Read low-power detection (LPD, with LPMD = 0).
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
    UC8276CMinimal, UC8276CFull,
    BLACK, WHITE, RED, MODE_KWR, MODE_KW, MAX_WIDTH, MAX_HEIGHT,
};
