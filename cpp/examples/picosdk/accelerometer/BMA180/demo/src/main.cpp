#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMA180.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

    stdio_init_all();

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz
    // --- Calibrate zero-g while the board sits level ---
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None
    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    accel.set_tap(0.5, 250);                               // Configure tap, (threshold_g, window_ms) → None
    accel.set_low_g(0.3, 40);                              // Configure low-g, (threshold_g, duration_ms) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    for (int i = 0; i < 600; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        float t   = accel.read_temperature();               // Read temperature, () → °C
        printf("|a|=%.3f g  T=%.1f C\n", (double)mag, (double)t);

        uint8_t flags = accel.poll_interrupt();            // Read STATUS_REG3, () → bitmask
        if (flags & 0x10) {
            printf("DOUBLE TAP\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        if (flags & 0x40) {
            printf("FREE FALL\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        sleep_ms(100);
    }
    return 0;
}