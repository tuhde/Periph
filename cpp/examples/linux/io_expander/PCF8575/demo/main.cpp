#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8575.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CTransportLinux transport(bus, addr);

    PCF8575Full pcf(transport);                                            // Create PCF8575 driver, (transport)

    // --- Knight-rider chaser across all 16 outputs ---
    int pos = 0, dir = 1;
    while (true) {
        pcf.write((uint16_t)(1 << pos));                                   // Write all 16 pins, (value) → void
        pos += dir;
        if (pos == 15 || pos == 0) dir = -dir;
        usleep(60000);
    }
    return 0;
}
