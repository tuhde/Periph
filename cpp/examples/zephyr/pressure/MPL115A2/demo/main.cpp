#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MPL115A2.h"

#ifndef MPL115A2_I2C_NODE
#define MPL115A2_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPL115A2_ADDR
#define MPL115A2_ADDR 0x60
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MPL115A2_I2C_NODE);
    I2CConnectionZephyr connection(dev, MPL115A2_ADDR);
    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    printk("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    float pmin = 1e9f, pmax = 0.0f;
    for (int n = 0; n < 15; n++) {
        float pa = baro.read_pressure();                  // Read pressure, () → float Pa
        float hpa = baro.pressure_hpa();                  // Read pressure, () → float hPa
        float alt = baro.altitude();                      // Compute altitude, (sea_level_pa=101325.0) → float m
        uint16_t padc, tadc;
        baro.read_raw(padc, tadc);                        // One conversion, (padc&, tadc&) → void
        if (pa < pmin) pmin = pa;
        if (pa > pmax) pmax = pa;
        printk("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)\n", n * 2, pa, hpa, alt, (int)padc, (int)tadc);
        k_msleep(2000);
    }
    printk("Summary: min=%.0f Pa, max=%.0f Pa\n", pmin, pmax);

    return 0;
}
