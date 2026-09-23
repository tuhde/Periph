#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BMP280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BMP280Full bmp(connection);                                             // Create BMP280 driver, (connection)

    uint8_t id = bmp.chip_id();                                            // Read chip ID, () → uint8_t
                                                                           // 0x58 for BMP280 (0x60 would be a BME280)
    printf("chip_id=0x%02X\n", id);
    float t = bmp.temperature();                                           // Read temperature, () → float °C
    float p = bmp.pressure();                                              // Read pressure, () → float hPa
    printf("t=%.2f C p=%.2f hPa\n", (double)t, (double)p);

    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X16, BMP280Full::MODE_NORMAL,
                  BMP280Full::FILTER_16, BMP280Full::T_SB_0_5_MS);         // Configure in one write, (osrs_t, osrs_p, mode, filter, t_sb) → void
                                                                           // indoor-navigation profile from the datasheet
    bmp.set_oversampling(BMP280Full::OSRS_X1, BMP280Full::OSRS_X4);        // Set oversampling, (osrs_t, osrs_p) → void
    bmp.set_filter(BMP280Full::FILTER_4);                                  // Set IIR filter, (coeff) → void
    bmp.set_standby(BMP280Full::T_SB_62_5_MS);                             // Set normal-mode standby, (t_sb) → void
    bmp.set_mode(BMP280Full::MODE_NORMAL);                                 // Set power mode, (mode) → void
    usleep(200000);
    printf("status=0x%02X\n", bmp.status());                              // Read STATUS, () → uint8_t (MEASURING / IM_UPDATE)

    float alt = bmp.altitude(1013.25f);                                    // Altitude from pressure, (sea_level_hpa=1013.25 hPa) → float m
    float slp = bmp.sea_level_pressure(100.0f);                            // Reduce to sea level, (altitude_m m) → float hPa
    printf("p=%.2f hPa alt=%.1f m slp=%.2f hPa\n", (double)bmp.pressure(), (double)alt, (double)slp);

    bmp.reset();                                                           // Soft reset, () → void
    return 0;
}
