#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Mpl3115a2.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MPL3115A2_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MPL3115A2_ADDR);
    MPL3115A2Full sensor(connection);                              // Create MPL3115A2 driver, (connection) → void
    float p = 0, t = 0, alt = 0;
    sensor.init();                                                 // Reset and apply defaults, () → bool
    // --- Weather and altitude station ---
    // Sets the local sea-level pressure, takes three barometer readings, switches to
    // altimeter mode for three altitude readings, collects 16 samples in the circular
    // FIFO and prints their minimum, maximum and mean, then arms a temperature
    // threshold on INT1 and reports the captured extremes.
    sensor.set_sea_level_pressure(101325.0f);                      // Sea-level reference, (pa) → bool
    for (int i = 0; i < 3; i++) {
        sensor.read(p, t);                                         // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        printk("%.2f Pa %.2f C\n", (double)(p), (double)(t));
        k_sleep(K_MSEC(5000));
    }
    for (int i = 0; i < 3; i++) {
        sensor.read_altitude(alt);                                 // Read altitude, (altitude_m&) → bool
        printk("altitude %.2f m\n", (double)(alt));
    }
    sensor.set_mode(false);                                        // Back to barometer, (altimeter) → bool
    sensor.set_oversample(16);                                     // Faster conversions, (ratio) → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 16);           // Circular FIFO, watermark 16, (mode, watermark) → bool
    sensor.start_continuous(0);                                    // One sample per second, (step_exp 0-15) → bool
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    while (!watermark) {
        k_sleep(K_MSEC(1000));
        sensor.fifo_status(overflow, watermark, count);
    }
    float fv[32], ft[32];
    uint8_t n = sensor.fifo_read(fv, ft, 32);                      // Drain the FIFO, (values*, temperatures*, max) → uint8_t
    float lo = fv[0], hi = fv[0], sum = 0;
    for (uint8_t i = 0; i < n; i++) { if (fv[i] < lo) lo = fv[i]; if (fv[i] > hi) hi = fv[i]; sum += fv[i]; }
    printk("n=%lu min %.2f max %.2f mean %.2f Pa\n", (unsigned long)(n), (double)(lo), (double)(hi), (double)(sum / (n ? n : 1)));
    sensor.set_target(0.0f, 0.0f, (int8_t)(ft[n - 1] + 1), 0);     // Temperature target, (value, window, temperature_c, temperature_window) → bool
    sensor.enable_interrupt(MPL3115A2Full::SOURCE_TTH, 1);         // Route temperature threshold to INT1, (source, pin) → bool
    printk("INT_SOURCE 0x%02X\n", (unsigned)(sensor.poll_interrupt()));
    sensor.stop_continuous();                                      // Back to STANDBY, () → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_OFF);                    // FIFO off, (mode) → bool
    float pmin = 0, pmax = 0, tmin = 0, tmax = 0;
    sensor.read_min_max(pmin, pmax, tmin, tmax);                   // Captured extremes, (p_min&, p_max&, t_min&, t_max&) → bool
    printk("min %.2f Pa max %.2f Pa\n", (double)(pmin), (double)(pmax));
    return 0;
}
