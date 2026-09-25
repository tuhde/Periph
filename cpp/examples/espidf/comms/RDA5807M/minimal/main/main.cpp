// Auto-generated ESP-IDF example for RDA5807M (Minimal).
// Mirrors the Arduino RDA5807M_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "RDA5807M.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x10;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    RDA5807MMinimal chip(connection);  // Create RDA5807M driver
    float freq, found;
    bool found_ok;
    uint16_t blk_a, blk_b, blk_c, blk_d;
    bool rds, st_stereo, st_station, st_ready;
    uint8_t rssi;
    while (1) {
    chip.set_frequency(100.0f);                       // Tune to frequency, (frequency_mhz) → void
    chip.set_volume(8);                               // Set volume, (level 0-15) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
