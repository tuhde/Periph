// Auto-generated ESP-IDF example for PCA6408A (Full).
// Mirrors the Arduino PCA6408A_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA6408A.h"

static PCA6408AFull* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    printf("interrupt, changed=0x%02X\n", changed);
}
static void onPin(PCA6408AFull::IOExpanderPin*) {
    printf("P4 falling\n");
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
    dev_cfg.device_address = 0x20;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCA6408AFull chip(connection);                            // Create PCA6408A driver, (connection, addr=0x20)
                                                              // writes the reset values; all pins start as inputs
    g_chip = &chip;

    chip.configure_polarity(0x00);                            // Normal input polarity, (mask=0x00) → void
                                                              // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0xF0);                                 // P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → void
                                                              // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                       // Read the direction shadow, () → uint8_t
                                                              // 1 = input, 0 = output
    printf("CONFIG=0x%02X\n", dir);

    for (uint8_t n = 0; n < 4; n++) {
        auto p = chip.pin(n);                                 // Get LED pin proxy, (n) → IOExpanderPin
                                                              // Full proxy with watch support
        p.mode(OUTPUT);                                       // Set pin direction, (m INPUT/OUTPUT) → bool
                                                              // latches OUTPUT, then clears the CONFIG bit
        p.low();                                              // Drive pin low, () → void
                                                              // clears the OUTPUT bit via the shadow
        p.toggle();                                           // Invert the output latch, () → void
                                                              // one OUTPUT write, no read-modify-write
    }

    chip.write_port(0, 0x05);                                 // Write the output latch, (port=0, mask) → void
                                                              // whole-port OUTPUT write, updates the shadow
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask
                                                              // reads INPUT (actual pin levels)
    printf("PORT=0x%02X\n", port_val);
    uint8_t config = chip.read_register(0x03);                // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printf("CONFIG=0x%02X\n", config);
    chip.write_register(0x02, 0x10);                          // Write raw register, (reg, value) → bool
                                                              // POLARITY: invert P4

    chip.onInterrupt(onChange);                               // Subscribe to INT, (callback, intPin=nullptr) → void
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(4).watch(onPin, InputPin::kFalling);             // Watch P4 falling edges, (handler, trigger) → bool
                                                              // filters the edge in software; all pins share the INT line
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INPUT; use when no INT pin is wired
    uint8_t changed = chip.pollInterrupt();                   // Read INPUT, get changed mask, () → uint8_t
                                                              // XOR with the last INPUT value
    printf("changed=0x%02X\n", changed);
    chip.pin(4).unwatch();                                    // Stop watching P4, () → void
                                                              // drops the handler
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}
