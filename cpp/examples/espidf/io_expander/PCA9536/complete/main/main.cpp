// Auto-generated ESP-IDF example for PCA9536 (Full).
// Mirrors the Arduino PCA9536_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9536.h"

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    printf("changed=0x%X\n", changed);
}
static void onPin(PCA9536Full::IOExpanderPin*) {
    printf("P2 falling\n");
}

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

    PCA9536Full chip(connection);                           // Create PCA9536 driver, (connection, addr=0x41)
                                                            // writes the reset values; all pins start as inputs
    chip.configure_polarity(0x00);                              // Normal input polarity, (mask=0x00) → void
                                                            // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void
                                                            // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                         // Read the direction shadow, () → uint8_t
                                                            // 1 = input, 0 = output
    printf("CONFIG=0x%X\n", dir);

    for (uint8_t n = 0; n < 2; n++) {
        auto p = chip.pin(n);                                   // Get LED pin proxy, (n) → IOExpanderPin
                                                            // Full proxy with watch support
        p.mode(OUTPUT);                                         // Set pin direction, (m INPUT/OUTPUT) → bool
                                                            // latches OUTPUT, then clears the CONFIG bit
        p.low();                                                // Drive pin low, () → void
                                                            // clears the OUTPUT bit via the shadow
        p.toggle();                                             // Invert the output latch, () → void
                                                            // one OUTPUT write, no read-modify-write
    }

    chip.write_port(0, 0x01);                                   // Write the output latch, (port=0, mask) → void
                                                            // whole-port OUTPUT write, updates the shadow
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask
                                                            // reads INPUT (actual pin levels)
    printf("PORT=0x%X\n", port_val);
    uint8_t config = chip.read_register(0x03);                  // Read raw register, (reg) → uint8_t
                                                            // escape hatch for debugging
    printf("CONFIG=0x%02X\n", config);
    chip.write_register(0x02, 0x04);                            // Write raw register, (reg, value) → bool
                                                            // POLARITY: invert P2

    chip.watchChanges(onChange);                                // Register change callback, (callback) → void
                                                            // no INT pin exists; service() polls and dispatches
    chip.pin(2).watch(onPin, InputPin::kFalling);               // Watch P2 falling edges, (handler, trigger) → bool
                                                            // filters the edge in software; all pins share one poll
    chip.service();                                             // Poll once and dispatch callbacks, () → void
                                                            // reads INPUT; call periodically from the main loop
    uint8_t changed = chip.pollChanges();                       // Read INPUT, get changed mask, () → uint8_t
                                                            // XOR with the last INPUT value
    printf("changed=0x%X\n", changed);
    chip.pin(2).unwatch();                                      // Stop watching P2, () → void
                                                            // drops the handler
    chip.unwatchChanges();                                      // Remove change callback, () → void
                                                            // stops dispatch
    chip.reset();                                               // Restore reset values, () → void
                                                            // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    while (1) { vTaskDelay(pdMS_TO_TICKS(1000)); }
}
