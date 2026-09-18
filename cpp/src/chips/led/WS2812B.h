#pragma once
#include "NeoPixelRGBBase.h"

/** @brief WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n WS2812B pixels over a NeoPixel connection.
 * Maintains an internal GRB buffer; fill() writes every pixel and
 * transmits immediately.
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class WS2812BMinimal : public NeoPixelRGBMinimal {
public:
    WS2812BMinimal(Connection& connection, size_t n);
};

/** @brief WS2812B full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill. Call set_pixel() to update the buffer, then
 * show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class WS2812BFull : public NeoPixelRGBFull {
public:
    WS2812BFull(Connection& connection, size_t n);
};
