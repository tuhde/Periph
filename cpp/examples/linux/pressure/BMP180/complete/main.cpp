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

    BMP180Full bmp(connection, BMP180Full::OSS_ULP);                        // Create BMP180 driver, (connection, oss=0)

    uint8_t id = bmp.chip_id();                                            // Read chip ID, () → uint8_t
                                                                           // 0x55 for BMP180
    printf("chip_id=0x%02X\n", id);
    float t = bmp.temperature();                                           // Read temperature, () → float °C
    float p = bmp.pressure();                                              // Read pressure at the current OSS, () → float hPa
    printf("t=%.2f C p=%.2f hPa oss=%u\n", (double)t, (double)p, bmp.oversampling());  // Current OSS, () → uint8_t 0–3

    bmp.set_oversampling(BMP180Full::OSS_ULTRA_HIGH_RES);                  // Set oversampling, (oss 0–3) → void
                                                                           // 8 samples per reading, ~25.5 ms conversion
    float p3 = bmp.pressure();
    float alt = bmp.altitude(1013.25f);                                    // Altitude from pressure, (sea_level_hpa=1013.25 hPa) → float m
    float slp = bmp.sea_level_pressure(100.0f);                            // Reduce to sea level, (altitude_m m) → float hPa
    printf("p(oss3)=%.2f hPa alt=%.1f m slp=%.2f hPa\n", (double)p3, (double)alt, (double)slp);

    bmp.reset();                                                           // Soft reset, () → void
    return 0;
}
