#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HT16K33.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    HT16K33Full matrix(connection);                      // Create HT16K33 driver, (connection)
    stdio_init_all();
    sleep_ms(2000);

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    for (uint8_t com = 0; com < HT16K33Full::COMMONS; com++) {
        for (uint8_t row = 0; row < HT16K33Full::ROWS; row++) {
            matrix.clear();                               // Blank the display, () → void
            matrix.set_pixel(com, row);                   // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show();                                // Write the shadow RAM, () → void
            sleep_ms(50);
        }
    }

    static const uint8_t all_on[16] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                       0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    matrix.write_raw(all_on, 16, 0);                      // All LEDs on, (data, len, address) → bool
    for (uint8_t level = 0; level < 16; level++) {
        matrix.set_brightness(level);                     // Sweep dimming, (level 0–15) → void
        sleep_ms(100);
    }

    static const float blink_hz[4] = {2.0f, 1.0f, 0.5f, 0.0f};
    for (uint8_t i = 0; i < 4; i++) {
        matrix.set_blink(blink_hz[i]);                    // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        sleep_ms(3000);
    }

    matrix.set_int_output(true);                          // INT output, active low, (enable, active_high) → void
    for (uint16_t t = 0; t < 1000; t++) {                 // 1000 x 20 ms = 20 s
        uint64_t keys = matrix.poll_interrupt();          // Read keys if pending, () → uint64_t bitmask
        if (keys) printf("keys: 0x%x\n", (unsigned)(keys));
        sleep_ms(20);
    }

    matrix.standby();                                     // Oscillator off, () → void
    sleep_ms(1000);
    matrix.wake();                                        // Oscillator on, state restored, () → void

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
