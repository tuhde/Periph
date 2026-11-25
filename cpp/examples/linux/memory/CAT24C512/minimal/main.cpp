#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "CAT24C512.h"


int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x50;
    I2CConnectionLinux connection(bus, addr, nullptr, nullptr, 2);          // Create I2C connection, (bus, addr, int=null, en=null, reg_bytes=2)
    CAT24C512Minimal ee(connection);                                    // Create CAT24C512 driver, (connection) → void
    uint8_t v = 0;
    uint8_t counter = 0;

    while (true) {
        ee.write_byte(0x0000, counter);                                 // Write a single byte, (address 0-65535, value 0-255) → bool
        v = ee.read_byte(0x0000);                                       // Read a single byte, (address 0-65535) → uint8_t
        printf("byte 0x%02X\n", (unsigned)v);
        counter++;
        usleep(1000 * 1000);
    }
    return 0;
}
