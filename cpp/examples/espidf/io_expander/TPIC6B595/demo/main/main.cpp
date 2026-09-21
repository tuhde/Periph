// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include "SiPoConnectionESPIDF.h"
#include "TPIC6B595.h"

static constexpr uint8_t NUM_DEVICES = 2;
static constexpr uint8_t NUM_OUTPUTS = NUM_DEVICES * 8;

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

    gpio_num_t rck   = static_cast<gpio_num_t>(17);
    gpio_num_t srclr = static_cast<gpio_num_t>(16);
    gpio_num_t g     = static_cast<gpio_num_t>(15);
    SiPoConnectionESPIDF connection(dev, rck, srclr, g);                     // Create SiPo connection, (spi, rck, srclr, g)
    TPIC6B595Full<SiPoConnectionESPIDF> chip(connection, NUM_DEVICES);       // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                              // two cascaded devices — 16 outputs total; outputs start OFF

    int8_t position = 0;
    int8_t direction = 1;
    uint8_t sweep_count = 0;
    constexpr uint8_t BLANK_EVERY = 3;
    constexpr uint32_t BLANK_MS = 500;

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        uint8_t bytes_[NUM_DEVICES] = {0, 0};
        uint8_t port = position / 8;
        uint8_t bit  = position % 8;
        bytes_[port] = (uint8_t)(1u << bit);
        chip.write_all(bytes_, NUM_DEVICES);                                  // Write all device bytes, (values=uint8_t*, len=2) → void

        printf("position=%d  bytes=[0x%02X, 0x%02X]\n",
               (int)position, bytes_[0], bytes_[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count++;
        if (sweep_count % BLANK_EVERY == 0) {
            chip.set_output_enable(false);                                    // Force every output off via G, (enabled=false) → int
                                                                              // the chase pattern's shadow state is preserved
            printf("  blanked via G for %u ms\n", (unsigned)BLANK_MS);
            vTaskDelay(pdMS_TO_TICKS(BLANK_MS));
            chip.set_output_enable(true);                                     // Re-enable outputs, (enabled=true) → int
                                                                              // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if (position >= (int8_t)(NUM_OUTPUTS - 1) || position <= 0) {
            direction = -direction;
            vTaskDelay(pdMS_TO_TICKS(100));
        } else {
            vTaskDelay(pdMS_TO_TICKS(80));
        }
    }
}
