#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8574.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CTransportLinux transport(bus, addr);

    PCF8574Full pcf(transport);                                            // Create PCF8574 driver, (transport)

    // --- 4-bit LED bargraph driven by upper nibble ---
    // Sets each bit in the upper nibble in turn to create a simple chaser.
    for (int i = 0; ; i = (i + 1) & 3) {
        pcf.write((uint8_t)(0x10 << i));                                   // Write all 8 pins, (value) → void
        usleep(200000);
    }
    return 0;
}
