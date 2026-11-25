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
    sleep_ms(3000);
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

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
