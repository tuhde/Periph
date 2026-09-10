#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP581.h"

#ifndef BMP581_I2C_NODE
#define BMP581_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP581_ADDR
#define BMP581_ADDR 0x46
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP581_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP581_ADDR);

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    BMP581Full bmp(connection);                           // Create BMP581 driver, (connection, spi=false)
    bmp.configure(0x17, BMP581Full::OSR_16X, BMP581Full::OSR_4X, true);  // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → None

    float pressures[300], temps[300], alts[300];
    for (int n = 0; n < 300; n++) {
        pressures[n] = bmp.pressure();                    // Read pressure, () → float Pa
        temps[n] = bmp.temperature();                     // Read temperature, () → float °C
        alts[n] = bmp.altitude();                         // Compute altitude, (sea_level_pa=101325.0) → float m
        if (n % 10 == 0) {
            int start = (n >= 10) ? n - 10 : 0;
            int span = (n >= 10) ? 10 : n;
            float mp = 0, mt = 0, ma = 0;
            for (int k = start; k < n; k++) {
                mp += pressures[k];
                mt += temps[k];
                ma += alts[k];
            }
            if (span > 0) { mp /= span; mt /= span; ma /= span; }
            printk("%ds: rolling P=%.1f Pa, T=%.2f C, alt=%.2f m\n", n / 10, mp, mt, ma);
        }
        k_sleep(K_MSEC(100));
    }

    float amin = alts[0], amax = alts[0];
    for (int n = 1; n < 300; n++) {
        if (alts[n] < amin) amin = alts[n];
        if (alts[n] > amax) amax = alts[n];
    }
    printk("Bypass: alt min=%.3f max=%.3f spread=%.3f m\n", amin, amax, amax - amin);

    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → None

    float alts2[300];
    for (int n = 0; n < 300; n++) {
        bmp.pressure();                                   // Read pressure, () → float Pa
        alts2[n] = bmp.altitude();                        // Compute altitude, (sea_level_pa=101325.0) → float m
        k_sleep(K_MSEC(100));
    }
    float amin2 = alts2[0], amax2 = alts2[0];
    for (int n = 1; n < 300; n++) {
        if (alts2[n] < amin2) amin2 = alts2[n];
        if (alts2[n] > amax2) amax2 = alts2[n];
    }
    printk("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m\n", amin2, amax2, amax2 - amin2);

    float pmin = pressures[0], pmax = pressures[0], psum = 0;
    for (int n = 0; n < 300; n++) {
        if (pressures[n] < pmin) pmin = pressures[n];
        if (pressures[n] > pmax) pmax = pressures[n];
        psum += pressures[n];
    }
    printk("Min P=%.1f, max P=%.1f, mean P=%.1f Pa\n", pmin, pmax, psum / 300.0f);

    return 0;
}