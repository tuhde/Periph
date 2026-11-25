#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "SHT31.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define SHT31_ADDR 0x44

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SHT31_ADDR);
    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.soft_reset();                                                // Soft reset, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printk("status 0x%04X\n", (unsigned)status);
    // --- Single shots at all three repeatability levels ---
    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);                                // Select repeatability, (level) → bool
        uint32_t t0 = k_uptime_get_32();
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        uint32_t dt = k_uptime_get_32() - t0;
        printk("level %u: %.2f C %.1f %%RH (%lu ms)\n", (unsigned)level, (double)(t), (double)(rh), (unsigned long)dt);
    }
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
    printk("raw %u %u, T %.2f, RH %.1f\n", (unsigned)raw_t, (unsigned)raw_rh, (double)(sensor.read_temperature()), (double)(sensor.read_humidity()));

    // --- Periodic mode at 2 mps: ten samples ---
    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
    for (uint8_t got = 0; got < 10;) {
        if (!sensor.fetch(t, rh)) {                                     // Fetch the latest periodic result, (t, rh) → bool
            k_sleep(K_MSEC(50));
            continue;
        }
        got++;
        printk("periodic %u: %.2f C %.1f %%RH\n", (unsigned)got, (double)(t), (double)(rh));
    }
    sensor.stop_periodic();                                             // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    float before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    k_sleep(K_MSEC(10000));
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printk("heater status bit: %u\n", (unsigned)((status >> 13) & 1));
    printk("temperature rise: %.2f K\n", (double)(sensor.read_temperature() - before));
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printk("status 0x%04X, dew point %.2f C\n", (unsigned)status, (double)(sensor.dew_point()));
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
