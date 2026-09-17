#pragma once
#include "NeoPixelRGBWBase.h"

/** @brief WS2814 addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n WS2814 pixels over a NeoPixel connection.
 * Maintains an internal RGBW buffer (identity wire order — no reorder
 * needed); fill() writes every pixel and transmits immediately. Each
 * pixel has four channels: red, green, blue, and white.
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class WS2814Minimal : public NeoPixelRGBWMinimal {
public:
    WS2814Minimal(Connection& connection, size_t n);
};

/** @brief WS2814 full interface — per-pixel control, brightness, rotation, HSV fill.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill. Call set_pixel() to update the buffer, then
 * show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param connection Configured NeoPixel connection.
 * @param n         Number of pixels in the strip.
 */
class WS2814Full : public NeoPixelRGBWFull {
public:
    WS2814Full(Connection& connection, size_t n);
};
