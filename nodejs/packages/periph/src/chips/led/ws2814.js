'use strict';

const { NeoPixelRGBWMinimal, NeoPixelRGBWFull } = require('./_NeoPixelRGBWBase');

const RESET_BYTES = 90;

/**
 * WS2814 addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n WS2814 pixels over a NeoPixel connection.
 * Maintains an internal RGBW buffer (identity wire order — no reorder
 * needed); fill() writes every pixel and transmits immediately. Each
 * pixel has four channels: red, green, blue, and white.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2814Minimal extends NeoPixelRGBWMinimal {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [0, 1, 2, 3], RESET_BYTES);
    }
}

/**
 * WS2814 full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness
 * scaling, buffer rotation, and HSV fill. Call set_pixel() / set_pixels()
 * to update the buffer, then show() to transmit; or use the inherited
 * fill() for an immediate all-same-colour update.
 *
 * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
 * @param {number} n         - Number of pixels in the strip.
 */
class WS2814Full extends NeoPixelRGBWFull {
    /**
     * @param {import('../../connection/neopixel').NeoPixelConnection} connection - Configured NeoPixel connection.
     * @param {number} n         - Number of pixels in the strip.
     */
    constructor(connection, n) {
        super(connection, n, [0, 1, 2, 3], RESET_BYTES);
    }
}

module.exports = { WS2814Minimal, WS2814Full };
