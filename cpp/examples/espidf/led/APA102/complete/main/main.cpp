// Auto-generated ESP-IDF example for APA102 (Complete).
// Uses SPIConnectionESPIDF for raw SPI Mode 0 (APA102 synchronous protocol).

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "APA102.h"

extern "C" void app_main(void) {
    // Default APA102 SPI pins: MOSI=GPIO23, SCK=GPIO18 (SPI2_HOST defaults)
    spi_bus_config_t bus_cfg = {
        .mosi_io_num = 23,
        .miso_io_num = -1,
        .sclk_io_num = 18,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 0,
    };
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {
        .mode = 0,
        .clock_speed_hz = 1000000,  // 1 MHz for APA102
        .spics_io_num = -1,         // No hardware CS needed
        .queue_size = 1,
    };
    spi_device_handle_t spi_dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &spi_dev);

    SPIConnectionESPIDF connection(spi_dev);
    APA102Full strip(connection, 8);  // Create APA102 full driver

    while (1) {
        // fill — set all pixels and send immediately
        strip.fill(255, 0, 0);                                  // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                                 // stores brightness/B/G/R in buffer and calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));

        // set individual pixels then show
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(1, 0, 255, 0);                          // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(2, 0, 0, 255);                          // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));

        // set_pixels — write multiple pixels at once
        uint8_t colors[] = {
            255, 128, 0,   128, 0, 255,   0, 255, 128,
            255, 255, 0,   0, 255, 255,   255, 0, 255,
            128, 128, 128, 255, 255, 255
        };
        strip.set_pixels(colors, 8, false);                     // Set pixels from flat array of (r,g,b), (colors=uint8_t[], count, has_brightness=false) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));

        // set_pixels with per-pixel hardware brightness
        uint8_t colors_bright[] = {
            255, 0, 0, 31,   255, 0, 0, 16,   255, 0, 0, 8,   255, 0, 0, 4,
            0, 255, 0, 31,   0, 255, 0, 16,   0, 255, 0, 8,   0, 255, 0, 4
        };
        strip.set_pixels(colors_bright, 8, true);               // Set pixels from flat array of (r,g,b,brightness), (colors=uint8_t[], count, has_brightness=true) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));

        // brightness — global software scale applied at show() time
        strip.set_brightness(64);                               // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));
        strip.set_brightness(255);                              // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

        // fill_hsv — fill all pixels from HSV colour
        strip.fill_hsv(0.0f, 1.0f, 1.0f);                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.0 = red
        vTaskDelay(pdMS_TO_TICKS(500));
        strip.fill_hsv(0.333f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.333 = green
        vTaskDelay(pdMS_TO_TICKS(500));
        strip.fill_hsv(0.667f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.667 = blue
        vTaskDelay(pdMS_TO_TICKS(500));

        // rotate — shift pixel buffer by N positions
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        for (size_t i = 1; i < 8; i++) {
            strip.set_pixel(i, 0, 0, 0);                        // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        }
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        vTaskDelay(pdMS_TO_TICKS(500));
        for (int i = 0; i < 7; i++) {
            strip.rotate(1);                                    // Rotate pixel buffer left, (steps=1) → void
                                                                 // shifts buffer by steps pixel positions; wraps around; does not send
            strip.show();                                       // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
            vTaskDelay(pdMS_TO_TICKS(200));
        }

        strip.off();                                            // Turn off all pixels, () → void
                                                                 // equivalent to fill(0, 0, 0)
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}