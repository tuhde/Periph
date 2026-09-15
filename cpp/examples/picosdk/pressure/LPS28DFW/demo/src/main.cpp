#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS28DFW.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);

    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    LPS28DFWFull lps(connection);                          // Create LPS28DFW driver, (connection)
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpf_en=True, lpf_cfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    stdio_init_all();
    sleep_ms(2000);
    int samples = 0;
    for (int n = 0; n < 60; n++) {
        float p = 0.0f, t = 0.0f;
        lps.read(p, t);                                    // Read both values, (pressure, temperature) → void
        float alt = 44330.0f * (1.0f - powf(p / 1013.25f, 1.0f / 5.255f));
        float elapsed = (n + 1) * 0.5f;
        printf("%.1fs  %.2f hPa  %.2f C  %.1f m\n",
               (double)elapsed, (double)p, (double)t, (double)alt);
        samples++;
        sleep_ms(500);
    }
    printf("Total samples: %d\n", samples);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) { sleep_ms(1000); }
    return 0;
}