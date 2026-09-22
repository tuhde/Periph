'use strict';

const { _hsvToRgb } = require('./_color');

/**
 * Shared minimal-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * Drives a chain of n pixels over a NeoPixel connection. Maintains an
 * internal buffer in wire order; fill() writes every pixel and transmits
 * immediately. Each pixel has four channels: red, green, blue, and white.
 * Subclasses fix channelOrder and resetBytes for their specific chip and
 * expose a public constructor(connection, n) that calls this one with
 * those fixed values.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n            - Number of pixels in the strip.
 * @param {number[]} channelOrder - [iR, iG, iB, iW]; wire[k] = [r,g,b,w][channelOrder[k]].
 * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
 */
class NeoPixelRGBWMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n            - Number of pixels in the strip.
     * @param {number[]} channelOrder - [iR, iG, iB, iW] wire channel order.
     * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
     */
    constructor(connection, n, channelOrder, resetBytes) {
        this._conn = connection;
        this._n = n;
        this._buf = Buffer.alloc(n * 4);
        this._channelOrder = channelOrder;
        this._reset = Buffer.alloc(resetBytes);
    }

    /**
     * Fill every pixel with one colour and send to the strip immediately.
     *
     * Clamps each channel to [0, 255]. Stores the four channels using
     * this chip's wire channel order, then appends the reset tail and
     * calls connection.write(). The white channel defaults to 0.
     *
     * @param {number} r       - Red channel (0–255).
     * @param {number} g       - Green channel (0–255).
     * @param {number} b       - Blue channel (0–255).
     * @param {number} [w=0]   - White channel (0–255).
     * @returns {Promise<void>}
     */
    async fill(r, g = 0, b = 0, w = 0) {
        const vals = [
            Math.max(0, Math.min(255, r | 0)),
            Math.max(0, Math.min(255, g | 0)),
            Math.max(0, Math.min(255, b | 0)),
            Math.max(0, Math.min(255, w | 0)),
        ];
        const [i0, i1, i2, i3] = this._channelOrder;
        const w0 = vals[i0], w1 = vals[i1], w2 = vals[i2], w3 = vals[i3];
        for (let i = 0; i < this._n; i++) {
            this._buf[i * 4]     = w0;
            this._buf[i * 4 + 1] = w1;
            this._buf[i * 4 + 2] = w2;
            this._buf[i * 4 + 3] = w3;
        }
        await this._conn.write(Buffer.concat([this._buf, this._reset]));
    }

    /**
     * Turn off all pixels (fill with all zeros and send).
     *
     * Equivalent to fill(0, 0, 0, 0).
     * @returns {Promise<void>}
     */
    async off() {
        await this.fill(0, 0, 0, 0);
    }
}

/**
 * Shared full-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill on top of NeoPixelRGBWMinimal.
 * Call set_pixel() / set_pixels() to update the buffer, then show() to
 * transmit; or use the inherited fill() for an immediate all-same-colour
 * update.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n            - Number of pixels in the strip.
 * @param {number[]} channelOrder - [iR, iG, iB, iW] wire channel order.
 * @param {number} resetBytes   - Extra zero-bytes appended after pixel data.
 */
class NeoPixelRGBWFull extends NeoPixelRGBWMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n            - Number of pixels in the strip.
     * @param {number[]} channelOrder - [iR, iG, iB, iW] wire channel order.
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
     * Call show() to transmit. White channel defaults to 0.
     *
     * @param {number} index   - Zero-based pixel index.
     * @param {number} r       - Red channel (0–255).
     * @param {number} g       - Green channel (0–255).
     * @param {number} b       - Blue channel (0–255).
     * @param {number} [w=0]   - White channel (0–255).
     */
    set_pixel(index, r, g, b, w = 0) {
        index = Math.max(0, Math.min(this._n - 1, index | 0));
        const vals = [
            Math.max(0, Math.min(255, r | 0)),
            Math.max(0, Math.min(255, g | 0)),
            Math.max(0, Math.min(255, b | 0)),
            Math.max(0, Math.min(255, w | 0)),
        ];
        const [i0, i1, i2, i3] = this._channelOrder;
        this._buf[index * 4]     = vals[i0];
        this._buf[index * 4 + 1] = vals[i1];
        this._buf[index * 4 + 2] = vals[i2];
        this._buf[index * 4 + 3] = vals[i3];
    }

    /**
     * Write a sequence of [r, g, b] or [r, g, b, w] arrays into the buffer
     * starting at pixel 0.
     *
     * Extra entries beyond the strip length are ignored. Call show() to transmit.
     * White channel defaults to 0 if arrays have only 3 elements.
     *
     * @param {Array<number[]>} colors - Array of [r, g, b] or [r, g, b, w] arrays.
     */
    set_pixels(colors) {
        const [i0, i1, i2, i3] = this._channelOrder;
        for (let i = 0; i < colors.length && i < this._n; i++) {
            const [r, g, b, w = 0] = colors[i];
            const vals = [
                Math.max(0, Math.min(255, r | 0)),
                Math.max(0, Math.min(255, g | 0)),
                Math.max(0, Math.min(255, b | 0)),
                Math.max(0, Math.min(255, w | 0)),
            ];
            this._buf[i * 4]     = vals[i0];
            this._buf[i * 4 + 1] = vals[i1];
            this._buf[i * 4 + 2] = vals[i2];
            this._buf[i * 4 + 3] = vals[i3];
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * Each channel is scaled: sent = stored * brightness / 255.
     * Appends this chip's reset-byte tail before transmission.
     * @returns {Promise<void>}
     */
    async show() {
        const bri = this._brightness;
        let pixels;
        if (bri === 255) {
            pixels = this._buf;
        } else {
            pixels = Buffer.alloc(this._buf.length);
            for (let i = 0; i < this._buf.length; i++) {
                pixels[i] = (this._buf[i] * bri / 255) | 0;
            }
        }
        await this._conn.write(Buffer.concat([pixels, this._reset]));
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
        const bytes = steps * 4;
        const head = Buffer.from(this._buf.slice(0, bytes));
        this._buf.copyWithin(0, bytes);
        head.copy(this._buf, this._buf.length - bytes);
    }

    /**
     * Fill every pixel with one HSV colour and send to the strip immediately.
     *
     * Converts HSV to RGB (white=0) then calls fill().
     *
     * @param {number} h - Hue (0.0–1.0).
     * @param {number} s - Saturation (0.0–1.0).
     * @param {number} v - Value/brightness (0.0–1.0).
     * @returns {Promise<void>}
     */
    async fill_hsv(h, s, v) {
        const [r, g, b] = _hsvToRgb(h, s, v);
        await this.fill(r, g, b, 0);
    }
}

module.exports = { NeoPixelRGBWMinimal, NeoPixelRGBWFull };
