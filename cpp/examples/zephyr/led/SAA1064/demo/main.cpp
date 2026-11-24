#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SAA1064.h"

#ifndef SAA1064_I2C_NODE
#define SAA1064_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SAA1064_ADDR
#define SAA1064_ADDR 0x38
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SAA1064_I2C_NODE);
    I2CConnectionZephyr connection(dev, SAA1064_ADDR);

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The SAA1064 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once
    // per second and the terminal mirrors the value sent to the display.
    SAA1064Full display(connection);                                // Create SAA1064 driver, (connection)

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    bool ok = true;
    for (uint16_t n = 0; n < 10000; n += 37) {
        ok = display.show_number(n);                                // Show the counter, (value 0–9999) → bool
    k_sleep(K_MSEC(10));
    }
    const int8_t digits[4] = {1, 2, 3, 4};
    ok = display.show_digits(digits);                               // Show four digits, (digits) → bool
    for (uint8_t pos = 1; pos <= 4; pos++) {
        ok = display.set_decimal_point(pos, true);                  // Light a decimal point, (position, on) → bool
    k_sleep(K_MSEC(400));
        ok = display.set_decimal_point(pos, false);
    }
    const uint8_t currents[4] = {3, 9, 15, 21};
    for (uint8_t i = 0; i < 4; i++) {
        ok = display.set_current(currents[i]);                      // Step the segment current, (ma) → bool
        ok = display.show_number(8888);
    k_sleep(K_MSEC(1000));
    }
    ok = display.set_current(12);
    display.set_mode(false);                                        // Static mode, (dynamic) → void
    k_sleep(K_MSEC(2000));
    display.set_mode(true);
    display.segment_test(true);                                     // Segment test, (on) → void
    k_sleep(K_MSEC(2000));
    display.segment_test(false);
    printk("status 0x%02X\n", (unsigned)(display.read_status()));   // Read the status byte, () → uint8_t
    printk("power reset %lu\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
    display.clear();                                                // Blank all digits, () → void
    printk("ok %lu\n", (unsigned long)(ok));

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
