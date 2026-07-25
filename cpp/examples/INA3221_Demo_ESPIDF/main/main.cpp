// Auto-generated ESP-IDF example for INA3221 (Demo).
// Mirrors the Arduino INA3221_Demo example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "INA3221.h"

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
    INA3221Full chip(transport);  // Create INA3221 driver
    float v, sv, i, p;
    bool pvf, cr;
    uint16_t flags, mfr, die;
    uint8_t chans[2] = { 1, 2 };
    // --- Three-channel rail monitor ---
    // INA3221 monitors three independent rails. All three channels default to enabled in continuous shunt+bus mode.

    chip.voltage(1);                                  // Read bus voltage channel 1, (channel 1-3) → float V
    chip.current(1);                                  // Read current channel 1, (channel 1-3) → float A
    chip.power(1);                                    // Read power channel 1, (channel 1-3) → float W
    vTaskDelay(pdMS_TO_TICKS(1000));
}
