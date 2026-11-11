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
static uint8_t g_hold_mask = 0x0F;
static int g_hold_steps = 0;

static void onButton(uint8_t changed) {
    uint8_t port = g_chip->read_port();
    printf("button changed=0x%02X port=0x%02X\n", changed, port);
    g_hold_mask = ~((changed >> 4) & 0x0F) & 0x0F;
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

    PCA6408AFull chip(connection);                            // Create PCA6408A driver, (connection, addr=0x20)
    g_chip = &chip;


    // --- Configure LEDs and buttons ---
    // P0–P3 are active-low LED outputs (0 lights the LED); P4–P7 stay inputs and rely on
    // external pull-ups (the chip has none), so a pressed button (to GND) reads 0.
    chip.write_port(0, 0xFF);                                 // Turn all LEDs off, (port=0, mask=0xFF) → void
    chip.set_direction(0xF0);                                 // P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT; onButton() prints the changed mask and the port state and
    // holds the LED matching the pressed button for one second.
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr) → void

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 40; step++) {
        uint8_t pos = step % 4;
        uint8_t leds = ~(1 << pos) & 0x0F;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_port(0, 0xF0 | leds);                      // Write LED outputs, (port=0, mask) → void
        uint8_t port_val = chip.read_port();                  // Read all 8 pins, (port=0) → uint8_t bitmask
        printf("  pos=%d  port=0x%02X\n", pos, port_val);
        vTaskDelay(pdMS_TO_TICKS(100));
        chip.service();                                       // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_port(0, 0xFF);                                 // Turn all LEDs off, (port=0, mask=0xFF) → void
}
