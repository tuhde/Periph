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
                                                                           // quasi-bidirectional: all pins start high (inputs)

    while (true) {
        printf("port0=0x%02X port1=0x%02X\n", pcf.read_port(0), pcf.read_port(1));  // Read port, (port) → uint8_t bitmask
        pcf.write_port(0, 0xAA);                                               // Write port, (port 0/1, mask) → void
        pcf.write_port(1, 0x55);
        usleep(500000);
    }
    return 0;
}
