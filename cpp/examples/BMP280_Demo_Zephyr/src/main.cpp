#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <cmath>
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
    BMP280Full bmp(transport);                        // Create BMP280 driver, (transport, addr=0x76)

    // --- Weather monitoring preset: forced mode, ×1/×1, filter off ---
    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1,
                   BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF,
                   BMP280Full::T_SB_0_5_MS);         // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None

    printk("WEATHER-MONITORING  T[C]   P[hPa]   ALT[m]\n");

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                  // Read temperature, () → float C
        float p = bmp.pressure();                     // Read pressure, () → float hPa
        float a = bmp.altitude();                     // Compute altitude, (sea_level_hpa=1013.25) → float m
        printk("%ds: %.2f C   %.2f hPa   %.2f m\n", n, t, p, a);
        k_sleep(K_SECONDS(1));
    }

    // --- Switch to indoor-navigation preset: normal mode, ×16/×2, filter 16 ---
    bmp.configure(BMP280Full::OSRS_X16, BMP280Full::OSRS_X2,
                   BMP280Full::MODE_NORMAL, BMP280Full::FILTER_16,
                   BMP280Full::T_SB_0_5_MS);          // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None

    printk("\nINDOOR-NAVIGATION (normal mode, filter=16)\n");
    printk("      n   ALT[m]    delta[cm]\n");

    float prev_alt = 0.0f;
    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                  // Read temperature, () → float C
        float p = bmp.pressure();                     // Read pressure, () → float hPa
        float a = bmp.altitude();                     // Compute altitude, (sea_level_hpa=1013.25) → float m
        float da = (a - prev_alt) * 100.0f;
        if (n > 0) {
            printk("%4d:  %.4f   %+.1f cm\n", n, a, da);
        } else {
            printk("%4d:  %.4f\n", n, a);
        }
        prev_alt = a;
        k_sleep(K_SECONDS(1));
    }

    return 0;
}