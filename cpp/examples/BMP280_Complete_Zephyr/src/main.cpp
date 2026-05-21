#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP280.h"

#ifndef BMP280_I2C_NODE
#define BMP280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP280_ADDR
#define BMP280_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP280_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP280_ADDR);
    BMP280Full bmp(transport);                        // Create BMP280 driver, (transport, addr=0x76)

    uint8_t cid = bmp.chip_id();                      // Read chip ID, () → int
    check_true(cid == 0x58, "chip_id");

    uint8_t s = bmp.status();                        // Read status register, () → int
    (void)s;

    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X4,
                   BMP280Full::MODE_FORCED, BMP280Full::FILTER_4,
                   BMP280Full::T_SB_62_5_MS);         // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None

    bmp.set_oversampling(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1);  // Update oversampling, (osrs_t, osrs_p) → None
    bmp.set_filter(BMP280Full::FILTER_OFF);           // Update IIR filter, (coeff 0–4) → None
    bmp.set_standby(BMP280Full::T_SB_250_MS);        // Update standby time, (t_sb 0–7) → None
    bmp.reset();                                      // Soft reset and re-init, () → None

    float t = bmp.temperature();                      // Read temperature, () → float C
    float p = bmp.pressure();                         // Read pressure, () → float hPa
    float alt = bmp.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m

    printk("T=%.1f C, P=%.1f hPa, alt=%.1f m\n", t, p, alt);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}