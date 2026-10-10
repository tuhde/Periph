#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMA180.h"

#ifndef BMA180_I2C_NODE
#define BMA180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMA180_ADDR
#define BMA180_ADDR 0x40
#endif

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(BMA180_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BMA180_ADDR);
    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

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
        printk("|a|=%.3f g  T=%.1f C\n", (double)mag, (double)t);

        uint8_t flags = accel.poll_interrupt();            // Read STATUS_REG3, () → bitmask
        if (flags & 0x10) {
            printk("DOUBLE TAP\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        if (flags & 0x40) {
            printk("FREE FALL\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        k_sleep(K_MSEC(100));
    }
    return 0;
}