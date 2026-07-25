#include <SPI.h>
#include "NeoPixelTransport.h"
#include "WS2812B.h"

static const size_t N_PIXELS          = 30;
static const unsigned long FRAME_MS   = 33;   // ~30 fps
static const unsigned long RAINBOW_MS = 10000;
static const unsigned long STROBE_MS  = 2000;
static const unsigned long STROBE_HALF_MS = 50;

NeoPixelTransport transport(SPI);              // Create NeoPixel transport, (spi=SPIClass&)
WS2812BFull strip(transport, N_PIXELS);        // Create WS2812B full driver, (transport, n=N_PIXELS pixels)

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b);

void setup() {
    Serial.begin(115200);
    SPI.begin();
    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
}

void loop() {
    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset is advanced each frame so the rainbow rotates around the strip.
    //     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
    float hue_offset = 0.0f;
    unsigned long start = millis();
    unsigned long last_print = start;
    while (millis() - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b);       // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
        }
        strip.show();                          // Transmit buffer to strip, () → void
        hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        unsigned long now = millis();
        if (now - last_print >= 1000) {
            Serial.print("rainbow hue_offset=");
            Serial.println(hue_offset, 3);
            last_print = now;
        }
        unsigned long elapsed = millis() - now;
        if (elapsed < FRAME_MS) delay(FRAME_MS - elapsed);
    }

    // --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
    //     Uses brightness=255 for maximum intensity then brightness=0 for off,
    //     demonstrating non-destructive brightness scaling — pixel values in the
    //     buffer are never zeroed. ---
    strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
    strip.fill(255, 255, 255);                 // Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → void
    start = millis();
    bool state = true;
    while (millis() - start < STROBE_MS) {
        strip.set_brightness(state ? 255 : 0); // Set global brightness, (value=0–255) → void
        strip.show();                          // Transmit buffer to strip, () → void
        state = !state;
        delay(STROBE_HALF_MS);
    }

    // --- Return to continuous rainbow ---
    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
}

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b) {
    if (s == 0.0f) { r = g = b = (uint8_t)(v * 255); return; }
    int i = (int)(h * 6.0f);
    float f = h * 6.0f - i;
    uint8_t p  = (uint8_t)(v * (1.0f - s) * 255);
    uint8_t q  = (uint8_t)(v * (1.0f - s * f) * 255);
    uint8_t t  = (uint8_t)(v * (1.0f - s * (1.0f - f)) * 255);
    uint8_t vv = (uint8_t)(v * 255);
    switch (i % 6) {
        case 0: r = vv; g = t;  b = p;  return;
        case 1: r = q;  g = vv; b = p;  return;
        case 2: r = p;  g = vv; b = t;  return;
        case 3: r = p;  g = q;  b = vv; return;
        case 4: r = t;  g = p;  b = vv; return;
        default: r = vv; g = p; b = q;
    }
}
