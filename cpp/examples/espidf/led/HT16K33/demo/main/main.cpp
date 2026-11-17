// Auto-generated ESP-IDF example for HT16K33 (Demo).
// Mirrors the Arduino HT16K33_Demo example using the
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

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    for (uint8_t com = 0; com < HT16K33Full::COMMONS; com++) {
        for (uint8_t row = 0; row < HT16K33Full::ROWS; row++) {
            matrix.clear();                               // Blank the display, () → void
            matrix.set_pixel(com, row);                   // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show();                                // Write the shadow RAM, () → void
            vTaskDelay(pdMS_TO_TICKS(50));
        }
    }

    static const uint8_t all_on[16] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                       0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    matrix.write_raw(all_on, 16, 0);                      // All LEDs on, (data, len, address) → bool
    for (uint8_t level = 0; level < 16; level++) {
        matrix.set_brightness(level);                     // Sweep dimming, (level 0–15) → void
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    static const float blink_hz[4] = {2.0f, 1.0f, 0.5f, 0.0f};
    for (uint8_t i = 0; i < 4; i++) {
        matrix.set_blink(blink_hz[i]);                    // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        vTaskDelay(pdMS_TO_TICKS(3000));
    }

    matrix.set_int_output(true);                          // INT output, active low, (enable, active_high) → void
    for (uint16_t t = 0; t < 1000; t++) {                 // 1000 x 20 ms = 20 s
        uint64_t keys = matrix.poll_interrupt();          // Read keys if pending, () → uint64_t bitmask
        if (keys) printf("keys: 0x%x\n", (unsigned)(keys));
        vTaskDelay(pdMS_TO_TICKS(20));
    }

    matrix.standby();                                     // Oscillator off, () → void
    vTaskDelay(pdMS_TO_TICKS(1000));
    matrix.wake();                                        // Oscillator on, state restored, () → void
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
