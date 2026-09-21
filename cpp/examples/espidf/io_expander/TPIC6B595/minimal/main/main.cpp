#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include "SiPoConnectionESPIDF.h"
#include "TPIC6B595.h"

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = static_cast<gpio_num_t>(23);
    bus_cfg.miso_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.sclk_io_num = static_cast<gpio_num_t>(18);
    bus_cfg.quadwp_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.quadhd_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.max_transfer_sz = 32;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.clock_speed_hz = 1000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(-1);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    gpio_num_t rck = static_cast<gpio_num_t>(17);
    SiPoConnectionESPIDF connection(dev, rck);                                // Create SiPo connection, (spi, rck, srclr=-1, g=-1)
    TPIC6B595Minimal<SiPoConnectionESPIDF> chip(connection);                 // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                              // initialises every output to OFF (shadow zeroed, latched once)

    auto p0 = chip.pin(0);                                                    // Get pin proxy, (n=0) → IOExpanderPin
    auto p7 = chip.pin(7);                                                    // Get pin proxy, (n=7) → IOExpanderPin

    while (true) {
        p0.high();                                                            // Set DMOS output ON, () → void
        p7.low();                                                             // Set DMOS output OFF, () → void
        vTaskDelay(pdMS_TO_TICKS(500));
        p0.low();                                                             // Set DMOS output OFF, () → void
        p7.high();                                                            // Set DMOS output ON, () → void
        vTaskDelay(pdMS_TO_TICKS(500));
    }
}
