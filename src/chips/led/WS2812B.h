#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"
#include "NeoPixelColor.h"

/** @brief WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n WS2812B pixels over a NeoPixel transport.
 * Maintains an internal GRB buffer; fill() writes every pixel and
 * transmits immediately.
 *
 * @param transport Configured NeoPixel transport.
 * @param n         Number of pixels in the strip.
 */
class WS2812BMinimal {
public:
    WS2812BMinimal(Transport& transport, size_t n);
    ~WS2812BMinimal();

    /** @brief Fill every pixel with one colour and send to the strip.
     *
     *  Clamps each channel to [0, 255], stores GRB in the internal buffer,
     *  then calls transport.write().
     *
     *  @param r Red channel (0–255).
     *  @param g Green channel (0–255).
     *  @param b Blue channel (0–255).
     */
    void fill(uint8_t r, uint8_t g, uint8_t b);

    /** @brief Turn off all pixels (fill with black and send).
     *
     *  Equivalent to fill(0, 0, 0).
     */
    void off();

protected:
    Transport& _transport;
    size_t     _n;
    uint8_t*   _buf;

    void _send();
};

/** @brief WS2812B full interface — extends WS2812BMinimal with per-pixel control.
 *
 * Adds individual pixel addressing, explicit show(), global brightness scaling,
 * buffer rotation, and HSV fill. Call set_pixel() / set_pixels() to update the
 * buffer, then show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param transport Configured NeoPixel transport.
 * @param n         Number of pixels in the strip.
 */
class WS2812BFull : public WS2812BMinimal {
public:
    WS2812BFull(Transport& transport, size_t n);

    /** @brief Set one pixel in the buffer without sending.
     *
     *  Index is clamped to [0, n-1]; each channel is clamped to [0, 255].
     *  Call show() to transmit.
     *
     *  @param index Zero-based pixel index.
     *  @param r     Red channel (0–255).
     *  @param g     Green channel (0–255).
     *  @param b     Blue channel (0–255).
     */
    void set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b);

    /** @brief Transmit the current buffer to the strip, applying brightness scaling.
     *
     *  Each channel is scaled: sent = stored * brightness / 255.
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

    /** @brief Shift the pixel buffer left by steps positions (wraps around).
     *
     *  Does not transmit — call show() afterwards.
     *
     *  @param steps Number of pixel positions to shift left (default 1).
     */
    void rotate(size_t steps = 1);

    /** @brief Fill every pixel with one HSV colour and send to the strip.
     *
     *  Converts HSV to RGB then calls fill().
     *
     *  @param h Hue (0.0–1.0).
     *  @param s Saturation (0.0–1.0).
     *  @param v Value/brightness (0.0–1.0).
     */
    void fill_hsv(float h, float s, float v);

private:
    uint8_t _brightness;

};
