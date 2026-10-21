#include <string.h>
#include <math.h>
#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "23X1024.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 8000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                                  // Create SPI connection, (dev) → SPIConnectionESPIDF
    SRAM23X1024Minimal sram(connection);                                     // Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    uint8_t counter = 0;
    while (true) {
        sram.write_byte(0x00000, counter);                                   // Write one byte, (address 0–131071, value 0–255)
        uint8_t value = sram.read_byte(0x00000);                             // Read one byte, (address 0–131071) → byte
        printf("wrote 0x%02X, read 0x%02X\n", counter, value);
        counter++;
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}
