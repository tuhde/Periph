#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "L3G4200D.h"

#ifndef L3G4200D_I2C_NODE
#define L3G4200D_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3G4200D_ADDR
#define L3G4200D_ADDR 0x68
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(L3G4200D_I2C_NODE);
    I2CConnectionZephyr connection(dev, L3G4200D_ADDR);

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    L3G4200DFull gyro(connection);                           // Create L3G4200D driver, (connection, spi=false)
    gyro.configure(1, 0, 500);                               // Configure chip, (odr=200Hz, bandwidth=0, full_scale=500) → None
    gyro.enable_highpass(0, 4);                              // Enable high-pass, (mode=0, cutoff=4) → None
                                                                // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    gyro.enable_fifo(L3G4200DFull::FIFO_STREAM, 10);          // Enable FIFO, (mode=2=stream, watermark=10) → None

    float threshold_rad_s = 90.0f * (3.141592653589793f / 180.0f);
    int alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for (int n = 0; n < 50; n++) {
        while (gyro.fifo_samples() < 10) {                   // Read FIFO count, () → int
            k_sleep(K_MSEC(5));
        }
        float x, y, z;
        gyro.angular_rate(x, y, z);                          // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        if (fabsf(x) > threshold_rad_s || fabsf(y) > threshold_rad_s || fabsf(z) > threshold_rad_s) {
            alerts++;
            printk("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        } else {
            printk("       X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        }
        k_sleep(K_MSEC(20));
    }

    printk("Total alerts: %d / 50\n", alerts);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
