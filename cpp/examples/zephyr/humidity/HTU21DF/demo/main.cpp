#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "HTU21DF.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define HTU21DF_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU21DF_ADDR);
    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
    sensor.soft_reset();                                                // Soft reset, () → void
    reg = sensor.read_user_register();                                  // Read the user register, () → uint8_t
    printk("user register 0x%02X\n", (unsigned)reg);
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printk("resolution setting %u\n", (unsigned)res);

    // --- Five readings at 12/14 bit ---
    for (uint8_t i = 0; i < 5; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printk("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C\n", (double)(t), (double)(rh), (double)(sensor.read_humidity_compensated()), (double)(sensor.dew_point()));
        // sensor.read_humidity_compensated() → float %RH ; sensor.dew_point() → float °C
        k_sleep(K_MSEC(2000));
    }
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
    printk("raw T %u raw RH %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.set_resolution(1);                                           // Set resolution, (res) → bool
    uint32_t t0 = k_uptime_get_32();
    sensor.read(t, rh);
    uint32_t dt = k_uptime_get_32() - t0;
    printk("8/12 bit: %.2f C %.1f %%RH in %lu ms\n", (double)(t), (double)(rh), (unsigned long)dt);
    sensor.set_resolution(0);                                           // Back to 12/14 bit
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    k_sleep(K_MSEC(10000));
    printk("heater on %u\n", (unsigned)(sensor.heater()));
    printk("RH drop %.1f %%RH, temperature rise %.2f K\n", (double)(rh_before - sensor.read_humidity()), (double)(sensor.read_temperature() - t_before));
    sensor.set_heater(false);                                           // Heater off
    printk("battery low %u\n", (unsigned)(sensor.battery_low()));
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
