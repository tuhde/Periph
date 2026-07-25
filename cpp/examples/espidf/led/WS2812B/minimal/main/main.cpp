// Auto-generated ESP-IDF example for WS2812B (Minimal).
// Mirrors the Arduino WS2812B_Minimal example using the
// NeoPixelTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "NeoPixelTransportESPIDF.h"
#include "WS2812B.h"

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
    WS2812BMinimal chip(transport, 8);  // Create WS2812B driver
    while (1) {
    chip.fill(255, 0, 0);                             // Fill all pixels RGB, (r, g, b) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
