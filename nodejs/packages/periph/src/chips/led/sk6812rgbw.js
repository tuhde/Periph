'use strict';

const { NeoPixelRGBWMinimal, NeoPixelRGBWFull } = require('./_NeoPixelRGBWBase');

const RESET_BYTES = 24;

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n SK6812RGBW pixels over a NeoPixel connection.
 * Maintains an internal GRBW buffer; fill() writes every pixel and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class SK6812RGBWMinimal extends NeoPixelRGBWMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [1, 0, 2, 3], RESET_BYTES);
    }
}

/**
 * SK6812RGBW full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
 * to update the buffer, then show() to transmit; or use the inherited
 * fill() for an immediate all-same-colour update.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class SK6812RGBWFull extends NeoPixelRGBWFull {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [1, 0, 2, 3], RESET_BYTES);
    }
}

module.exports = { SK6812RGBWMinimal, SK6812RGBWFull };
