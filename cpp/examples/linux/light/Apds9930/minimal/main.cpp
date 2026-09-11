#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Apds9930.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);                                 // Create I2C connection, (bus, addr) → I2CConnectionLinux

    APDS9930Minimal apds(connection);                                          // Create APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                               // initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20

    usleep(110 * 1000);
    while (true) {
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
                                                                               // IR-compensated lux via Ch0/Ch1 difference
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
                                                                               // 16-bit ADC value; higher = closer object
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        usleep(1000 * 1000);
    }
    return 0;
}