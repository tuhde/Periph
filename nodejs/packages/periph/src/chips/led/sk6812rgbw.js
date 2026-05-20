'use strict';

const RESET = Buffer.alloc(24);

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n SK6812RGBW pixels over a NeoPixel transport.
 * Maintains an internal GRBW buffer; fill() writes every pixel and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * @param {object} transport - Configured NeoPixel transport.
 * @param {number} n         - Number of pixels in the strip.
 */
class SK6812RGBWMinimal {
    /**
     * @param {object} transport - Configured NeoPixel transport.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(transport, n) {
        this._transport = transport;
        this._n = n;
        this._buf = Buffer.alloc(n * 4);
    }

    /**
     * Fill every pixel with one colour and send to the strip immediately.
     *
     * Clamps each channel to [0, 255]. Stores G, R, B, W (GRBW wire order),
     * then appends 24 reset bytes and calls transport.write().
     *
     * @param {number} r       - Red channel (0–255).
     * @param {number} g       - Green channel (0–255).
     * @param {number} b       - Blue channel (0–255).
     * @param {number} [w=0]   - White channel (0–255).
     */
    fill(r, g = 0, b = 0, w = 0) {
        r = Math.max(0, Math.min(255, r | 0));
        g = Math.max(0, Math.min(255, g | 0));
        b = Math.max(0, Math.min(255, b | 0));
        w = Math.max(0, Math.min(255, w | 0));
        for (let i = 0; i < this._n; i++) {
            this._buf[i * 4]     = g;
            this._buf[i * 4 + 1] = r;
            this._buf[i * 4 + 2] = b;
            this._buf[i * 4 + 3] = w;
        }
        this._transport.write(Buffer.concat([this._buf, RESET]));
    }

    /**
     * Turn off all pixels (fill with all zeros and send).
     *
     * Equivalent to fill(0, 0, 0, 0).
     */
    off() {
        this.fill(0, 0, 0, 0);
    }
}

/**
 * SK6812RGBW full interface — extends SK6812RGBWMinimal with per-pixel control.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
 * to update the buffer, then show() to transmit; or use the inherited
 * fill() for an immediate all-same-colour update.
 *
 * @param {object} transport - Configured NeoPixel transport.
 * @param {number} n         - Number of pixels in the strip.
 */
class SK6812RGBWFull extends SK6812RGBWMinimal {
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
     * @param {number} index   - Zero-based pixel index.
     * @param {number} r       - Red channel (0–255).
     * @param {number} g       - Green channel (0–255).
     * @param {number} b       - Blue channel (0–255).
     * @param {number} [w=0]   - White channel (0–255).
     */
    set_pixel(index, r, g, b, w = 0) {
        index = Math.max(0, Math.min(this._n - 1, index | 0));
        this._buf[index * 4]     = Math.max(0, Math.min(255, g | 0));
        this._buf[index * 4 + 1] = Math.max(0, Math.min(255, r | 0));
        this._buf[index * 4 + 2] = Math.max(0, Math.min(255, b | 0));
        this._buf[index * 4 + 3] = Math.max(0, Math.min(255, w | 0));
    }

    /**
     * Write a sequence of [r, g, b] or [r, g, b, w] arrays into the buffer
     * starting at pixel 0.
     *
     * Extra entries beyond the strip length are ignored. Call show() to transmit.
     *
     * @param {Array<number[]>} colors - Array of [r, g, b] or [r, g, b, w] arrays.
     */
    set_pixels(colors) {
        for (let i = 0; i < colors.length && i < this._n; i++) {
            const [r, g, b, w = 0] = colors[i];
            this._buf[i * 4]     = Math.max(0, Math.min(255, g | 0));
            this._buf[i * 4 + 1] = Math.max(0, Math.min(255, r | 0));
            this._buf[i * 4 + 2] = Math.max(0, Math.min(255, b | 0));
            this._buf[i * 4 + 3] = Math.max(0, Math.min(255, w | 0));
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * Each channel is scaled: sent = stored * brightness / 255.
     * Appends 24 reset bytes before transmission.
     */
    show() {
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
        this._transport.write(Buffer.concat([pixels, RESET]));
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
     */
    fill_hsv(h, s, v) {
        const [r, g, b] = _hsvToRgb(h, s, v);
        this.fill(r, g, b, 0);
    }
}

const { _hsvToRgb } = require('./_color');

module.exports = { SK6812RGBWMinimal, SK6812RGBWFull };
