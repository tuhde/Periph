#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"
#include "NeoPixelColor.h"

/** @brief SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of n SK6812RGBW pixels over a NeoPixel transport.
 * Maintains an internal GRBW buffer; fill() writes every pixel and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * The internal buffer is allocated as (n*4 + 24) bytes so the trailing
 * 24 zero-bytes satisfy the SK6812RGBW ≥80 µs reset requirement when
 * sent through the transport.
 *
 * @param transport Configured NeoPixel transport.
 * @param n         Number of pixels in the strip.
 */
class SK6812RGBWMinimal {
public:
    SK6812RGBWMinimal(Transport& transport, size_t n);
    ~SK6812RGBWMinimal();

    /** @brief Fill every pixel with one colour and send to the strip.
     *
     *  Stores GRBW in the internal buffer then calls transport.write().
     *  The white channel defaults to 0, allowing RGB-only usage.
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
    Transport& _transport;
    size_t     _n;
    uint8_t*   _buf;   ///< size = n*4 + 24; last 24 bytes are always zero (reset)

    void _send();
};

/** @brief SK6812RGBW full interface — extends SK6812RGBWMinimal with per-pixel control.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill. Call set_pixel() to update the buffer, then
 * show() to transmit; or use the inherited fill() for an immediate all-same-colour
 * update.
 *
 * @param transport Configured NeoPixel transport.
 * @param n         Number of pixels in the strip.
 */
class SK6812RGBWFull : public SK6812RGBWMinimal {
public:
    SK6812RGBWFull(Transport& transport, size_t n);

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
     *  The 24 trailing zero-bytes (reset) are always transmitted unscaled.
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
     *  Each step shifts 4 bytes (one GRBW pixel). Does not transmit — call show()
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
