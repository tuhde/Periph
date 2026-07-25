#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP180.h"

#ifndef BMP180_I2C_NODE
#define BMP180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP180_ADDR
#define BMP180_ADDR 0x77
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP180_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP180_ADDR);
    BMP180Full bmp(transport);                         // Create BMP180 driver, (transport, oss=0)
    uint8_t cid = bmp.chip_id();                     // Read chip ID, () → int
    check_true(cid == 0x55, "chip_id");

    uint8_t oss = bmp.oversampling();                // Read OSS, () → int 0–3
    check_true(oss == 0, "default_oss");

    bmp.set_oversampling(BMP180Full.OSS_STANDARD);    // Set OSS, (oss 0–3) → None
    check_true(bmp.oversampling() == 1, "set_oss");

    float t = bmp.temperature();                      // Read temperature, () → float C
    float p = bmp.pressure();                        // Read pressure, () → float hPa
    float alt = bmp.altitude();                     // Compute altitude, (sea_level_hpa=1013.25) → float m
    float slp = bmp.sea_level_pressure(alt);         // Compute sea-level pressure, (altitude_m) → float hPa
    bmp.reset();                                     // Soft reset chip, () → None

    printk("T=%.1f C, P=%.1f hPa, alt=%.1f m, slp=%.1f hPa\n", t, p, alt, slp);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
