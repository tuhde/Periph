#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ENS160.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x52;
    I2CConnectionLinux connection(bus, addr);

    ENS160Full ens(connection);                                             // Create ENS160 driver, (connection)

    // --- Office air quality display ---
    // Logs AQI, TVOC and eCO2 every 10 s with the UBA AQI label. A fixed
    // 22 °C / 45 %RH compensation stands in for a companion T/H sensor.
    ens.set_compensation(22.0f, 45.0f);                                    // Set T/H compensation, (temp_celsius °C, rh_percent %RH) → void
    const char* aqi_label[] = {"", "Excellent", "Good", "Moderate", "Poor", "Unhealthy"};
    while (true) {
        uint8_t aqi;
        float tvoc, eco2;
        if (ens.read_air_quality(aqi, tvoc, eco2))                         // Read AQI/TVOC/eCO2, (aqi 1–5, tvoc_ppb ppb, eco2_ppm ppm) → bool
            printf("AQI=%u %s  TVOC=%4.0f ppb  eCO2=%4.0f ppm\n",
                   aqi, aqi <= 5 ? aqi_label[aqi] : "?", (double)tvoc, (double)eco2);
        usleep(10000000);
    }
    return 0;
}
