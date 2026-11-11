// Auto-generated ESP-IDF example for PCA9536 (Minimal).
// Mirrors the Arduino PCA9536_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9536.h"

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
    dev_cfg.device_address = 0x41;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCA9536Minimal chip(connection);                        // Create PCA9536 driver, (connection, addr=0x41)
    auto p2 = chip.pin(2);                                      // Get P2 proxy, (n) → IOExpanderPin
    auto p0 = chip.pin(0);                                      // Get P0 proxy, (n) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set pin direction, (m INPUT/OUTPUT) → bool
    p0.high();                                                  // Drive P0 high, () → void
    p2.mode(INPUT);                                             // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p2.read();                                    // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x09);                                   // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask

    printf("PORT=0x%X  P2=%d\n", port_val, val);
    while (1) { vTaskDelay(pdMS_TO_TICKS(1000)); }
}
