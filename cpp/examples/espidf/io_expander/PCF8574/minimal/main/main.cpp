// Auto-generated ESP-IDF example for PCF8574 (Minimal).
// Mirrors the Arduino PCF8574_Minimal example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "PCF8574.h"

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
        .device_address  = 0x20,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    PCF8574Minimal chip(transport, 0x20);  // Create PCF8574 driver
    uint8_t val;
    uint8_t mask;
    while (1) {
    chip.read_port();                                // Read all 8 pins, (port) → uint8_t bitmask
    chip.write_port(0xAA);                         // Write all 8 pins, (port, mask) → void
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
