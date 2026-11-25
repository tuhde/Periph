// Auto-generated ESP-IDF example for PCA9536 (Full).
// Mirrors the Arduino PCA9536_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9536.h"

static PCA9536Full* g_chip = nullptr;
static uint8_t g_hold_mask = 0x03;
static int g_hold_steps = 0;

static void onButton(uint8_t changed) {
    uint8_t port = g_chip->read_port();
    printf("button changed=0x%X port=0x%X\n", changed, port);
    g_hold_mask = ~((changed >> 2) & 0x03) & 0x03;
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
    dev_cfg.device_address = 0x41;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCA9536Full chip(connection);                           // Create PCA9536 driver, (connection, addr=0x41)
    g_chip = &chip;

    // --- Configure LEDs and buttons ---
    // P0–P1 are active-low LED outputs (0 lights the LED); P2–P3 stay inputs and rely on the
    // built-in weak pull-up, so a pressed button (to GND) reads 0.
    chip.write_port(0, 0x0F);                                   // Turn all LEDs off, (port=0, mask=0x0F) → void
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void

    // --- Watch for input changes ---
    // The chip has no INT pin: service() polls INPUT, and onButton() prints the changed mask
    // and the port state and holds the LED matching the pressed button for one second.
    chip.watchChanges(onButton);                                // Register change callback, (callback) → void

    // --- Blink loop ---
    for (int step = 0; step < 40; step++) {
        uint8_t leds = (step % 2 == 0) ? 0x02 : 0x01;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, 0x0C | leds);                        // Write LED outputs, (port=0, mask) → void
        vTaskDelay(pdMS_TO_TICKS(100));
        chip.service();                                         // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.unwatchChanges();                                      // Remove change callback, () → void
    chip.write_port(0, 0x0F);                                   // Turn all LEDs off, (port=0, mask=0x0F) → void
    while (1) { vTaskDelay(pdMS_TO_TICKS(1000)); }
}
