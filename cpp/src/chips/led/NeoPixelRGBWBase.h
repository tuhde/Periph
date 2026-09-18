#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "NeoPixelColor.h"

/** @brief Shared minimal-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * Drives a chain of n pixels over a NeoPixel connection. Maintains an
 * internal buffer in wire order; fill() writes every pixel and transmits
 * immediately. Each pixel has four channels: red, green, blue, and white.
 * Subclasses fix channel_order and reset_bytes for their specific chip.
 *
 * The internal buffer is allocated as (n*4 + reset_bytes) bytes so the
 * trailing zero-bytes satisfy this chip's reset requirement when sent
 * through the connection.
 *
 * @param connection Configured NeoPixel connection.
 * @param n Number of pixels in the strip.
 * @param channel_order Wire byte positions for (r, g, b, w): wire[k] = (r,g,b,w)[channel_order[k]].
 * @param reset_bytes Extra zero-bytes appended after pixel data for this chip's reset pulse.
 */
class NeoPixelRGBWMinimal {
public:
    NeoPixelRGBWMinimal(Connection& connection, size_t n, const uint8_t channel_order[4], size_t reset_bytes);
    ~NeoPixelRGBWMinimal();

    /** @brief Fill every pixel with one colour and send to the strip.
     *
     *  Stores the four channels in the internal buffer using this chip's
     *  wire channel order, then calls connection.write(). The white
     *  channel defaults to 0, allowing RGB-only usage.
     *
     *  @param r Red channel (0–255).
     *  @param g Green channel (0–255).
     *  @param b Blue channel (0–255).
     *  @param w White channel (0–255, default 0).
     */
    void fill(uint8_t r, uint8_t g, uint8_t b, uint8_t w = 0);

    /** @brief Turn off all pixels (fill with all zeros and send).
     *
     *  Equivalent to fill(0, 0, 0, 0).
     */
    void off();

protected:
    Connection& _connection;
    size_t     _n;
    uint8_t*   _buf;   ///< size = n*4 + reset_bytes; trailing reset_bytes are always zero
    uint8_t    _channel_order[4];
    size_t     _reset_bytes;

    void _send();
};

/** @brief Shared full-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill on top of NeoPixelRGBWMinimal. Call set_pixel()
 * to update the buffer, then show() to transmit; or use the inherited fill()
 * for an immediate all-same-colour update.
 *
 * @param connection Configured NeoPixel connection.
 * @param n Number of pixels in the strip.
 * @param channel_order Wire byte positions for (r, g, b, w).
 * @param reset_bytes Extra zero-bytes appended after pixel data.
 */
class NeoPixelRGBWFull : public NeoPixelRGBWMinimal {
public:
    NeoPixelRGBWFull(Connection& connection, size_t n, const uint8_t channel_order[4], size_t reset_bytes);

    /** @brief Set one pixel in the buffer without sending.
     *
     *  Index is clamped to [0, n-1]. White channel defaults to 0.
     *  Call show() to transmit.
     *
     *  @param index Zero-based pixel index.
     *  @param r     Red channel (0–255).
     *  @param g     Green channel (0–255).
     *  @param b     Blue channel (0–255).
     *  @param w     White channel (0–255, default 0).
     */
    void set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b, uint8_t w = 0);

    /** @brief Transmit the current buffer to the strip, applying brightness scaling.
     *
     *  Each channel is scaled: sent = stored * brightness / 255.
     *  The trailing reset_bytes are always transmitted unscaled (zero).
     */
    void show();

    /** @brief Get the global brightness scalar (0–255). */
    uint8_t get_brightness() const;

    /** @brief Set the global brightness scalar (0–255).
     *
     *  Applied non-destructively at show() time: stored values are unchanged.
     *
     *  @param value Brightness (0 = off, 255 = full).
     */
    void set_brightness(uint8_t value);

    /** @brief Shift the pixel buffer left by steps whole-pixel positions (wraps around).
     *
     *  Each step shifts 4 bytes (one pixel). Does not transmit — call show()
     *  afterwards.
     *
     *  @param steps Number of pixel positions to shift left (default 1).
     */
    void rotate(size_t steps = 1);

    /** @brief Fill every pixel with one HSV colour and send to the strip.
     *
     *  Converts HSV to RGB (white=0) then calls fill().
     *
     *  @param h Hue (0.0–1.0).
     *  @param s Saturation (0.0–1.0).
     *  @param v Value/brightness (0.0–1.0).
     */
    void fill_hsv(float h, float s, float v);

private:
    uint8_t _brightness;
};
