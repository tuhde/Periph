#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "BMA150.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define BMA150_ADDR 0x38

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BMA150_ADDR);
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

    int64_t start = k_uptime_get();
    int64_t last_heartbeat = 0;
    int64_t last_poll = 0;

    // --- 60-second free-fall / shock logger ---
    // User is expected to drop or shake the board at some point during
    // the 60 s window. Between events the magnitude sits at ≈1.00 g
    // (gravity). Each latched interrupt is reported with a timestamp,
    // the latest (x, y, z), temperature, and a free-fall or shock tag.
    while (k_uptime_get() - start < 60000) {
        int64_t now = k_uptime_get();

        if (now - last_heartbeat >= 1000) {
            float x, y, z;
            accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
            float mag = sqrtf(x * x + y * y + z * z);
            float temp = accel.read_temperature();               // Read temperature, () → °C
            printk("%5lld  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\n",
                   (long long)((now - start) / 1000), (double)x, (double)y, (double)z, (double)mag, (double)temp);
            last_heartbeat = now;
        }

        if (now - last_poll >= 50) {
            uint8_t status = accel.poll_interrupt();             // Read STATUS, () → bitmask
            if (status & 0x08) {                                // STATUS_LG_LATCHED (bit 3)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printk("%5lld  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                                // STATUS_HG_LATCHED (bit 2)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printk("%5lld  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            last_poll = now;
        }

        k_sleep(K_MSEC(10));
    }
    printk("done\n");
    return 0;
}
