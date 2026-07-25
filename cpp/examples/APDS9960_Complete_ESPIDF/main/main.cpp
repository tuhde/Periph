// Auto-generated ESP-IDF example for APDS9960 (Complete).
// Mirrors the Arduino APDS9960_Complete example using the
// I2CTransportESPIDF transport.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CTransportESPIDF.h"
#include "APDS9960.h"

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
        .device_address  = 0x39,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CTransportESPIDF transport(dev);
    APDS9960Full chip(transport);  // Create APDS9960 driver
    uint16_t c, r, g, b;
    uint8_t fifo_buf[128];
    uint8_t n;
    chip.chip_id();                                   // Read device ID, () → uint8_t
    chip.color(c, r, g, b);                           // Read all RGBC channels, (clear, red, green, blue) → void
    // burst read 0x94-0x9B latches all channels atomically
    chip.color_clear();                               // Read clear channel, () → uint16_t
    chip.color_red();                                 // Read red channel, () → uint16_t
    chip.color_green();                               // Read green channel, () → uint16_t
    chip.color_blue();                                // Read blue channel, () → uint16_t
    chip.configure_als(0xB6, 1);                      // Configure ALS, (atime 0-255, again 0-3) → void
    // sets integration time and gain for the ALS/color engine
    chip.configure_wait(0xFF, false);                 // Configure wait, (wtime 0-255, wlong=false) → void
    // sets idle period between measurement cycles
    chip.enable_wait(true);                           // Enable wait engine, (enabled) → void
    chip.enable_proximity(true);                      // Enable proximity engine, (enabled) → void
    chip.configure_proximity_led(0, 0, 0, 1);         // Configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → void
    // sets LED drive strength, gain, pulse count and length
    chip.set_led_boost(0);                            // Set LED boost, (boost 0-3) → void
    // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
    chip.proximity();                                 // Read proximity count, () → uint8_t
    chip.als_threshold(100, 60000);                   // Set ALS thresholds, (low 0-65535, high 0-65535) → void
    chip.proximity_threshold(10, 200);                // Set proximity thresholds, (low 0-255, high 0-255) → void
    chip.set_persistence(0, 1);                       // Set persistence, (ppers 0-15, apers 0-15) → void
    chip.enable_als_interrupt(true);                  // Enable ALS interrupt, (enabled) → void
    chip.enable_proximity_interrupt(true);            // Enable proximity interrupt, (enabled) → void
    chip.clear_als_interrupt();                       // Clear ALS interrupt, () → void
    chip.clear_proximity_interrupt();                 // Clear proximity interrupt, () → void
    chip.clear_all_interrupts();                      // Clear all interrupts, () → void
    chip.set_proximity_offset(10, -5);                // Set proximity offset, (ur -127..127, dl -127..127) → void
    // sign-magnitude encoding compensates for optical crosstalk
    chip.set_proximity_mask(false, false, false, false);  // Set proximity mask, (u, d, l, r) → void
    chip.enable_gesture(true);                        // Enable gesture engine, (enabled) → void
    chip.configure_gesture(1, 0, 0, 1, 1, 50, 20);    // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
    // sets gain, LED drive, pulse, wait time, entry/exit thresholds
    chip.gesture_available();                         // Check gesture data, () → bool
    chip.gesture_fifo_level();                        // Read FIFO level, () → uint8_t
    chip.read_gesture_fifo(fifo_buf, 32);             // Read gesture FIFO, (buf, max_len) → uint8_t
    chip.clear_gesture_fifo();                        // Clear gesture FIFO, () → void
    chip.enable_gesture_interrupt(false);             // Enable gesture interrupt, (enabled) → void
    chip.enable_gesture(false);                       // Disable gesture engine, (enabled) → void
    chip.status();                                    // Read STATUS register, () → uint8_t
    chip.is_als_valid();                              // Check ALS data valid, () → bool
    chip.is_proximity_valid();                        // Check proximity valid, () → bool
    chip.is_als_saturated();                          // Check ALS saturated, () → bool
    chip.is_proximity_saturated();                    // Check proximity saturated, () → bool
    chip.enable_proximity(false);                     // Disable proximity engine, (enabled) → void
    vTaskDelay(pdMS_TO_TICKS(1000));
}
