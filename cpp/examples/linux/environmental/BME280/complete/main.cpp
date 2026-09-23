#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME280Full bme(connection);                                             // Create BME280 driver, (connection)

    uint8_t id = bme.chip_id();                                            // Read chip ID register, () → uint8_t
                                                                           // BME280 returns 0x60, BMP280 returns 0x58
    printf("chip_id=0x%02X\n", id);

    bme.configure(BME280Full::OSRS_X2, BME280Full::OSRS_X16, BME280Full::OSRS_X1,
                  BME280Full::MODE_NORMAL, BME280Full::FILTER_16, BME280Full::T_SB_0_5_MS);  // Configure in one write, (osrs_t, osrs_p, osrs_h, mode, filter, t_sb) → void
                                                                           // indoor-navigation profile from the datasheet
    bme.set_oversampling(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1);  // Set oversampling, (osrs_t, osrs_p, osrs_h) → void
    bme.set_filter(BME280Full::FILTER_4);                                  // Set IIR filter coefficient, (coeff) → void
                                                                           // smooths pressure against short gusts
    bme.set_standby(BME280Full::T_SB_125_MS);                              // Set normal-mode standby, (t_sb) → void
    bme.set_mode(BME280Full::MODE_NORMAL);                                 // Set power mode, (mode) → void
                                                                           // normal mode measures continuously
    usleep(200000);

    uint8_t st = bme.status();                                             // Read STATUS register, () → uint8_t
                                                                           // STATUS_MEASURING / STATUS_IM_UPDATE bits
    float t = bme.temperature();                                           // Read temperature, () → float °C
    float h = bme.humidity();                                              // Read relative humidity, () → float %RH
    float p = bme.pressure();                                              // Read pressure, () → float hPa
    float alt = bme.altitude(1013.25f);                                    // Estimate altitude, (sea_level_hpa=1013.25 hPa) → float m
                                                                           // barometric formula against the given sea-level pressure
    float slp = bme.sea_level_pressure(100.0f);                            // Reduce to sea level, (altitude_m m) → float hPa
    float dp = bme.dew_point();                                            // Dew point from T and RH, () → float °C
                                                                           // Magnus approximation
    printf("status=0x%02X t=%.2f C h=%.2f %%RH p=%.2f hPa alt=%.1f m slp=%.2f hPa dew=%.2f C\n",
           st, (double)t, (double)h, (double)p, (double)alt, (double)slp, (double)dp);

    bme.reset();                                                           // Soft reset, () → void
                                                                           // all registers return to power-on defaults
    return 0;
}
