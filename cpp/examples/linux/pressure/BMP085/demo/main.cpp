#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <cstdio>
#include <chrono>
#include <thread>
#include "I2CConnectionLinux.h"
#include "BMP085.h"

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    BMP085Full bmp(connection, BMP085Full::OSS_ULP);        // Create BMP085 driver, (connection, oss=0 ULP)

    float t0 = bmp.temperature();                           // Read temperature, () → float C
    float p0 = bmp.pressure();                             // Read pressure, () → float Pa
    float alt_ref = bmp.altitude();                        // Compute altitude, (sea_level_pa=101325.0) → float m
    printf("Reference: %.1f C, %.1f Pa, alt=%.1f m\n", t0, p0, alt_ref);

    float prev_alt = 0.0f;
    for (int n = 0; n < 60; n++) {
        float t = bmp.temperature();                        // Read temperature, () → float C
        float p = bmp.pressure();                          // Read pressure, () → float Pa
        float a = bmp.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
        float da = (a - prev_alt) * 100.0f;

        if (n > 0) {
            printf("%ds: %.1f C, %.1f Pa, alt=%.1f m (delta=%+.0f cm)\n", n, t, p, a, da);
        } else {
            printf("%ds: %.1f C, %.1f Pa, alt=%.1f m\n", n, t, p, a);
        }
        prev_alt = a;
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
