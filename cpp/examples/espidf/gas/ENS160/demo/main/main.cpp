// Auto-generated ESP-IDF example for ENS160 (Demo).
// Mirrors the Arduino ENS160_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ENS160.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x52,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ENS160Full chip(connection);  // Create ENS160 driver
    uint8_t aqi, validity;
    float tvoc, eco2;
    uint8_t maj, min, rel;
    float t_c, rh;
    float r_ohm;
    // --- Air quality log with external temperature/humidity compensation ---
    // Without compensation the gas algorithm assumes 25 C / 50 %RH. Feeding the actual ambient values improves the TVOC/eCO2 estimate.

    chip.read_air_quality(aqi, tvoc, eco2);           // Read air quality, (aqi, tvoc_ppb, eco2_ppm) → bool
    chip.set_compensation(22.0f, 50.0f);              // Set compensation, (temp_celsius, rh_percent) → void
    chip.read_air_quality(aqi, tvoc, eco2);           // Read air quality, (aqi, tvoc_ppb, eco2_ppm) → bool
    vTaskDelay(pdMS_TO_TICKS(1000));
}
