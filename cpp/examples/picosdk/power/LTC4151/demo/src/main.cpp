#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LTC4151.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x6A);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for (int n = 0; n < 20; n++) {
        LTC4151Full::Readings r;
        ltc.read_all(r);                                                // Consistent read of all channels, (out Readings) → void
        printf("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V\n", r.vin, r.sense_v * 1000.0f, r.current, r.power, r.adin);
        sleep_ms(1000);
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    static const char* const names[3] = { "SENSE", "VIN", "ADIN" };
    for (uint8_t ch = 0; ch < 3; ch++) {
        float f = 0;
        uint32_t t0 = (uint32_t)to_ms_since_boot(get_absolute_time());
        bool ok = ltc.snapshot(ch, f);                                  // One snapshot conversion, (channel 0–2, out V) → bool
        uint32_t dt = (uint32_t)to_ms_since_boot(get_absolute_time()) - t0;
        printf("%s snapshot %u, %.4f V in %u ms\n", names[ch], (unsigned)ok, f, (unsigned)dt);
    }
    printf("snapshot mode afterwards %u\n", (unsigned)ltc.is_snapshot_mode());  // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.set_stuck_bus_timer(false);                                     // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(false);                                             // I²C page read/write, (enabled) → void
    printf("CONTROL (timer off, page off) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t
    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
    printf("CONTROL (restored) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    printf("temperature %.1f C\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    float pmin = 1e9f, pmax = 0, psum = 0;
    for (int n = 0; n < 25; n++) {
        float p = 0;
        p = ltc.power();
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        sleep_ms(200);
    }
    printf("power min %.3f W, max %.3f W, avg %.3f W\n", pmin, pmax, psum / 25.0f);

    return 0;
}
