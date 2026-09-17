#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Mpr121.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x5A;
    I2CConnectionLinux connection(bus, addr);                                 // Create I2C connection, (bus, addr) → I2CConnectionLinux

    MPR121Minimal mpr(connection);                                             // Create MPR121 Minimal, (connection) → MPR121Minimal

    while (true) {
        uint16_t t = mpr.touched();                                            // Read 12-bit touch bitmask, () → uint16_t bitmask
        printf("touched=0x%03X\n", t);
        usleep(1000 * 1000);
    }
    return 0;
}
