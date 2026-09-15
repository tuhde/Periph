#include <stdio.h>
#include <math.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "I2CConnectionPicoSDK.h"
#include "L3G4200D.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x68);

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    L3G4200DFull chip(connection, /*spi=*/false);         // Create L3G4200D driver, (connection, spi=false)
    chip.configure(1, 0, 500);                             // Configure chip, (odr=200Hz, bandwidth=0, full_scale=500) → None
    chip.enable_highpass(0, 4);                            // Enable high-pass, (mode=0, cutoff=4) → None
                                                            // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    chip.enable_fifo(L3G4200DFull::FIFO_STREAM, 10);        // Enable FIFO, (mode=2=stream, watermark=10) → None

    stdio_init_all();
    sleep_ms(2000);

    float threshold_rad_s = 90.0f * (3.141592653589793f / 180.0f);
    int alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for (int n = 0; n < 50; n++) {
        while (chip.fifo_samples() < 10) {                 // Read FIFO count, () → int
            sleep_ms(5);
        }
        float x, y, z;
        chip.angular_rate(x, y, z);                        // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        if (fabsf(x) > threshold_rad_s || fabsf(y) > threshold_rad_s || fabsf(z) > threshold_rad_s) {
            alerts++;
            printf("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        } else {
            printf("       X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        }
        sleep_ms(20);
    }

    printf("Total alerts: %d / 50\n", alerts);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}
