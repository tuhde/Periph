#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BME280.h"

#ifndef BME280_I2C_NODE
#define BME280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME280_ADDR
#define BME280_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME280_I2C_NODE);
    I2CTransportZephyr transport(dev, BME280_ADDR);

    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    BME280Full bme(transport);                          // Create BME280 driver, (transport, spi=false)
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_FORCED, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → void

    int n_samples = 10;
    for (int n = 0; n < n_samples; n++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float a = bme.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        printk("%d: %d C, %d %%RH, %d hPa, dew=%d C, alt=%d m\n", n, (int)t, (int)h, (int)p, (int)d, (int)a);
        k_msleep(1000);
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    printk("--- Breathe gently on the sensor for 3 seconds ---\n");
    k_msleep(3000);
    {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        printk("after breath: %d C, %d %%RH, %d hPa, dew=%d C\n", (int)t, (int)h, (int)p, (int)d);
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
