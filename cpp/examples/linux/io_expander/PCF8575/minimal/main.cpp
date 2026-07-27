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

    PCF8575Minimal pcf(connection);                                         // Create PCF8575 driver, (connection)

    while (true) {
        uint16_t val = pcf.read();                                         // Read all 16 pins, () → uint16_t
        printf("port=0x%04X\n", val);
        pcf.write(0xAAAA);                                                 // Write all 16 pins, (value) → void
        usleep(500000);
    }
    return 0;
}
