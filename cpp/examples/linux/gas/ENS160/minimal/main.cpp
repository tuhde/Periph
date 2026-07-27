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

    while (true) {
        uint16_t eco2 = ens.eco2();                                        // Read eCO2, () → uint16_t ppm
        uint16_t tvoc = ens.tvoc();                                        // Read TVOC, () → uint16_t ppb
        printf("eCO2=%u ppm  TVOC=%u ppb\n", eco2, tvoc);
        usleep(1000000);
    }
    return 0;
}
