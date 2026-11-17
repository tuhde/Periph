// Auto-generated ESP-IDF example for HT16K33 (Complete).
// Mirrors the Arduino HT16K33_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HT16K33.h"

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
    dev_cfg.device_address = 0x70;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HT16K33Full matrix(connection);                      // Create HT16K33 driver, (connection)

    matrix.clear();                                       // Blank the display, () → void
                                                          // zeros the shadow RAM and writes it
    matrix.set_pixel(2, 5);                               // Set one LED, (com 0–7, row 0–15, on) → bool
    matrix.show();                                        // Write the shadow RAM, () → void
    bool lit = matrix.get_pixel(2, 5);                    // Read a pixel from the shadow RAM, (com, row) → bool
    (void)lit;
    static const uint8_t raw[2] = {0xFF, 0x0F};
    matrix.write_raw(raw, 2, 0);                          // Write raw RAM bytes, (data, len 1–16, address 0–15) → bool
                                                          // com 0 rows 0–11 lit
    matrix.set_brightness(8);                             // Set dimming, (level 0–15) → void
                                                          // 9/16 duty
    matrix.set_blink(1.0f);                               // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
                                                          // blinks at 1 Hz
    vTaskDelay(pdMS_TO_TICKS(3000));
    matrix.set_blink(0.0f);
    matrix.display_off();                                 // Display output off, () → void
    matrix.display_on();                                  // Display output on, () → void
    matrix.set_int_output(true, false);                   // ROW15/INT pin as INT output, (enable, active_high) → void
                                                          // active low interrupt output
    uint64_t keys = matrix.read_keys();                   // Read and clear key data, () → uint64_t bitmask
    (void)keys;
    bool pressed = matrix.key_pressed();                  // Read the INT flag, () → bool
    (void)pressed;
    uint64_t polled = matrix.poll_interrupt();            // Read keys if INT flag set, () → uint64_t (0 if none)
    (void)polled;
    matrix.on_interrupt([](uint64_t k) { (void)k; });     // Subscribe to key presses, (callback, intPin) → void
                                                          // polls via service() when no INT pin is wired
    matrix.service();                                     // Poll once and dispatch, () → void
    matrix.off_interrupt();                               // Unsubscribe, () → void
    matrix.standby();                                     // Oscillator off, () → void
    matrix.wake();                                        // Oscillator on and restore state, () → void
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
