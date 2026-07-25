// Auto-generated ESP-IDF example for SK6812RGBW (Complete).
// Mirrors the Arduino SK6812RGBW_Complete example using the
// NeoPixelTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "NeoPixelTransportESPIDF.h"
#include "SK6812RGBW.h"

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {
        .mosi_io_num = 13,
        .miso_io_num = -1,
        .sclk_io_num = 14,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 0,
    };
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {
        .mode = 0,
        .clock_speed_hz = 2400000,  // 2.4 MHz for NeoPixel bit-encoding
        .spics_io_num = -1,
        .queue_size = 1,
    };
    spi_device_handle_t spi_dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &spi_dev);

    NeoPixelTransportESPIDF transport(spi_dev);
    SK6812RGBWFull chip(transport, 8);  // Create SK6812RGBW driver
    chip.set_pixel(0, 255, 0, 0, 0);                  // Set one pixel, (index, r, g, b, w=0) → void
    chip.set_pixel(1, 0, 255, 0, 0);                  // Set one pixel, (index, r, g, b, w=0) → void
    chip.set_pixel(2, 0, 0, 255, 0);                  // Set one pixel, (index, r, g, b, w=0) → void
    chip.set_brightness(128);                         // Set brightness, (value 0-255) → void
    chip.show();                                      // Transmit buffer with brightness, () → void
    chip.get_brightness();                            // Read brightness, () → uint8_t
    chip.rotate(1);                                   // Rotate buffer, (steps=1) → void
    chip.show();                                      // Transmit buffer, () → void
    chip.fill_hsv(0.0f, 1.0f, 1.0f);                  // Fill HSV, (h, s, v) → void
    chip.off();                                       // Turn off all pixels, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
