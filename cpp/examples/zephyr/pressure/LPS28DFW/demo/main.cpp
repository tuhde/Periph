#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LPS28DFW.h"

#ifndef LPS28DFW_I2C_NODE
#define LPS28DFW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS28DFW_ADDR
#define LPS28DFW_ADDR 0x5C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS28DFW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS28DFW_ADDR);

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    LPS28DFWFull lps(connection);                               // Create LPS28DFW driver, (connection)
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=True, lpf_cfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    int samples = 0;
    for (int n = 0; n < 60; n++) {
        float p = 0.0f, t = 0.0f;
        lps.read(p, t);                                         // Read both values, (pressure, temperature) → void
        float alt = 44330.0f * (1.0f - powf(p / 1013.25f, 1.0f / 5.255f));
        float elapsed = (n + 1) * 0.5f;
        printk("%.1fs  %.2f hPa  %.2f C  %.1f m\n",
               (double)elapsed, (double)p, (double)t, (double)alt);
        samples++;
        k_sleep(K_MSEC(500));
    }
    printk("Total samples: %d\n", samples);
    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}