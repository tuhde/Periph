'use strict';

const BLACK = 0;
const WHITE = 1;
const GREEN = 2;
const BLUE = 3;
const RED = 4;
const YELLOW = 5;
const ORANGE = 6;
const CLEAN = 7;

const WIDTH = 600;
const HEIGHT = 448;
const FRAME_BYTES = WIDTH * HEIGHT / 2;

const _CMD_PSR = 0x00;
const _CMD_PWR = 0x01;
const _CMD_PFS = 0x03;
const _CMD_PON = 0x04;
const _CMD_POF = 0x02;
const _CMD_BTST = 0x06;
const _CMD_DSLP = 0x07;
const _CMD_DTM1 = 0x10;
const _CMD_DRF = 0x12;
const _CMD_PLL = 0x30;
const _CMD_TSC = 0x40;
const _CMD_TSE = 0x41;
const _CMD_CDI = 0x50;
const _CMD_LPD = 0x51;
const _CMD_TCON = 0x60;
const _CMD_TRES = 0x61;
const _CMD_PWS = 0xE3;
const _CMD_FLG = 0x71;
const _CMD_VV = 0x81;
const _CMD_VDCS = 0x82;

/**
 * UC8159 seven-colour e-paper controller — minimal interface.
 *
 * Drives a 600 x 448 ACeP seven-colour electrophoretic panel such as the
 * Waveshare 5.65inch e-Paper (F). The driver keeps the picture in a
 * framebuffer in wire format (4 bits per pixel, 134 400 bytes) and sends it on
 * show(). Every update is DTM1, PON, wait BUSY, DRF, wait BUSY, POF, wait BUSY;
 * show() resolves only when the whole refresh (about 30 s) is done.
 *
 * The connection is a DisplayConnection (see specs/transport_display.md) that
 * owns CS#, D/C#, RES# and BUSY. BUSY is active low.
 *
 * The constructor starts the initialisation sequence; every method waits for
 * it, and `ready()` resolves (or rejects, e.g. on a BUSY timeout) when it is
 * done.
 *
 * @param {object} connection - A DisplayConnection (SIMPLEX wiring is enough).
 * @param {object} [options]
 * @param {number|null} [options.busyTimeoutMs=60000] - Limit for every BUSY wait in
 *   milliseconds, or null for no limit.
 */
class UC8159Minimal {
    constructor(connection, { busyTimeoutMs = 60000 } = {}) {
        this._conn = connection;
        this._timeout = busyTimeoutMs;
        this._fb = Buffer.alloc(FRAME_BYTES, 0x11);
        this._psr = 0xEF;
        this._cdi = 0x37;
        this._asleep = false;
        this._ready = this._initSequence();
        this._ready.catch(() => {}); // surfaced through ready() and the first awaited call
    }

    /** @returns {number} Panel width in pixels (600). */
    get width() { return WIDTH; }

    /** @returns {number} Panel height in pixels (448). */
    get height() { return HEIGHT; }

    /**
     * Resolves when initialisation has finished; rejects if it failed.
     * @returns {Promise<void>}
     */
    ready() { return this._ready; }

    /**
     * Fill the framebuffer with one colour. The panel is unchanged until show().
     * @param {number} [color=WHITE] - Colour code 0..7.
     */
    clear(color = WHITE) {
        const c = color & 7;
        this._fb.fill((c << 4) | c);
    }

    /**
     * Set one pixel in the framebuffer. Out-of-range coordinates are ignored.
     * @param {number} x - Column, 0 <= x < 600.
     * @param {number} y - Row, 0 <= y < 448.
     * @param {number} [color=BLACK] - Colour code 0..7.
     */
    setPixel(x, y, color = BLACK) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return;
        const i = (y * WIDTH + x) >> 1;
        const c = color & 7;
        if (x & 1) this._fb[i] = (this._fb[i] & 0xF0) | c;
        else this._fb[i] = (this._fb[i] & 0x0F) | (c << 4);
    }

    /**
     * Send the frame and run a full display update (about 30 s): DTM1 + 134 400
     * bytes, PON, wait BUSY, DRF, wait BUSY, POF, wait BUSY. A sleeping
     * controller is woken (hardware reset + initialisation) first.
     * @returns {Promise<void>}
     * @throws {Error} If a BUSY wait exceeds busyTimeoutMs.
     */
    async show() {
        await this._ready;
        if (this._asleep) await this._wake();
        await this._conn.sendCommand(_CMD_DTM1);
        for (let off = 0; off < FRAME_BYTES; off += 4096) {
            await this._conn.sendData(this._fb.subarray(off, Math.min(off + 4096, FRAME_BYTES)));
        }
        await this._conn.sendCommand(_CMD_PON);
        await this._waitReady();
        await this._conn.sendCommand(_CMD_DRF);
        await this._waitReady();
        await this._conn.sendCommand(_CMD_POF);
        await this._waitReady();
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
            throw new Error(`UC8159: BUSY did not go high within ${timeoutMs} ms`);
        }
    }

    async _initSequence() {
        if (typeof this._conn.ready === 'function') await this._conn.ready();
        const c = this._conn;
        // The vendor demo's initialisation commands (sigrok/tests/uc8159).
        await c.reset();
        await this._waitReady();
        await c.sendCommand(_CMD_PSR, [this._psr, 0x08]);
        await c.sendCommand(_CMD_PWR, [0x37, 0x00, 0x23, 0x23]);
        await c.sendCommand(_CMD_PFS, [0x00]);
        await c.sendCommand(_CMD_BTST, [0xC7, 0xC7, 0x1D]);
        await c.sendCommand(_CMD_PLL, [0x3C]);
        await c.sendCommand(_CMD_TSE, [0x00]);
        await c.sendCommand(_CMD_CDI, [this._cdi]);
        await c.sendCommand(_CMD_TCON, [0x22]);
        await c.sendCommand(_CMD_TRES, [0x02, 0x58, 0x01, 0xC0]); // 600 x 448
        await c.sendCommand(_CMD_PWS, [0xAA]);
    }

    async _wake() {
        this._asleep = false;
        await this._initSequence();
    }
}

