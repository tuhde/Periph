#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "AS5600.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x36;
    I2CTransportLinux transport(bus, addr);

    AS5600Minimal as(transport);                                           // Create AS5600 driver, (transport)

    while (true) {
        float angle = as.angle();                                          // Read angle, () → float °  (0–360)
        printf("%.2f °\n", angle);
        usleep(100000);
    }
    return 0;
}
