// Auto-generated ESP-IDF example for BMP180 (Demo).
// Mirrors the Arduino BMP180_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP180.h"

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
        .device_address  = 0x77,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMP180Full chip(connection);  // Create BMP180 driver
    float t, p, alt;
    uint8_t cid;
    int oss;
    // --- Read T/P at high resolution ---
    // BMP180 supports 4 oversampling settings; standard (OSS=1) is the most common balance of noise vs conversion time.

    chip.set_oversampling(BMP180Full::OSS_STANDARD);  // Set OSS, (oss 0-3) → void
    chip.temperature();                               // Read temperature, () → float °C
    chip.pressure();                                  // Read pressure, () → float hPa
    chip.altitude();                                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    vTaskDelay(pdMS_TO_TICKS(1000));
}
