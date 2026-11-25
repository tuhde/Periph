// Auto-generated ESP-IDF example for PCA6416A (Full).
// Mirrors the Arduino PCA6416A_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA6416A.h"

static PCA6416AFull* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint16_t changed) {
    printf("interrupt, changed=0x%04X\n", changed);
}
static void onPin(PCA6416AFull::IOExpanderPin*) {
    printf("P1_4 falling\n");
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

    PCA6416AFull chip(connection);                            // Create PCA6416A driver, (connection, addr=0x20)
                                                              // writes the reset values; all pins start as inputs
    g_chip = &chip;

    chip.configure_polarity(0x0000);                            // Normal input polarity, (mask=0x0000) → void
                                                              // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0xFF00);                                 // Port 0 outputs, port 1 inputs, (mask=0xFF00) → void
                                                              // OUTPUT is written before CONFIG (no glitch)
    uint16_t dir = chip.get_direction();                       // Read the direction shadow, () → uint16_t
                                                              // 1 = input, 0 = output
    printf("CONFIG=0x%04X\n", dir);

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

    chip.write_port(0, 0x05);                                 // Write port 0 output latch, (port, mask) → void
                                                              // single-port OUTPUT write, updates the shadow
    chip.write_all(0xA505);                                    // Write both output latches, (value) → void
                                                               // one two-byte write at OUTPUT0 (register-pair toggling)
    uint8_t port_val = chip.read_port(1);                      // Read port 1 pins, (port) → uint8_t bitmask
                                                              // reads INPUT (actual pin levels)
    printf("PORT1=0x%02X\n", port_val);
    uint16_t all = chip.read_all();                            // Read all 16 pins, () → uint16_t
                                                               // one two-byte read at INPUT0
    printf("ALL=0x%04X\n", all);
    uint8_t config = chip.read_register(0x07);                // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printf("CONFIG1=0x%02X\n", config);
    chip.write_register(0x05, 0x10);                          // Write raw register, (reg, value) → bool
                                                              // POLARITY1: invert P1_4

    chip.onInterrupt(onChange);                               // Subscribe to INT, (callback, intPin=nullptr) → void
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(12).watch(onPin, InputPin::kFalling);             // Watch P1_4 falling edges, (handler, trigger) → bool
                                                              // filters the edge in software; all pins share the INT line
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INPUT; use when no INT pin is wired
    uint16_t changed = chip.pollInterrupt();                   // Read INPUT0/1, get changed mask, () → uint16_t
                                                              // XOR with the last INPUT values
    printf("changed=0x%04X\n", changed);
    chip.pin(12).unwatch();                                    // Stop watching P1_4, () → void
                                                              // drops the handler
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
}
