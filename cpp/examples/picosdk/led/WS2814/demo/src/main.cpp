#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "NeoPixelConnectionPicoSDK.h"
#include "WS2814.h"

static const size_t N_PIXELS          = 30;
static const unsigned long FRAME_MS   = 33;
static const unsigned long RAINBOW_MS = 5000;
static const unsigned long FLASH_MS   = 2000;
static const unsigned long DIM_MS     = 2000;

int main(void) {
    // SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;
    // SCK, MISO, and CS are unused by the strip.
    spi_init(spi0, 2'400'000);
    gpio_set_function(3, GPIO_FUNC_SPI);
    NeoPixelConnectionPicoSDK connection(spi0);
    WS2814Full strip(connection, N_PIXELS);

    stdio_init_all();

    while (true) {
        // --- Rainbow rotation using RGB channels (white=0). Each pixel is
        //     assigned a hue offset by its position; the offset advances
        //     each frame so the rainbow rotates continuously around the
        //     strip. Demonstrates WS2814's identity RGBW wire order (no
        //     reorder), unlike SK6812RGBW's GRBW order. Runs at ~30 fps
        //     for 5 seconds. ---
        float hue_offset = 0.0f;
        unsigned long start = to_ms_since_boot(get_absolute_time());
        unsigned long last_print = start;
        while (to_ms_since_boot(get_absolute_time()) - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b, 0);    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            unsigned long now = to_ms_since_boot(get_absolute_time());
            if (now - last_print >= 1000) {
                printf("mode=rainbow brightness=%u\n", strip.get_brightness());
                last_print = now;
            }
            unsigned long elapsed = to_ms_since_boot(get_absolute_time()) - now;
            if (elapsed < FRAME_MS) sleep_ms(FRAME_MS - elapsed);
        }

        // --- Warm white at full brightness for 2 seconds. ---
        strip.fill(255, 200, 150, 255);            // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        start = to_ms_since_boot(get_absolute_time());
        while (to_ms_since_boot(get_absolute_time()) - start < FLASH_MS) {
            printf("mode=warm-white brightness=%u\n", strip.get_brightness());
            sleep_ms(100);
        }

        // --- Dim warm white to 50% for 2 seconds. ---
        strip.set_brightness(128);                 // Set global brightness, (value=0–255) → void
        strip.show();                              // Transmit buffer to strip, () → void
        start = to_ms_since_boot(get_absolute_time());
        while (to_ms_since_boot(get_absolute_time()) - start < DIM_MS) {
            printf("mode=warm-white-dimmed brightness=%u\n", strip.get_brightness());
            sleep_ms(100);
        }

        // --- Cycle to cool white at full brightness. ---
        strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
        strip.fill(200, 210, 255, 255);            // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        printf("mode=cool-white brightness=%u\n", strip.get_brightness());
        sleep_ms(1000);
    }

    return 0;
}
