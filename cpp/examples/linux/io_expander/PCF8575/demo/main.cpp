#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8575.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCF8575Full pcf(connection);                                            // Create PCF8575 driver, (connection)

    // --- 4-LED bargraph chaser on P4–P7 ---
    // Lights one LED at a time across the upper nibble of port 0; the
    // lower nibble stays high so those pins keep working as inputs.
    for (int i = 0; ; i = (i + 1) & 3) {
        pcf.write_port(0, (uint8_t)(0x0F | (0x10 << i)));                 // Write port, (port, mask) → void
        usleep(200000);
    }
    return 0;
}
