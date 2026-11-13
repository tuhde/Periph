// Auto-generated ESP-IDF example for PCA9539 (Full).
// Mirrors the Arduino PCA9539_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9539.h"

static PCA9539Full* g_chip = nullptr;
static uint8_t g_hold_mask = 0xFF;
static int g_hold_steps = 0;

static void onButton(uint16_t changed) {
    uint16_t all = g_chip->read_all();
    printf("button changed=0x%04X all=0x%04X\n", changed, all);
    g_hold_mask = ~((changed >> 8) & 0xFF) & 0xFF;
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
    dev_cfg.device_address = 0x74;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    PCA9539Full chip(connection);                            // Create PCA9539 driver, (connection, addr=0x74)
    g_chip = &chip;


    // --- Configure LEDs and buttons ---
    // Port 0 holds the active-low LED outputs (0 lights the LED); port 1 stays input and relies
    // on external pull-ups (the chip has none), so a pressed button (to GND) reads 0. Polarity
    // inversion makes a pressed button read as 1.
    chip.write_all(0xFFFF);                                   // Turn all LEDs off, (value=0xFFFF) → void
    chip.set_direction(0xFF00);                                // Port 0 outputs, port 1 inputs, (mask=0xFF00) → void
    chip.configure_polarity(0xFF00);                          // Invert port 1 inputs, (mask=0xFF00) → void

    // --- Subscribe to input changes ---
    // Any button edge raises INT; onButton() prints the 16-bit changed mask and the pin state and
    // holds the LED matching the pressed button for one second.
    chip.onInterrupt(onButton);                               // Subscribe to INT, (callback, intPin=nullptr) → void

    // --- Scanner loop ---
    // service() stands in for the INT line when none is wired.
    for (int step = 0; step < 80; step++) {
        uint8_t pos = step % 8;
        uint8_t leds = ~(1 << pos) & 0xFF;
        if (g_hold_steps > 0) { leds = g_hold_mask; g_hold_steps--; }
        chip.write_all(0xFF00 | leds);                           // Write LED outputs, (value) → void
        uint16_t all = chip.read_all();                      // Read all 16 pins, () → uint16_t
        printf("  pos=%d  all=0x%04X\n", pos, all);
        vTaskDelay(pdMS_TO_TICKS(100));
        chip.service();                                       // Poll for button changes, () → void
    }

    // --- Shut down ---
    chip.offInterrupt();                                      // Unsubscribe, () → void
    chip.write_all(0xFFFF);                                   // Turn all LEDs off, (value=0xFFFF) → void
}
