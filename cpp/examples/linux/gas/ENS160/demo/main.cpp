#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "ENS160.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x52;
    I2CTransportLinux transport(bus, addr);

    ENS160Full ens(transport);                                             // Create ENS160 driver, (transport)

    // --- Office air quality display ---
    // Logs eCO2 and TVOC at 10 s intervals with AQI colour band.
    ens.set_temp_rh_comp(22.0f, 45.0f);                                    // Set T/H compensation, (temp_c, humidity_pct) → void
    const char* aqi_label[] = {"", "Excellent", "Good", "Moderate", "Poor", "Unhealthy"};
    while (true) {
        uint16_t eco2 = ens.eco2();                                        // Read eCO2, () → uint16_t ppm
        uint16_t tvoc = ens.tvoc();                                        // Read TVOC, () → uint16_t ppb
        uint8_t  aqi  = ens.aqi();                                         // Read AQI (UBA 1–5), () → uint8_t
        printf("eCO2=%4u ppm  TVOC=%4u ppb  AQI=%u %s\n",
               eco2, tvoc, aqi, aqi <= 5 ? aqi_label[aqi] : "?");
        usleep(10000000);
    }
    return 0;
}
