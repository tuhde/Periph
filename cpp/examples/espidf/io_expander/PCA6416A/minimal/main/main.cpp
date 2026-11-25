// Auto-generated ESP-IDF example for PCA6416A (Minimal).
// Mirrors the Arduino PCA6416A_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA6416A.h"

static PCA6416AMinimal* g_chip = nullptr;

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
    dev_cfg.device_address = 0x20;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCA6416AMinimal chip(connection);                         // Create PCA6416A driver, (connection, addr=0x20)
    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P0_0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(15);                                    // Get P1_7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P1_7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write port 0 output latch, (port, mask) → void
    uint8_t port_val = chip.read_port(1);                      // Read the 8 pins of port 1, (port) → uint8_t bitmask

    printf("PORT1=0x%02X  P0=%d\n", port_val, val);
}
