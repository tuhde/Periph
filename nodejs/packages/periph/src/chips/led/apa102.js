'use strict';

/**
 * APA102 addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n APA102 pixels over an SPI connection (Mode 0, MSB first).
 * Maintains an internal BGR+brightness buffer; fill() writes every pixel and
 * transmits the full frame (start + pixels + end) immediately.
 *
 * The APA102 frame format is:
 *   start frame: 4 zero-bytes (0x00 × 4)
 *   pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)
 *   end frame:   max(4, (n+15)//16) bytes of 0xFF
 *
 * @param {import('../../connection/spi').SPIConnection} connection - Configured SPI connection (Mode 0, MSB first).
 * @param {number} n         - Number of pixels in the strip.
 */
class APA102Minimal {
    /**
     * @param {import('../../connection/spi').SPIConnection} connection - Configured SPI connection (Mode 0, MSB first).
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        this._conn = connection;
        this._n = n;
        this._buf = Buffer.alloc(n * 4);
        // Initialize buffer with hardware brightness=31, all channels off
        for (let i = 0; i < n; i++) {
            this._buf[i * 4]     = 0xE0 | 31;  // brightness byte (3 high bits = 1)
            this._buf[i * 4 + 1] = 0;          // blue
            this._buf[i * 4 + 2] = 0;          // green
            this._buf[i * 4 + 3] = 0;          // red
        }
    }

    /**
     * Fill every pixel with one colour and send to the strip immediately.
     *
     * Clamps each channel to [0, 255]. Stores brightness/B/G/R in the
     * internal buffer (BGR wire order with hardware brightness byte first),
     * then transmits the full APA102 frame.
     *
     * @param {number} r - Red channel (0–255).
     * @param {number} g - Green channel (0–255).
     * @param {number} b - Blue channel (0–255).
     * @returns {Promise<void>}
     */
    async fill(r, g, b) {
        r = Math.max(0, Math.min(255, r | 0));
        g = Math.max(0, Math.min(255, g | 0));
        b = Math.max(0, Math.min(255, b | 0));
        for (let i = 0; i < this._n; i++) {
            this._buf[i * 4]     = 0xE0 | 31;  // hardware brightness = 31 (max)
            this._buf[i * 4 + 1] = b;          // blue
            this._buf[i * 4 + 2] = g;          // green
            this._buf[i * 4 + 3] = r;          // red
        }
        await this._sendFrame();
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

    /**
     * Send the full APA102 frame (start + pixel buffer + end).
     * @returns {Promise<void>}
     * @private
     */
    async _sendFrame() {
        const endBytes = Math.max(4, Math.floor((this._n + 15) / 16));
        const frame = Buffer.alloc(4 + this._n * 4 + endBytes);
        // Start frame: 4 zero bytes
        frame[0] = frame[1] = frame[2] = frame[3] = 0x00;
        // Pixel data
        this._buf.copy(frame, 4);
        // End frame: 0xFF bytes
        for (let i = 0; i < endBytes; i++) {
            frame[4 + this._n * 4 + i] = 0xFF;
        }
        await this._conn.write(frame);
    }
}

/**
 * APA102 full interface — extends APA102Minimal with per-pixel control.
 *
 * Adds individual pixel addressing with per-pixel hardware brightness,
 * explicit show(), global software brightness scaling, buffer rotation,
 * and HSV fill. Call set_pixel() / set_pixels() to update the buffer,
 * then show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param {import('../../connection/spi').SPIConnection} connection - Configured SPI connection (Mode 0, MSB first).
 * @param {number} n         - Number of pixels in the strip.
 */
class APA102Full extends APA102Minimal {
    /**
     * @param {import('../../connection/spi').SPIConnection} connection - Configured SPI connection (Mode 0, MSB first).
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n);
        this._brightness = 255;  // global software brightness (0–255)
    }

    /**
     * Global software brightness scalar applied at show() time (0–255).
     * @type {number}
     */
    get brightness() { return this._brightness; }
    set brightness(value) {
        this._brightness = Math.max(0, Math.min(255, value | 0));
    }

