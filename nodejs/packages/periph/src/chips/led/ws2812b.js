'use strict';

const { NeoPixelRGBMinimal, NeoPixelRGBFull } = require('./_NeoPixelRGBBase');

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n WS2812B pixels over a NeoPixel connection.
 * Maintains an internal GRB buffer; fill() writes every pixel and
 * transmits immediately.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2812BMinimal extends NeoPixelRGBMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [1, 0, 2], 0);
    }
}

/**
 * WS2812B full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
 * to update the buffer, then show() to transmit; or use the inherited
 * fill() for an immediate all-same-colour update.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2812BFull extends NeoPixelRGBFull {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [1, 0, 2], 0);
    }
}

module.exports = { WS2812BMinimal, WS2812BFull };
