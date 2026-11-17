#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "LTC4151.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define LTC4151_ADDR 0x6A

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, LTC4151_ADDR);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for (int n = 0; n < 20; n++) {
        LTC4151Full::Readings r;
        ltc.read_all(r);                                                // Consistent read of all channels, (out Readings) → void
        printk("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V\n", (double)(r.vin), (double)(r.sense_v * 1000.0f), (double)(r.current), (double)(r.power), (double)(r.adin));
        k_sleep(K_MSEC(1000));
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    static const char* const names[3] = { "SENSE", "VIN", "ADIN" };
    for (uint8_t ch = 0; ch < 3; ch++) {
        float f = 0;
        uint32_t t0 = (uint32_t)k_uptime_get();
        bool ok = ltc.snapshot(ch, f);                                  // One snapshot conversion, (channel 0–2, out V) → bool
        uint32_t dt = (uint32_t)k_uptime_get() - t0;
        printk("%s snapshot %u, %.4f V in %u ms\n", names[ch], (unsigned)ok, (double)(f), (unsigned)dt);
    }
    printk("snapshot mode afterwards %u\n", (unsigned)ltc.is_snapshot_mode());  // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.set_stuck_bus_timer(false);                                     // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(false);                                             // I²C page read/write, (enabled) → void
    printk("CONTROL (timer off, page off) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t
    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
    printk("CONTROL (restored) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    printk("temperature %.1f C\n", (double)(LTC4151Full::temperature_from_adin(900, 1200)));  // NTC example circuit, (adin_code, vin_code) → float °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    float pmin = 1e9f, pmax = 0, psum = 0;
    for (int n = 0; n < 25; n++) {
        float p = 0;
        p = ltc.power();
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        k_sleep(K_MSEC(200));
    }
    printk("power min %.3f W, max %.3f W, avg %.3f W\n", (double)(pmin), (double)(pmax), (double)(psum / 25.0f));

    return 0;
}
