// Auto-generated ESP-IDF example for APA102 (Minimal).
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
    APA102Minimal strip(connection, 30);  // Create APA102 driver

    while (1) {
        strip.fill(255, 0, 0);                             // Fill all pixels red, (r=0–255, g=0–255, b=0–255) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
        strip.fill(0, 255, 0);                             // Fill all pixels green, (r=0–255, g=0–255, b=0–255) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
        strip.fill(0, 0, 255);                             // Fill all pixels blue, (r=0–255, g=0–255, b=0–255) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
        strip.off();                                       // Turn off all pixels, () → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}