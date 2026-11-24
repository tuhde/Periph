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

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    bool ok = true;
    for (uint16_t n = 0; n < 10000; n += 37) {
        ok = display.show_number(n);                                // Show the counter, (value 0–9999) → bool
    sleep_ms(10);
    }
    const int8_t digits[4] = {1, 2, 3, 4};
    ok = display.show_digits(digits);                               // Show four digits, (digits) → bool
    for (uint8_t pos = 1; pos <= 4; pos++) {
        ok = display.set_decimal_point(pos, true);                  // Light a decimal point, (position, on) → bool
    sleep_ms(400);
        ok = display.set_decimal_point(pos, false);
    }
    const uint8_t currents[4] = {3, 9, 15, 21};
    for (uint8_t i = 0; i < 4; i++) {
        ok = display.set_current(currents[i]);                      // Step the segment current, (ma) → bool
        ok = display.show_number(8888);
    sleep_ms(1000);
    }
    ok = display.set_current(12);
    display.set_mode(false);                                        // Static mode, (dynamic) → void
    sleep_ms(2000);
    display.set_mode(true);
    display.segment_test(true);                                     // Segment test, (on) → void
    sleep_ms(2000);
    display.segment_test(false);
    printf("status 0x%02X\n", (unsigned)(display.read_status()));   // Read the status byte, () → uint8_t
    printf("power reset %lu\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
    display.clear();                                                // Blank all digits, () → void
    printf("ok %lu\n", (unsigned long)(ok));

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
