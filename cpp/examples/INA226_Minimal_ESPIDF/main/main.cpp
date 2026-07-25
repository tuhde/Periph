// Auto-generated ESP-IDF example for INA226 (Minimal).
// Mirrors the Arduino INA226_Minimal example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
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

    I2CTransportESPIDF transport(dev);
    INA226Minimal chip(transport);  // Create INA226 driver
    float v, sv, i, p;
    bool cr, ov;
    uint16_t flags, mfr, die;
    while (1) {
    chip.voltage();                                   // Read bus voltage, () → float V
    chip.current();                                   // Read current, () → float A
    chip.power();                                     // Read power, () → float W
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
