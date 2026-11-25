#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <unistd.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "BMA150.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    BMA150Full accel(connection);                       // Create BMA150 driver, (connection)

    // --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
    // ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
    // bandwidth is wide enough to capture a 2 ms high-g spike without
    // aliasing. Latched interrupts free the polling loop from having to
    // catch a transient.
    accel.set_range(8);                                  // Set measurement range, (range_g=2) → g
    accel.set_bandwidth(190);                            // Set bandwidth, (bandwidth_hz=25) → Hz
    accel.set_latch(true);                               // Set latched interrupts, (enabled=False) → None
    accel.set_low_g(0.4, 40);                            // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
    accel.set_high_g(4.0, 2);                            // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms

    const int DURATION_MS = 60'000;
    const int HEARTBEAT_MS = 1000;
    const int POLL_MS = 50;

    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    long elapsed_ms = 0;
    long last_heartbeat = 0;
    long last_poll = 0;

    // --- 60-second free-fall / shock logger ---
    // User is expected to drop or shake the board at some point during
    // the 60 s window. Between events the magnitude sits at ≈1.00 g
    // (gravity). Each latched interrupt is reported with a timestamp,
    // the latest (x, y, z), temperature, and a free-fall or shock tag.
    while (elapsed_ms < DURATION_MS) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        elapsed_ms = (now.tv_sec - start.tv_sec) * 1000
                   + (now.tv_nsec - start.tv_nsec) / 1'000'000;

        if (elapsed_ms - last_heartbeat >= HEARTBEAT_MS) {
            float x, y, z;
            accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
            float mag = sqrtf(x * x + y * y + z * z);
            float temp = accel.read_temperature();               // Read temperature, () → °C
            printf("%5ld  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\n",
                   elapsed_ms / 1000, (double)x, (double)y, (double)z, (double)mag, (double)temp);
            last_heartbeat = elapsed_ms;
        }

        if (elapsed_ms - last_poll >= POLL_MS) {
            uint8_t status = accel.poll_interrupt();             // Read STATUS, () → bitmask
            if (status & 0x08) {                                // STATUS_LG_LATCHED (bit 3)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5ld  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       elapsed_ms, (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                                // STATUS_HG_LATCHED (bit 2)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5ld  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       elapsed_ms, (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            last_poll = elapsed_ms;
        }

        usleep(10'000);
    }
    printf("done\n");
    return 0;
}
