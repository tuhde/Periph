#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../connection/Connection.h"
#include "NeoPixelColor.h"

/** @brief APA102 addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of n APA102 pixels over an SPI connection (Mode 0, MSB first).
 * Maintains an internal BGR+brightness buffer; fill() writes every pixel and
 * transmits the full frame (start + pixels + end) immediately.
 *
 * The APA102 frame format is:
 *   start frame: 4 zero-bytes (0x00 × 4)
 *   pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)
 *   end frame:   max(4, (n+15)/16) bytes of 0xFF
 *
 * @param connection Configured SPI connection (Mode 0, MSB first).
 * @param n         Number of pixels in the strip.
 */
class APA102Minimal {
public:
    APA102Minimal(Connection& connection, size_t n);
    ~APA102Minimal();

    /** @brief Fill every pixel with one colour and send to the strip.
     *
     *  Clamps each channel to [0, 255], stores brightness/B/G/R in the
     *  internal buffer (BGR wire order with hardware brightness byte first),
     *  then transmits the full APA102 frame.
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
    Connection& _connection;
    size_t      _n;
    uint8_t*    _buf;   ///< size = n*4; per-pixel: [0xE0|brightness, B, G, R]

    void _send_frame();

    static inline uint8_t _end_frame_bytes(size_t n) {
        size_t e = (n + 15) / 16;
        return e < 4 ? 4 : (uint8_t)e;
    }
};

/** @brief APA102 full interface — extends APA102Minimal with per-pixel control.
 *
 * Adds individual pixel addressing with per-pixel hardware brightness,
 * explicit show(), global software brightness scaling, buffer rotation,
 * and HSV fill. Call set_pixel() / set_pixels() to update the buffer,
 * then show() to transmit; or use the inherited fill() for an immediate
 * all-same-colour update.
 *
 * @param connection Configured SPI connection (Mode 0, MSB first).
 * @param n         Number of pixels in the strip.
 */
class APA102Full : public APA102Minimal {
public:
    APA102Full(Connection& connection, size_t n);

    /** @brief Set one pixel in the buffer without sending.
     *
     *  Index is clamped to [0, n-1]; each RGB channel is clamped to [0, 255];
     *  pixel_brightness is clamped to [0, 31]. Call show() to transmit.
     *
     *  @param index             Zero-based pixel index.
     *  @param r                 Red channel (0–255).
     *  @param g                 Green channel (0–255).
     *  @param b                 Blue channel (0–255).
     *  @param pixel_brightness  Per-pixel hardware brightness 0–31 (default 31).
     */
    void set_pixel(size_t index, uint8_t r, uint8_t g, uint8_t b, uint8_t pixel_brightness = 31);

    /** @brief Transmit the current buffer to the strip, applying software brightness scaling.
     *
     *  Each RGB channel value is scaled: sent = stored * _brightness / 255.
     *  The per-pixel hardware brightness byte is NOT scaled.
     */
    void show();

    /** @brief Set multiple pixels from an array of (r,g,b) or (r,g,b,brightness) tuples.
     *
     *  Each element is (r, g, b) or (r, g, b, pixel_brightness). Missing brightness
     *  defaults to 31. Extra entries beyond the strip length are ignored.
     *  Does not transmit — call show() afterwards.
     *
     *  @param colors Array of pixel data tuples.
     *  @param count  Number of elements in the array.
     */
    void set_pixels(const uint8_t* colors, size_t count, bool has_brightness = false);

    /** @brief Get the global software brightness scalar (0–255). */
    uint8_t get_brightness() const;

    /** @brief Set the global software brightness scalar (0–255).
     *
     *  Applied non-destructively at show() time: stored RGB values are unchanged.
     *  The per-pixel hardware brightness byte is NOT affected.
     *
     *  @param value Brightness (0 = off, 255 = full).
     */
    void set_brightness(uint8_t value);

    /** @brief Shift the pixel buffer left by steps whole-pixel positions (wraps around).
     *
     *  Each step shifts 4 bytes (one BGR+brightness pixel). Does not transmit —
     *  call show() afterwards.
     *
     *  @param steps Number of pixel positions to shift left (default 1).
     */
    void rotate(size_t steps = 1);

    /** @brief Fill every pixel with one HSV colour and send to the strip.
     *
     *  Converts HSV to RGB then calls fill() at hardware brightness 31.
     *
     *  @param h Hue (0.0–1.0).
     *  @param s Saturation (0.0–1.0).
     *  @param v Value/brightness (0.0–1.0).
     */
    void fill_hsv(float h, float s, float v);

private:
    uint8_t _brightness;  ///< global software brightness 0–255
};