    /**
     * Set one pixel in the buffer without sending.
     *
     * Index is clamped to [0, n-1]; each RGB channel is clamped to [0, 255];
     * pixel_brightness is clamped to [0, 31]. Call show() to transmit.
     *
     * @param {number} index             - Zero-based pixel index.
     * @param {number} r                 - Red channel (0–255).
     * @param {number} g                 - Green channel (0–255).
     * @param {number} b                 - Blue channel (0–255).
     * @param {number} [pixel_brightness=31] - Per-pixel hardware brightness 0–31.
     */
    set_pixel(index, r, g, b, pixel_brightness = 31) {
        index = Math.max(0, Math.min(this._n - 1, index | 0));
        r = Math.max(0, Math.min(255, r | 0));
        g = Math.max(0, Math.min(255, g | 0));
        b = Math.max(0, Math.min(255, b | 0));
        pixel_brightness = Math.max(0, Math.min(31, pixel_brightness | 0));
        this._buf[index * 4]     = 0xE0 | pixel_brightness;
        this._buf[index * 4 + 1] = b;
        this._buf[index * 4 + 2] = g;
        this._buf[index * 4 + 3] = r;
    }

    /**
     * Set multiple pixels from an array of [r, g, b] or [r, g, b, pixel_brightness] arrays.
     *
     * Each element is [r, g, b] or [r, g, b, pixel_brightness]. Missing brightness
     * defaults to 31. Extra entries beyond the strip length are ignored.
     * Does not transmit — call show() afterwards.
     *
     * @param {Array<number[]>} colors - Array of [r, g, b] or [r, g, b, pixel_brightness] arrays.
     */
    set_pixels(colors) {
        for (let i = 0; i < colors.length && i < this._n; i++) {
            const [r, g, b, pixel_brightness = 31] = colors[i];
            this._buf[i * 4]     = 0xE0 | Math.max(0, Math.min(31, pixel_brightness | 0));
            this._buf[i * 4 + 1] = Math.max(0, Math.min(255, b | 0));
            this._buf[i * 4 + 2] = Math.max(0, Math.min(255, g | 0));
            this._buf[i * 4 + 3] = Math.max(0, Math.min(255, r | 0));
        }
    }

    /**
     * Transmit the current buffer to the strip, applying software brightness scaling.
     *
     * Each RGB channel value is scaled: sent = stored * brightness / 255.
     * The per-pixel hardware brightness byte is NOT scaled.
     * @returns {Promise<void>}
     */
    async show() {
        const bri = this._brightness;
        const endBytes = Math.max(4, Math.floor((this._n + 15) / 16));
        const pixelDataLen = this._n * 4;
        const totalLen = 4 + pixelDataLen + endBytes;

        const frame = Buffer.alloc(totalLen);
        frame[0] = frame[1] = frame[2] = frame[3] = 0x00;

        if (bri === 255) {
            this._buf.copy(frame, 4);
        } else {
            // Scale RGB channels, leave hardware brightness byte unchanged
            for (let i = 0; i < this._n; i++) {
                const base = i * 4;
                frame[4 + base]     = this._buf[base];                                         // hardware brightness
                frame[4 + base + 1] = (this._buf[base + 1] * bri / 255) | 0;  // blue
                frame[4 + base + 2] = (this._buf[base + 2] * bri / 255) | 0;  // green
                frame[4 + base + 3] = (this._buf[base + 3] * bri / 255) | 0;  // red
            }
        }

        for (let i = 0; i < endBytes; i++) {
            frame[4 + pixelDataLen + i] = 0xFF;
        }

        await this._conn.write(frame);
    }

    /**
     * Shift the pixel buffer left by steps whole-pixel positions (wraps around).
     *
     * Each step shifts 4 bytes (one BGR+brightness pixel). Does not transmit —
     * call show() afterwards.
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
     * Converts HSV to RGB then calls fill() at hardware brightness 31.
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

const { _hsvToRgb } = require('./_color');

module.exports = { APA102Minimal, APA102Full };