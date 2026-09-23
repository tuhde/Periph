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

    // --- Indoor climate monitor ---
    // Weather-monitoring profile from the datasheet: ×1 oversampling, no
    // filter, one sample per second. Altitude comes from the barometric
    // formula; update the sea-level reference to the local forecast.
    const float P0_HPA = 1013.25f;
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1,
                  BME280Full::MODE_NORMAL, BME280Full::FILTER_OFF, BME280Full::T_SB_1000_MS);  // Configure in one write, (osrs_t, osrs_p, osrs_h, mode, filter, t_sb) → void
    while (true) {
        float t = bme.temperature();                                       // Read temperature, () → float °C
        float h = bme.humidity();                                          // Read relative humidity, () → float %RH
        float p = bme.pressure();                                          // Read pressure, () → float hPa
        float alt = bme.altitude(P0_HPA);                                  // Estimate altitude, (sea_level_hpa hPa) → float m
        float dp = bme.dew_point();                                        // Dew point from T and RH, () → float °C
        printf("%.2f C  %.2f %%RH  %.2f hPa  %.1f m  dew %.2f C\n",
               (double)t, (double)h, (double)p, (double)alt, (double)dp);
        usleep(1000000);
    }
    return 0;
}
