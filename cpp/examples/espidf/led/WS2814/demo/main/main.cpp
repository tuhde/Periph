// Auto-generated ESP-IDF example for WS2814 (Demo).
// Mirrors the Arduino WS2814_Demo example using the
// NeoPixelConnectionESPIDF connection.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "esp_timer.h"
#include "NeoPixelConnectionESPIDF.h"
#include "WS2814.h"

static const size_t N_PIXELS       = 30;
static const uint32_t FRAME_MS     = 33;
static const uint32_t RAINBOW_MS   = 5000;
static const uint32_t FLASH_MS     = 2000;
static const uint32_t DIM_MS       = 2000;

static uint32_t now_ms(void) {
    return (uint32_t)(esp_timer_get_time() / 1000);
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = 13;
    bus_cfg.miso_io_num = -1;
    bus_cfg.sclk_io_num = 14;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    bus_cfg.max_transfer_sz = 0;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode = 0;
    dev_cfg.clock_speed_hz = 2400000;  // 2.4 MHz for NeoPixel bit-encoding
    dev_cfg.spics_io_num = -1;
    dev_cfg.queue_size = 1;
    spi_device_handle_t spi_dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &spi_dev);

    NeoPixelConnectionESPIDF connection(spi_dev);
    WS2814Full strip(connection, N_PIXELS);  // Create WS2814 driver

    while (1) {
        // --- Rainbow rotation using RGB channels (white=0), ~30 fps for 5s.
        //     WS2814's RGBW wire order is identity (no reorder). ---
        float hue_offset = 0.0f;
        uint32_t start = now_ms();
        uint32_t last_print = start;
        while (now_ms() - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b, 0);   // Set pixel i to rainbow hue (w=0), (index, r, g, b, w) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            uint32_t nowv = now_ms();
            if (nowv - last_print >= 1000) {
                printf("mode=rainbow brightness=%u\n", strip.get_brightness());
                last_print = nowv;
            }
            vTaskDelay(pdMS_TO_TICKS(FRAME_MS));
        }

        // --- Warm white at full brightness for 2 seconds. ---
        strip.fill(255, 200, 150, 255);            // Fill all pixels warm white, (r, g, b, w) → void
        start = now_ms();
        while (now_ms() - start < FLASH_MS) {
            printf("mode=warm-white brightness=%u\n", strip.get_brightness());
            vTaskDelay(pdMS_TO_TICKS(100));
        }

        // --- Dim warm white to 50% for 2 seconds. ---
        strip.set_brightness(128);                 // Set global brightness, (value 0-255) → void
        strip.show();                              // Transmit buffer to strip, () → void
        start = now_ms();
        while (now_ms() - start < DIM_MS) {
            printf("mode=warm-white-dimmed brightness=%u\n", strip.get_brightness());
            vTaskDelay(pdMS_TO_TICKS(100));
        }

        // --- Cycle to cool white at full brightness. ---
        strip.set_brightness(255);                 // Set global brightness, (value 0-255) → void
        strip.fill(200, 210, 255, 255);            // Fill all pixels cool white, (r, g, b, w) → void
        printf("mode=cool-white brightness=%u\n", strip.get_brightness());
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
