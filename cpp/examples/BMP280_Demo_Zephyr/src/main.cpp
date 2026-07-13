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

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP280_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP280_ADDR);

    // --- Weather monitoring preset: lowest power, forced mode ---
    BMP280Full bmp(transport);                           // Create BMP280 driver, (transport, spi=false)
    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1, BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        printk("%ds: %.1f C, %.1f hPa, alt=%.1f m\n", n, t, p, a);
        k_sleep(K_SECONDS(1));
    }

    // --- Indoor navigation preset: high resolution with IIR filter ---
    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X16, BMP280Full::MODE_NORMAL, BMP280Full::FILTER_16, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        printk("%ds: alt=%.4f m\n", n, a);
        k_sleep(K_SECONDS(1));
    }

    return 0;
}
