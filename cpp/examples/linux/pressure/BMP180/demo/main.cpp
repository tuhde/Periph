#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP180.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    BMP180Full bmp(connection, BMP180Full::OSS_ULTRA_HIGH_RES);             // Create BMP180 driver, (connection, oss=3)

    // --- Weather station with trend detection ---
    // Ultra-high-resolution oversampling (0.03 hPa RMS noise); logs pressure
    // every 60 s and labels a change of more than 0.5 hPa as a trend.
    float prev = bmp.pressure();                                           // Read pressure, () → float hPa
    printf("%.2f hPa  (baseline)\n", (double)prev);
    while (true) {
        usleep(60000000);
        float curr = bmp.pressure();                                       // Read pressure, () → float hPa
        float delta = curr - prev;
        const char* trend = delta > 0.5f ? "RISING" : delta < -0.5f ? "FALLING" : "STEADY";
        printf("%.2f hPa  %s  (delta %.2f hPa)\n", (double)curr, trend, (double)delta);
        prev = curr;
    }
    return 0;
}
