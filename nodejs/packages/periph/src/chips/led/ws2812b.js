'use strict';

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n WS2812B pixels over a NeoPixel transport.
 * Maintains an internal GRB buffer; fill() writes every pixel and
 * transmits immediately.
 *
 * @param {object} transport - Configured NeoPixel transport.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2812BMinimal {
    /**
     * @param {object} transport - Configured NeoPixel transport.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(transport, n) {
        this._transport = transport;
        this._n = n;
        this._buf = Buffer.alloc(n * 3);
    }

    /**
     * Fill every pixel with one colour and send to the strip immediately.
     *
     * Clamps each channel to [0, 255]. Stores G, R, B (GRB wire order),
     * then calls transport.write().
     *
     * @param {number} r - Red channel (0–255).
     * @param {number} g - Green channel (0–255).
     * @param {number} b - Blue channel (0–255).
     */
    fill(r, g, b) {
        r = Math.max(0, Math.min(255, r | 0));
        g = Math.max(0, Math.min(255, g | 0));
        b = Math.max(0, Math.min(255, b | 0));
        for (let i = 0; i < this._n; i++) {
            this._buf[i * 3]     = g;
            this._buf[i * 3 + 1] = r;
            this._buf[i * 3 + 2] = b;
        }
        this._transport.write(this._buf);
    }

    /**
     * Turn off all pixels (fill with black and send).
     *
     * Equivalent to fill(0, 0, 0).
     */
    off() {
        this.fill(0, 0, 0);
    }
}

/**
 * WS2812B full interface — extends WS2812BMinimal with per-pixel control.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
 * to update the buffer, then show() to transmit; or use the inherited
 * fill() for an immediate all-same-colour update.
 *
 * @param {object} transport - Configured NeoPixel transport.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2812BFull extends WS2812BMinimal {
    /**
     * @param {object} transport - Configured NeoPixel transport.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(transport, n) {
        super(transport, n);
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
        this._buf[index * 3]     = Math.max(0, Math.min(255, g | 0));
        this._buf[index * 3 + 1] = Math.max(0, Math.min(255, r | 0));
        this._buf[index * 3 + 2] = Math.max(0, Math.min(255, b | 0));
    }

    /**
     * Write a sequence of [r, g, b] arrays into the buffer starting at pixel 0.
     *
     * Extra entries beyond the strip length are ignored. Call show() to transmit.
     *
     * @param {Array<number[]>} colors - Array of [r, g, b] arrays (0–255 each).
     */
    set_pixels(colors) {
        for (let i = 0; i < colors.length && i < this._n; i++) {
            const [r, g, b] = colors[i];
            this._buf[i * 3]     = Math.max(0, Math.min(255, g | 0));
            this._buf[i * 3 + 1] = Math.max(0, Math.min(255, r | 0));
            this._buf[i * 3 + 2] = Math.max(0, Math.min(255, b | 0));
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * Each channel is scaled: sent = stored * brightness / 255.
     */
    show() {
        const bri = this._brightness;
        if (bri === 255) {
            this._transport.write(this._buf);
            return;
        }
        const scaled = Buffer.alloc(this._buf.length);
        for (let i = 0; i < this._buf.length; i++) {
            scaled[i] = (this._buf[i] * bri / 255) | 0;
        }
        this._transport.write(scaled);
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
     */
    fill_hsv(h, s, v) {
        const [r, g, b] = _hsvToRgb(h, s, v);
        this.fill(r, g, b);
    }
}

function _hsvToRgb(h, s, v) {
    if (s === 0) {
        const c = Math.round(v * 255);
        return [c, c, c];
    }
    const i = Math.floor(h * 6);
    const f = h * 6 - i;
    const p = Math.round(v * (1 - s) * 255);
    const q = Math.round(v * (1 - s * f) * 255);
    const t = Math.round(v * (1 - s * (1 - f)) * 255);
    const vv = Math.round(v * 255);
    switch (i % 6) {
        case 0: return [vv, t, p];
        case 1: return [q, vv, p];
        case 2: return [p, vv, t];
        case 3: return [p, q, vv];
        case 4: return [t, p, vv];
        default: return [vv, p, q];
    }
}

module.exports = { WS2812BMinimal, WS2812BFull };
