#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "SAA1064.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x38);
    SAA1064Full display(connection);                                // Create SAA1064 driver, (connection)
    const int8_t digits[4] = {1, 2, 3, 4};
    bool ok = display.show_digits(digits);                          // Show four digits, (digits int8_t[4], 0–9 or BLANK) → bool
                                                                    // default mapping a=bit0 … g=bit6, dp=bit7
    sleep_ms(1000);
    ok = display.show_number(42);                                   // Show a number, (value 0–9999) → bool
                                                                    // leading zeros are blanked
    const uint8_t raw[2] = {0x7F, 0x7F};
    ok = display.write_raw(raw, 2, 3);                              // Write raw segment bytes, (patterns, len 1–4, first_digit 1–4) → bool
                                                                    // digits 3 and 4 show 8
    sleep_ms(1000);
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
    sleep_ms(1000);
    display.set_mode(true);
    display.blank(true, false);                                     // Blank digit pairs, (digits_13, digits_24) → void
                                                                    // digits 1+3 off
    sleep_ms(1000);
    display.blank(false, false);
    display.segment_test(true);                                     // Segment test, (on) → void
                                                                    // lights every segment
    sleep_ms(1000);
    display.segment_test(false);
    printf("status 0x%02X\n", (unsigned)(display.read_status()));   // Read the status byte, () → uint8_t
    printf("power reset %lu\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
                                                                    // set after power-up, clears when read
    display.write_control(0x47);                                    // Write the raw control byte, (value 0–127) → void
                                                                    // dynamic mode, unblanked, 12 mA
    display.clear();                                                // Blank all digits, () → void
    printf("ok %lu\n", (unsigned long)(ok));

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
