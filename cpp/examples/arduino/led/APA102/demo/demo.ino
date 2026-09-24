#include <SPI.h>
#include <Periph.h>

static const size_t N_PIXELS          = 30;
static const unsigned long FRAME_MS   = 16;   // ~60 fps
static const unsigned long RAINBOW_MS = 10000;

SPISettings apa102_settings(1000000, MSBFIRST, SPI_MODE0);  // 1 MHz, Mode 0, MSB first
SPIConnection connection(SPI, 10, apa102_settings);         // Create SPI connection, (spi=SPIClass&, cs_pin=10, settings)
APA102Full strip(connection, N_PIXELS);                    // Create APA102 full driver, (connection, n=N_PIXELS pixels)

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b);

void setup() {
    Serial.begin(115200);
    SPI.begin();
    pinMode(10, OUTPUT);
    digitalWrite(10, HIGH);
    strip.set_brightness(255);                              // Set global brightness, (value=0–255) → void
}

void loop() {
    // --- 13-bit effective color depth demonstration ---
    // First pass: full hardware brightness (31) for maximum drive current
    // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

    // --- Pass 1: Full hardware brightness (31) ---
    // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
    // hardware current control = 13-bit effective depth per channel.
    strip.set_brightness(255);                              // Set global software brightness, (value=0–255) → void
    float hue_offset = 0.0f;
    unsigned long start = millis();
    unsigned long last_print = start;
    while (millis() - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 31);                // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                       // Transmit buffer to strip, () → void
                                                              // applies software brightness scaling then calls connection.write()
        hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        unsigned long now = millis();
        if (now - last_print >= 1000) {
            Serial.print("rainbow hw_brightness=31 hue_offset=");
            Serial.println(hue_offset, 3);
            last_print = now;
        }
        unsigned long elapsed = millis() - now;
        if (elapsed < FRAME_MS) delay(FRAME_MS - elapsed);
    }

    // --- Pass 2: Low hardware brightness (1) ---
    // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
    // Demonstrates hardware current control vs software brightness scaling.
    strip.set_brightness(255);                              // Set global software brightness, (value=0–255) → void
    hue_offset = 0.0f;
    start = millis();
    last_print = start;
    while (millis() - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 1);                 // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                       // Transmit buffer to strip, () → void
                                                              // applies software brightness scaling then calls connection.write()
        hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        unsigned long now = millis();
        if (now - last_print >= 1000) {
            Serial.print("rainbow hw_brightness=1 hue_offset=");
            Serial.println(hue_offset, 3);
            last_print = now;
        }
        unsigned long elapsed = millis() - now;
        if (elapsed < FRAME_MS) delay(FRAME_MS - elapsed);
    }

    strip.off();                                            // Turn off all pixels, () → void
    delay(1000);
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
