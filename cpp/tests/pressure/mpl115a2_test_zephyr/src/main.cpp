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
    MPL115A2Full baro(connection);

    float p = baro.read_pressure();
    check_true(p >= 50000.0f && p <= 115000.0f, "pressure_range");
    float t = baro.read_temperature();
    check_true(t >= -40.0f && t <= 105.0f, "temperature_range");
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);
    check_true(padc <= 1023 && tadc <= 1023, "raw_range");
    float c = baro.compensate(padc, tadc);
    check_true(c >= 50000.0f && c <= 115000.0f, "compensate_range");
    float pa, tc;
    baro.read(pa, tc);
    check_true(pa >= 50000.0f && pa <= 115000.0f && tc >= -40.0f && tc <= 105.0f, "read_both");
    float hpa = baro.pressure_hpa();
    check_true(hpa >= 500.0f && hpa <= 1150.0f, "pressure_hpa");
    float alt = baro.altitude();
    check_true(alt >= -1000.0f && alt <= 6000.0f, "altitude");
    baro.reload_coefficients();
    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);
    check_true(a0 != 0.0f, "coefficients");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
