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

    accel.set_range(8);                                     // Set measurement range, (range_g) → g
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz
    accel.set_filter_mode(1);                               // Set filter mode, (mode) → None
    accel.set_mode(0);                                      // Set mode, (mode) → None
    accel.set_resolution(14);                               // Set resolution, (bits) → None
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                             // Read raw 14-bit counts, (x, y, z) → int, int, int
    float temp = accel.read_temperature();                  // Read temperature, () → °C
    bool ready = accel.new_data_available();                // Check new data, () → bool
    accel.set_shadow(false);                                // Set shadow mode, (enabled) → None
    accel.set_sample_skip(false);                           // Set sample skip, (enabled) → None
    accel.set_low_g(0.3, 40, 0.05, 0x07, 0, true);           // Configure low-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → None
    accel.set_high_g(1.8, 20, 0.1, 0x07, 0, true);          // Configure high-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → None
    accel.set_slope(0.3, 3, 0x07, true);                     // Configure slope, (threshold_g, samples, axes, filtered) → None
    accel.set_alert(false);                                 // Toggle alert mode, (enabled) → None
    accel.set_tap(0.5, 250, 0x07, true);                     // Configure tap, (threshold_g, window_ms, axes, filtered) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None
    uint8_t status = accel.poll_interrupt();                // Read STATUS_REG3, () → bitmask
    accel.clear_interrupt();                                // Clear latched interrupts, () → None
    accel.set_wake_up(true, 80);                            // Set self-wake-up, (enabled, pause_ms) → ms
    float x, y, z;
    accel.read(x, y, z);                                   // Read 3-axis acceleration, (x, y, z) → g, g, g
    uint8_t al, ml;
    accel.read_version(al, ml);                            // Read version, (al_version, ml_version) → byte, byte
    uint8_t c1 = accel.read_customer(0);                    // Read scratch byte, (index) → byte
    accel.write_customer(1, 0xA5);                         // Write scratch byte, (index, value) → None
    bool st = accel.self_test();                           // Run self-test, () → bool
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None
    accel.soft_reset();                                    // Soft reset, () → None
    accel.sleep();                                         // Enter sleep mode, () → None
    accel.wake();                                          // Leave sleep mode, () → None
    printf("raw=(%d,%d,%d) temp=%.1f ready=%d status=0x%02X al=%u ml=%u c1=0x%02X st=%s\n",
           (int)rx, (int)ry, (int)rz, (double)temp, (int)ready, status, al, ml, c1, st ? "PASS" : "FAIL");
    return 0;
}