// Auto-generated ESP-IDF example for SAA1064 (Complete).
// Mirrors the Arduino SAA1064_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "SAA1064.h"

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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    SAA1064Full display(connection);                                // Create SAA1064 driver, (connection)
    const int8_t digits[4] = {1, 2, 3, 4};
    bool ok = display.show_digits(digits);                          // Show four digits, (digits int8_t[4], 0–9 or BLANK) → bool
                                                                    // default mapping a=bit0 … g=bit6, dp=bit7
    vTaskDelay(pdMS_TO_TICKS(1000));
    ok = display.show_number(42);                                   // Show a number, (value 0–9999) → bool
                                                                    // leading zeros are blanked
    const uint8_t raw[2] = {0x7F, 0x7F};
    ok = display.write_raw(raw, 2, 3);                              // Write raw segment bytes, (patterns, len 1–4, first_digit 1–4) → bool
                                                                    // digits 3 and 4 show 8
    vTaskDelay(pdMS_TO_TICKS(1000));
    ok = display.show_digit(2, 7, true);                            // Show one digit, (position 1–4, value 0–9 or BLANK, dp) → bool
                                                                    // digit 2 = 7 with decimal point
    ok = display.set_decimal_point(2, false);                       // Switch a decimal point, (position 1–4, on) → bool
                                                                    // keeps the cached segments
    const uint8_t map[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    ok = display.set_segment_map(map);                              // Remap segment bits, (map a–g, dp → bit 0–7) → bool
    ok = display.set_current(21);                                   // Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → bool
                                                                    // C4–C6
    display.set_mode(false);                                        // Select static/dynamic mode, (dynamic) → void
                                                                    // static shows only digits 1 and 2
    vTaskDelay(pdMS_TO_TICKS(1000));
    display.set_mode(true);
    display.blank(true, false);                                     // Blank digit pairs, (digits_13, digits_24) → void
                                                                    // digits 1+3 off
    vTaskDelay(pdMS_TO_TICKS(1000));
    display.blank(false, false);
    display.segment_test(true);                                     // Segment test, (on) → void
                                                                    // lights every segment
    vTaskDelay(pdMS_TO_TICKS(1000));
    display.segment_test(false);
    printf("status 0x%02X\n", (unsigned)(display.read_status()));   // Read the status byte, () → uint8_t
    printf("power reset %lu\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
                                                                    // set after power-up, clears when read
    display.write_control(0x47);                                    // Write the raw control byte, (value 0–127) → void
                                                                    // dynamic mode, unblanked, 12 mA
    display.clear();                                                // Blank all digits, () → void
    printf("ok %lu\n", (unsigned long)(ok));

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
