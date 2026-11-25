#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMA180.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz

    // --- Calibrate zero-g while the board sits level ---
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None

    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    accel.set_tap(0.5f, 250);                               // Configure tap, (threshold_g, window_ms) → None
    accel.set_low_g(0.3f, 40);                              // Configure low-g, (threshold_g, duration_ms) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    for (int i = 0; i < 600; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float pitch = atan2f(x, sqrtf(y * y + z * z)) * 180.0f / 3.14159265f;
        float roll  = atan2f(y, sqrtf(x * x + z * z)) * 180.0f / 3.14159265f;
        float mag   = sqrtf(x * x + y * y + z * z);
        float t     = accel.read_temperature();             // Read temperature, () → °C
        printf("pitch=%+.1f roll=%+.1f |a|=%.3f g  T=%+.1f C\n",
               (double)pitch, (double)roll, (double)mag, (double)t);

        uint8_t flags = accel.poll_interrupt();            // Read STATUS_REG3, () → bitmask
        if (flags & 0x10) {
            printf("DOUBLE TAP\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        if (flags & 0x40) {
            printf("FREE FALL\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }

        usleep(100000);
    }
    return 0;
}