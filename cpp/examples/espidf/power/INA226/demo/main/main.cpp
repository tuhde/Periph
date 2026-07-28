// Auto-generated ESP-IDF example for INA226 (Demo).
// Mirrors the Arduino INA226_Demo example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "INA226.h"

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
        .device_address  = 0x40,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    INA226Full chip(connection);  // Create INA226 driver
    float v, sv, i, p;
    bool cr, ov;
    uint16_t flags, mfr, die;
    // --- 16-bit bus monitor with averaging ---
    // The INA226 has 16-bit ADCs and a programmable averaging engine. 1024-sample averaging (AVG=7) crushes switching noise.

    chip.voltage();                                   // Read bus voltage, () → float V
    chip.current();                                   // Read current, () → float A
    chip.power();                                     // Read power, () → float W
    chip.configure(3, 4, 4, 7);                       // Configure, (avg 0-7, vbus_ct 0-7, vsh_ct 0-7, mode 0-7) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
