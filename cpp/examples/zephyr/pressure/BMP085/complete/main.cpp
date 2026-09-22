#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP085.h"

#ifndef BMP085_I2C_NODE
#define BMP085_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP085_ADDR
#define BMP085_ADDR 0x77
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP085_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP085_ADDR);
    BMP085Full bmp(connection);                         // Create BMP085 driver, (connection, oss=0)
    uint8_t cid = bmp.chip_id();                     // Read chip ID, () → int
    check_true(cid == 0x55, "chip_id");

    uint8_t oss = bmp.oversampling();                // Read OSS, () → int 0–3
    check_true(oss == 0, "default_oss");

    bmp.set_oversampling(BMP085Full::OSS_STANDARD);    // Set OSS, (oss 0–3) → None
    check_true(bmp.oversampling() == 1, "set_oss");

    float t = bmp.temperature();                      // Read temperature, () → float C
    float p = bmp.pressure();                        // Read pressure, () → float Pa
    float alt = bmp.altitude();                     // Compute altitude, (sea_level_pa=101325.0) → float m
    float slp = bmp.sea_level_pressure(alt);         // Compute sea-level pressure, (altitude_m) → float Pa
    bmp.reset();                                     // Soft reset chip, () → None

    printk("T=%.1f C, P=%.1f Pa, alt=%.1f m, slp=%.1f Pa\n", t, p, alt, slp);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}