// Auto-generated ESP-IDF example for SAA1064 (Demo).
// Mirrors the Arduino SAA1064_Demo example using the
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

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    bool ok = true;
    for (uint16_t n = 0; n < 10000; n += 37) {
        ok = display.show_number(n);                                // Show the counter, (value 0–9999) → bool
    vTaskDelay(pdMS_TO_TICKS(10));
    }
    const int8_t digits[4] = {1, 2, 3, 4};
    ok = display.show_digits(digits);                               // Show four digits, (digits) → bool
    for (uint8_t pos = 1; pos <= 4; pos++) {
        ok = display.set_decimal_point(pos, true);                  // Light a decimal point, (position, on) → bool
    vTaskDelay(pdMS_TO_TICKS(400));
        ok = display.set_decimal_point(pos, false);
    }
    const uint8_t currents[4] = {3, 9, 15, 21};
    for (uint8_t i = 0; i < 4; i++) {
        ok = display.set_current(currents[i]);                      // Step the segment current, (ma) → bool
        ok = display.show_number(8888);
    vTaskDelay(pdMS_TO_TICKS(1000));
    }
    ok = display.set_current(12);
    display.set_mode(false);                                        // Static mode, (dynamic) → void
    vTaskDelay(pdMS_TO_TICKS(2000));
    display.set_mode(true);
    display.segment_test(true);                                     // Segment test, (on) → void
    vTaskDelay(pdMS_TO_TICKS(2000));
    display.segment_test(false);
    printf("status 0x%02X\n", (unsigned)(display.read_status()));   // Read the status byte, () → uint8_t
    printf("power reset %lu\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
    display.clear();                                                // Blank all digits, () → void
    printf("ok %lu\n", (unsigned long)(ok));

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
