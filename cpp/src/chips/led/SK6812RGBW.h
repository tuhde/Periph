#pragma once
#include "NeoPixelRGBWBase.h"

/** @brief SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n SK6812RGBW pixels over a NeoPixel connection.
 * Maintains an internal GRBW buffer; fill() writes every pixel and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class SK6812RGBWMinimal : public NeoPixelRGBWMinimal {
public:
    SK6812RGBWMinimal(Connection& connection, size_t n);
};

/** @brief SK6812RGBW full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill. Call set_pixel() to update the buffer, then
 * show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class SK6812RGBWFull : public NeoPixelRGBWFull {
public:
    SK6812RGBWFull(Connection& connection, size_t n);
};
