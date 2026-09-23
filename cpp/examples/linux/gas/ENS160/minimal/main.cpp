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

    ENS160Minimal ens(connection);                                          // Create ENS160 driver, (connection)
                                                                           // enters STANDARD operating mode

    while (true) {
        uint8_t aqi;
        float tvoc, eco2;
        if (ens.read_air_quality(aqi, tvoc, eco2))                         // Read AQI/TVOC/eCO2, (aqi 1–5, tvoc_ppb ppb, eco2_ppm ppm) → bool
            printf("AQI=%u  TVOC=%.0f ppb  eCO2=%.0f ppm\n", aqi, (double)tvoc, (double)eco2);
        usleep(1000000);
    }
    return 0;
}
