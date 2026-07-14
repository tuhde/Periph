#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BME680.h"

#ifndef BME680_I2C_NODE
#define BME680_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME680_ADDR
#define BME680_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME680_I2C_NODE);
    I2CTransportZephyr transport(dev, BME680_ADDR);

    // --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
    // Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
    // At tick 30, the user is prompted to expose the sensor to a VOC source.
    BME680Full bme(transport);                           // Create BME680 driver, (transport)
    bme.configure(BME680Full::OSRS_X2, BME680Full::OSRS_X16, BME680Full::OSRS_X1, BME680Full::MODE_FORCED, BME680Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float g_min = 1e12, g_max = 0;
    int gas_count = 0;

    for (int n = 0; n < 60; n++) {
        if (n == 30) {
            printk("--- Expose sensor to VOC source now (alcohol/marker) ---\n");
        }
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        if (t < t_min) t_min = t;
        if (t > t_max) t_max = t;
        t_sum += t;
        if (!isnan(g)) {
            if (g < g_min) g_min = g;
            if (g > g_max) g_max = g;
            gas_count++;
        }
        printk("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm\n", n, (double)t, (double)h, (double)p, (double)g);
        k_sleep(K_SECONDS(5));
    }

    float t_avg = t_sum / 60.0f;
    printk("T: %.1f/%.1f/%.1f C\n", (double)t_min, (double)t_avg, (double)t_max);
    if (gas_count > 0 && g_min > 0) {
        printk("VOC response ratio: %.1fx\n", (double)(g_max / g_min));
    }

    return 0;
}
