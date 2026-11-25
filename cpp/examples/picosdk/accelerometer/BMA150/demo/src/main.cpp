#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMA150.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x38);
    BMA150Full accel(connection);                       // Create BMA150 driver, (connection)

    stdio_init_all();

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

    absolute_time_t start = get_absolute_time();
    int64_t last_heartbeat = 0;
    int64_t last_poll = 0;

    // --- 60-second free-fall / shock logger ---
    // User is expected to drop or shake the board at some point during
    // the 60 s window. Between events the magnitude sits at ≈1.00 g
    // (gravity). Each latched interrupt is reported with a timestamp,
    // the latest (x, y, z), temperature, and a free-fall or shock tag.
    while (absolute_time_diff_us(start, get_absolute_time()) < 60000000) {
        int64_t now = absolute_time_diff_us(start, get_absolute_time()) / 1000;

        if (now - last_heartbeat >= 1000) {
            float x, y, z;
            accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
            float mag = sqrtf(x * x + y * y + z * z);
            float temp = accel.read_temperature();               // Read temperature, () → °C
            printf("%5lld  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\n",
                   (long long)(now / 1000), (double)x, (double)y, (double)z, (double)mag, (double)temp);
            last_heartbeat = now;
        }

        if (now - last_poll >= 50) {
            uint8_t status = accel.poll_interrupt();             // Read STATUS, () → bitmask
            if (status & 0x08) {                                // STATUS_LG_LATCHED (bit 3)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lld  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now / 1000), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                                // STATUS_HG_LATCHED (bit 2)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lld  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now / 1000), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            last_poll = now;
        }

        sleep_ms(10);
    }
    printf("done\n");
    return 0;
}
