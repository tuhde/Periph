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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MPL115A2_I2C_NODE);
    I2CConnectionZephyr connection(dev, MPL115A2_ADDR);
    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    baro.reload_coefficients();                           // Re-read coefficient block, () → void
                                                              // use after a power cycle
    float pa, tc;
    baro.read(pa, tc);                                    // One conversion, (pressure_pa&, temperature_c&) → void
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);                            // One conversion, (padc&, tadc&) → void, 10-bit counts
    float p = baro.compensate(padc, tadc);                // Compensate raw counts, (padc, tadc) → float Pa
                                                              // pure function, no bus access
    float hpa = baro.pressure_hpa();                      // Read pressure, () → float hPa
    float alt = baro.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
                                                              // barometric formula, informative
    printk("a0=%.3f b1=%.5f b2=%.5f c12=%.8f\n", a0, b1, b2, c12);
    printk("%.0f Pa, %.1f C, raw=(%d, %d), %.0f Pa, %.2f hPa, alt=%.1f m\n", pa, tc, (int)padc, (int)tadc, p, hpa, alt);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
