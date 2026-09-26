#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "esp_timer.h"
#include "SPIConnectionESPIDF.h"
#include "RFM9x.h"

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
    dev_cfg.clock_speed_hz  = 5000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);                 // Create SPI connection, (dev) → SPIConnectionESPIDF
    RFM95Minimal radio(connection, 868000000);           // Create RFM95W driver, (connection, frequency_hz=868e6 Hz) → RFM95Minimal

    const uint8_t msg[] = "hello";
    uint8_t buf[255];
    while (true) {
        radio.send(msg, sizeof(msg) - 1);                // Send packet, (data, len ≤ 255) → void
        size_t len = 0;
        bool ok = radio.receive(buf, len, 2000);         // Receive single packet, (buf, len, timeout_ms=2000 ms) → bool
        if (ok) printf("rx %u B\n", (unsigned)len);
        else    printf("rx timeout\n");
        vTaskDelay(pdMS_TO_TICKS(3000));
    }
}
