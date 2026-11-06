// Auto-generated ESP-IDF example for MCP23008 (Minimal).
// Mirrors the Arduino MCP23008_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP23008.h"

static MCP23008Full* g_chip = nullptr;
static uint8_t g_hold_mask = 0;
static int g_hold_steps = 0;

static void onButton(uint8_t status) {
    uint8_t captured = g_chip->read_capture();
    printf("button INTF=0x%02X INTCAP=0x%02X\n", status, captured);
    g_hold_mask = (status >> 4) & 0x0F;
    g_hold_steps = 10;
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

    MCP23008Full chip(connection);                            // Create MCP23008 driver, (connection, addr=0x20)
    g_chip = &chip;

    // --- Configure LEDs and buttons ---
    // GP0–GP3 are LED outputs; GP4–GP7 stay inputs with the 100 kΩ pull-ups on,
    // so a pressed button (to GND) reads 0.
    for (uint8_t n = 0; n < 4; n++) {
        chip.pin(n).mode(OUTPUT);                             // Set LED pin direction, (m INPUT/OUTPUT) → void
    }
    chip.configure_pullup(0xF0);                              // Enable pull-ups on GP4–GP7, (mask=0xF0) → void

    // --- Arm interrupt-on-change on the buttons ---
    // Any button edge raises INT; onButton() prints INTF and the INTCAP snapshot and
    // holds the LED matching the pressed button for one second.
    chip.enable_pin_interrupt(0xF0);                          // Arm GP4–GP7, (mask) → void
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr, mode) → bool

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 40; step++) {
        uint8_t pos = step % 4;
        uint8_t leds = 1 << pos;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, leds);                             // Write LED outputs, (port=0, mask) → void
        uint8_t port_val = chip.read_port();                  // Read all 8 pins, (port=0) → uint8_t bitmask
        printf("  pos=%d  port=0x%02X\n", pos, port_val);
        vTaskDelay(pdMS_TO_TICKS(100));
        chip.service();                                       // Poll for button interrupts, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_port(0, 0x00);                                 // Clear all outputs, (port=0, mask=0x00) → void
}
