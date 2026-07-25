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

    ENS160Minimal ens(transport);                                          // Create ENS160 driver, (transport)

    while (true) {
        uint16_t eco2 = ens.eco2();                                        // Read eCO2, () → uint16_t ppm
        uint16_t tvoc = ens.tvoc();                                        // Read TVOC, () → uint16_t ppb
        printf("eCO2=%u ppm  TVOC=%u ppb\n", eco2, tvoc);
        usleep(1000000);
    }
    return 0;
}
