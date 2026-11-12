'use strict';

/**
 * Shared building blocks of the monochrome page-organised OLED controller drivers (SSD130x style):
 * a page-major 1 bpp framebuffer with drawing primitives, and the two transports (control-byte
 * framed I²C, D/C-pin framed 4-wire SPI).
 */

const I2C_CHUNK = 32;       // largest write in bytes including the control byte
const SPI_CHUNK = 128;

/** Page-major monochrome framebuffer (bit 0 of a byte = top pixel of its page) with drawing primitives. */
class MonoFramebuffer {
    /**
     * @param {number} width - Columns.
     * @param {number} height - Rows, a multiple of 8.
     */
    constructor(width, height) {
        this.width = width;
        this.height = height;
        this.pages = height / 8;
        this.data = Buffer.alloc(width * this.pages);
    }

    /** Zero the framebuffer. */
    clear() { this.data.fill(0); }

    /** Set (or clear) every pixel. @param {boolean} [on=true] */
    fill(on = true) { this.data.fill(on ? 0xFF : 0); }

    /**
     * Set or clear one pixel; out-of-range coordinates are ignored.
     * @param {number} x - Column.
     * @param {number} y - Row.
     * @param {boolean} [on=true]
     */
    setPixel(x, y, on = true) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) return;
        const i = (y >> 3) * this.width + x;
        if (on) this.data[i] |= 1 << (y & 7);
        else this.data[i] &= ~(1 << (y & 7)) & 0xFF;
    }

    /**
     * Return a pixel; out-of-range coordinates read as false.
     * @param {number} x
     * @param {number} y
     * @returns {boolean}
     */
    getPixel(x, y) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) return false;
        return (this.data[(y >> 3) * this.width + x] & (1 << (y & 7))) !== 0;
    }

    /** Bresenham line. @param {number} x0 @param {number} y0 @param {number} x1 @param {number} y1 @param {boolean} [on=true] */
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
     * Rectangle outline or filled rectangle.
     * @param {number} x
     * @param {number} y
     * @param {number} w
     * @param {number} h
     * @param {boolean} [on=true]
     * @param {boolean} [fill=false]
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
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top); clipped. `columns` holds `heightPages`
     * groups of columns, page by page: byte p * n + c is column c of page row p.
     * @param {number} x
     * @param {number} y - Top row, a multiple of 8.
     * @param {Buffer|number[]} columns
     * @param {number} [heightPages=1]
     * @throws {RangeError} If y is not a multiple of 8.
     */
    blit(x, y, columns, heightPages = 1) {
        if (y % 8 !== 0) throw new RangeError('y must be a multiple of 8');
        const n = Math.floor(columns.length / heightPages);
        for (let p = 0; p < heightPages; p++) {
            const page = y / 8 + p;
            if (page < 0 || page >= this.pages) continue;
            for (let c = 0; c < n; c++) {
                const xx = x + c;
                if (xx >= 0 && xx < this.width) this.data[page * this.width + xx] = columns[p * n + c];
            }
        }
    }

    /** The bytes of one page. @param {number} page @returns {Buffer} */
    page(page) {
        const start = page * this.width;
        return this.data.subarray(start, start + this.width);
    }

    /** The framebuffer column-major (for vertical addressing). @returns {Buffer} */
    transposed() {
        const out = Buffer.alloc(this.data.length);
        let n = 0;
        for (let x = 0; x < this.width; x++) {
            for (let p = 0; p < this.pages; p++) out[n++] = this.data[p * this.width + x];
        }
        return out;
    }
}

/** I²C transport: every transaction starts with a control byte (0x00 = commands, 0x40 = RAM data). */
class I2cOledBus {
    /** @param {object} connection - I2CConnection at 0x3C or 0x3D. */
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
class SpiOledBus {
    /** @param {object} connection - DisplayConnection. */
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

module.exports = { MonoFramebuffer, I2cOledBus, SpiOledBus, I2C_CHUNK, SPI_CHUNK };
