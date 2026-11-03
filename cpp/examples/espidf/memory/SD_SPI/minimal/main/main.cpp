#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "SPIConnectionESPIDF.h"
#include "SD_SPI.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

#define PF printf
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
static uint32_t now_ms() { return static_cast<uint32_t>(esp_timer_get_time() / 1000); }

static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) PF("%02X ", p[i]);
    PF("\n");
}

static void app(SPIExtensions& spi) {
    SDSPIMinimal::Options opt;                                                // Driver options, (speed_hz=12000000, now_ms=nullptr, crc=false)
    opt.now_ms = now_ms;
    SDSPIMinimal sd(spi, opt);                                                // Create SD driver and init the card, (spi, options) → SDSPIMinimal

    while (sd.status() != BlockError::Ok) {
        PF("no SD card (error %d), retrying\n", static_cast<int>(sd.status()));
        DELAY_MS(2000);
        sd.begin();                                                           // Re-run card init, () → BlockError
    }

    PF("capacity: %lu blocks = %lu MiB\n",
       static_cast<unsigned long>(sd.block_count()),                          // Capacity in 512-byte blocks, () → uint32 blocks
       static_cast<unsigned long>(sd.block_count() / 2048));
    static uint8_t block[512];
    while (true) {
        BlockError e = sd.read_block(0, block);                               // Read one 512-byte block, (block, buf) → BlockError
        PF("block 0 (%d): ", static_cast<int>(e));
        print_hex(block, 16);
        PF("boot signature: %02X%02X\n", block[510], block[511]);
        DELAY_MS(2000);
    }
}

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
    dev_cfg.clock_speed_hz  = 400000;          // SD init needs <= 400 kHz; the driver raises it afterwards
    dev_cfg.spics_io_num    = -1;              // CS is driven manually: the SD init needs clocks with CS high
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    vTaskDelay(pdMS_TO_TICKS(2000));
    SPIConnectionESPIDF connection(dev);                                      // Create SPI connection, (dev) → SPIConnectionESPIDF
    connection.setManualCS(static_cast<gpio_num_t>(CS_PIN));                  // Drive CS from a GPIO, (cs_gpio)
    connection.enableSpeedChange(SPI2_HOST, dev_cfg);                         // Allow set_speed() to re-add the device, (host, dev_cfg)
    app(connection);
}
