'use strict';

const { _hsvToRgb } = require('./_color');

/**
 * Shared minimal-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.
 *
 * Drives a chain of n pixels over a NeoPixel connection. Maintains an
 * internal buffer in wire order; fill() writes every pixel and transmits
 * immediately. Subclasses fix channelOrder and resetBytes for their
 * specific chip and expose a public constructor(connection, n) that calls
 * this one with those fixed values.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n            - Number of pixels in the strip.
 * @param {number[]} channelOrder - [iR, iG, iB]; wire[k] = [r,g,b][channelOrder[k]].
 * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
 */
class NeoPixelRGBMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n            - Number of pixels in the strip.
     * @param {number[]} channelOrder - [iR, iG, iB] wire channel order.
     * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
     */
    constructor(connection, n, channelOrder, resetBytes) {
        this._conn = connection;
        this._n = n;
        this._buf = Buffer.alloc(n * 3);
        this._channelOrder = channelOrder;
        this._reset = Buffer.alloc(resetBytes);
    }

    /**
     * Fill every pixel with one colour and send to the strip immediately.
     *
     * Clamps each channel to [0, 255]. Stores the three channels using
     * this chip's wire channel order, then calls connection.write().
     *
     * @param {number} r - Red channel (0–255).
     * @param {number} g - Green channel (0–255).
     * @param {number} b - Blue channel (0–255).
     * @returns {Promise<void>}
     */
    async fill(r, g, b) {
        const vals = [
            Math.max(0, Math.min(255, r | 0)),
            Math.max(0, Math.min(255, g | 0)),
            Math.max(0, Math.min(255, b | 0)),
        ];
        const [i0, i1, i2] = this._channelOrder;
        const w0 = vals[i0], w1 = vals[i1], w2 = vals[i2];
        for (let i = 0; i < this._n; i++) {
            this._buf[i * 3]     = w0;
            this._buf[i * 3 + 1] = w1;
            this._buf[i * 3 + 2] = w2;
        }
        await this._conn.write(Buffer.concat([this._buf, this._reset]));
    }

    /**
     * Turn off all pixels (fill with black and send).
     *
     * Equivalent to fill(0, 0, 0).
     * @returns {Promise<void>}
     */
    async off() {
        await this.fill(0, 0, 0);
    }
}

/**
 * Shared full-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill on top of NeoPixelRGBMinimal.
 * Call set_pixel() / set_pixels() to update the buffer, then show() to
 * transmit; or use the inherited fill() for an immediate all-same-colour
 * update.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n            - Number of pixels in the strip.
 * @param {number[]} channelOrder - [iR, iG, iB] wire channel order.
 * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
 */
class NeoPixelRGBFull extends NeoPixelRGBMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n            - Number of pixels in the strip.
     * @param {number[]} channelOrder - [iR, iG, iB] wire channel order.
     * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
     */
    constructor(connection, n, channelOrder, resetBytes) {
        super(connection, n, channelOrder, resetBytes);
        this._brightness = 255;
    }

    /**
     * Global brightness scalar applied at show() time (0–255).
     * @type {number}
     */
    get brightness() { return this._brightness; }
    set brightness(value) {
        this._brightness = Math.max(0, Math.min(255, value | 0));
    }

    /**
     * Set one pixel in the buffer without sending.
     *
     * Index is clamped to [0, n-1]; each channel to [0, 255].
     * Call show() to transmit.
     *
     * @param {number} index - Zero-based pixel index.
     * @param {number} r     - Red channel (0–255).
     * @param {number} g     - Green channel (0–255).
     * @param {number} b     - Blue channel (0–255).
     */
    set_pixel(index, r, g, b) {
        index = Math.max(0, Math.min(this._n - 1, index | 0));
        const vals = [
            Math.max(0, Math.min(255, r | 0)),
            Math.max(0, Math.min(255, g | 0)),
            Math.max(0, Math.min(255, b | 0)),
        ];
        const [i0, i1, i2] = this._channelOrder;
        this._buf[index * 3]     = vals[i0];
        this._buf[index * 3 + 1] = vals[i1];
        this._buf[index * 3 + 2] = vals[i2];
    }

    /**
     * Write a sequence of [r, g, b] arrays into the buffer starting at pixel 0.
     *
     * Extra entries beyond the strip length are ignored. Call show() to transmit.
     *
     * @param {Array<number[]>} colors - Array of [r, g, b] arrays (0–255 each).
     */
    set_pixels(colors) {
        const [i0, i1, i2] = this._channelOrder;
        for (let i = 0; i < colors.length && i < this._n; i++) {
            const [r, g, b] = colors[i];
            const vals = [
                Math.max(0, Math.min(255, r | 0)),
                Math.max(0, Math.min(255, g | 0)),
                Math.max(0, Math.min(255, b | 0)),
            ];
            this._buf[i * 3]     = vals[i0];
            this._buf[i * 3 + 1] = vals[i1];
            this._buf[i * 3 + 2] = vals[i2];
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * Each channel is scaled: sent = stored * brightness / 255.
     * @returns {Promise<void>}
     */
    async show() {
        const bri = this._brightness;
        if (bri === 255) {
            await this._conn.write(Buffer.concat([this._buf, this._reset]));
            return;
        }
        const scaled = Buffer.alloc(this._buf.length);
        for (let i = 0; i < this._buf.length; i++) {
            scaled[i] = (this._buf[i] * bri / 255) | 0;
        }
        await this._conn.write(Buffer.concat([scaled, this._reset]));
    }

    /**
     * Shift the pixel buffer left by steps positions (wraps around).
     *
     * Does not transmit — call show() afterwards.
     *
     * @param {number} [steps=1] - Number of pixel positions to shift left.
     */
    rotate(steps = 1) {
        steps = ((steps % this._n) + this._n) % this._n;
        if (steps === 0) return;
        const bytes = steps * 3;
        const head = Buffer.from(this._buf.slice(0, bytes));
        this._buf.copyWithin(0, bytes);
        head.copy(this._buf, this._buf.length - bytes);
    }

    /**
     * Fill every pixel with one HSV colour and send to the strip immediately.
     *
     * Converts HSV to RGB then calls fill().
     *
     * @param {number} h - Hue (0.0–1.0).
     * @param {number} s - Saturation (0.0–1.0).
     * @param {number} v - Value/brightness (0.0–1.0).
     * @returns {Promise<void>}
     */
    async fill_hsv(h, s, v) {
        const [r, g, b] = _hsvToRgb(h, s, v);
        await this.fill(r, g, b);
    }
}

module.exports = { NeoPixelRGBMinimal, NeoPixelRGBFull };
