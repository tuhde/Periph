#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP581.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x46;
    I2CConnectionLinux connection(bus, addr);

    // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
    // 10 Hz ODR (odr field 0x17) gives sub-decimetre altitude resolution over
    // a 30-second window while still leaving headroom for higher OSR.
    BMP581Full bmp(connection);                                            // Create BMP581 driver, (connection, spi=false)
    bmp.configure(0x17, BMP581Full::OSR_16X, BMP581Full::OSR_4X, true);     // Configure chip, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → void

    static float pressures[300], temps[300], alts[300];
    for (int n = 0; n < 300; n++) {
        float p = bmp.pressure();                                          // Read pressure, () → float Pa
        float t = bmp.temperature();                                       // Read temperature, () → float °C
        float a = bmp.altitude();                                          // Compute altitude, (sea_level_pa=101325.0) → float m
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
            printf("%ds: rolling P=%.1f Pa, T=%.2f C, alt=%.2f m\n", n / 10 * 10, mp, mt, ma);
        }
        pressures[n] = p;
        temps[n] = t;
        alts[n] = a;
        usleep(100000);
    }

    // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
    // Coefficient 3 = 7-tap filter; expect noticeably tighter altitude variance.
    float amin = alts[0], amax = alts[0];
    for (int n = 1; n < 300; n++) {
        if (alts[n] < amin) amin = alts[n];
        if (alts[n] > amax) amax = alts[n];
    }
    printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m\n", amin, amax, amax - amin);

    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);    // Set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → void

    static float alts2[300];
    for (int n = 0; n < 300; n++) {
        bmp.pressure();                                                    // Read pressure, () → float Pa
        alts2[n] = bmp.altitude();                                         // Compute altitude, (sea_level_pa=101325.0) → float m
        usleep(100000);
    }
    float amin2 = alts2[0], amax2 = alts2[0];
    for (int n = 1; n < 300; n++) {
        if (alts2[n] < amin2) amin2 = alts2[n];
        if (alts2[n] > amax2) amax2 = alts2[n];
    }
    printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m\n", amin2, amax2, amax2 - amin2);

    float pmin = pressures[0], pmax = pressures[0], psum = 0;
    for (int n = 0; n < 300; n++) {
        if (pressures[n] < pmin) pmin = pressures[n];
        if (pressures[n] > pmax) pmax = pressures[n];
        psum += pressures[n];
    }
    printf("Min P=%.1f, max P=%.1f, mean P=%.1f Pa\n", pmin, pmax, psum / 300.0f);
    return 0;
}