/**
 * UC8159 seven-colour e-paper controller — full interface.
 *
 * Extends UC8159Minimal with drawing helpers, scan direction, frame rate,
 * border/data interval, temperature-sensor source, VCOM_DC and the read
 * commands (status, temperature, low power, VCOM). The read* methods need a
 * half-duplex wire mode; with a SIMPLEX connection they reject.
 */
class UC8159Full extends UC8159Minimal {
    /**
     * Colour code (0..7) of one framebuffer pixel.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @returns {number} Colour code (BLACK when out of range).
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return BLACK;
        const b = this._fb[(y * WIDTH + x) >> 1];
        return (x & 1) ? (b & 7) : ((b >> 4) & 7);
    }

    /**
     * Bresenham line into the framebuffer.
     * @param {number} x0 - Start column.
     * @param {number} y0 - Start row.
     * @param {number} x1 - End column.
     * @param {number} y1 - End row.
     * @param {number} [color=BLACK] - Colour code 0..7.
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
     * @param {number} [color=BLACK] - Colour code 0..7.
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
     * @param {number} [color=BLACK] - Colour code 0..7.
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
        await this._ready;
        await this._conn.sendCommand(_CMD_PSR, [this._psr, 0x08]);
    }

    /**
     * Frame rate (PLL raw code): 0x3C = 50 Hz (default), 0x3A = 100 Hz,
     * 0x39 = 200 Hz, 0x00..0x0F = 12.5 .. 200 Hz.
     * @param {number} code - PLL code.
     * @returns {Promise<void>}
     */
    async setFrameRate(code) {
        await this._ready;
        await this._conn.sendCommand(_CMD_PLL, [code & 0xFF]);
    }

    /**
     * VCOM and data interval (CDI).
     * @param {number} cdi - Interval, 0..15 (0b0111 = 10 hsync).
     * @param {number} [border=WHITE] - Border colour code 0..7.
     * @param {number} [polarity=1] - DDX, 1 = default (the colour codes assume 1).
     * @returns {Promise<void>}
     */
    async setInterval(cdi, border = WHITE, polarity = 1) {
        this._cdi = ((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F);
        await this._ready;
        await this._conn.sendCommand(_CMD_CDI, [this._cdi]);
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
     * VCOM_DC (VDCS): -0.10 .. -4.0 V in 0.05 V steps.
     * @param {number} volts - VCOM voltage.
     * @returns {Promise<void>}
     */
    async setVcomDc(volts) {
        const code = Math.max(2, Math.min(0x50, Math.floor((-0.10 - volts) / 0.05 + 2.5)));
        await this._ready;
        await this._conn.sendCommand(_CMD_VDCS, [code]);
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

    /**
     * Read FLG: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.
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
     * Read low-power detection (LPD).
     * @returns {Promise<boolean>} true when VDD is below 2.5 V.
     */
    async readLowPower() {
        return ((await this._read(_CMD_LPD, 1))[0] & 1) === 0;
    }

    /**
     * Read the measured VCOM (VV).
     * @returns {Promise<number>} Volts, -0.10 V - 0.05 V x (VV - 2).
     */
    async readVcom() {
        const c = (await this._read(_CMD_VV, 1))[0] & 0x7F;
        return -0.10 - 0.05 * (c > 2 ? c - 2 : 0);
    }

    async _read(cmd, length) {
        await this._ready;
        await this._conn.sendCommand(cmd);
        return this._conn.readData(length);
    }
}

module.exports = {
    UC8159Minimal, UC8159Full,
    BLACK, WHITE, GREEN, BLUE, RED, YELLOW, ORANGE, CLEAN, WIDTH, HEIGHT,
};
