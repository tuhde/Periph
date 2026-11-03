#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MB85RC.h"

static void sleep_ms(unsigned ms) { usleep(ms * 1000); }

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x50;
    I2CConnectionLinux connection(bus, addr, nullptr, nullptr, 2);          // Create I2C connection, (bus, addr, int=null, en=null, reg_bytes=2)
    MB85RCMinimal fram(connection);                                             // Create MB85RC driver, (connection) → void
    uint8_t counter = 0;
    while (true) {
        fram.write_byte(0x0000, counter);                                   // Write a single byte, (address 0–32767, value 0–255) → bool
        uint8_t value = fram.read_byte(0x0000);                             // Read a single byte, (address 0–32767) → uint8_t
        printf("Stored: %u\n", (unsigned)value);
        counter++;
        sleep_ms(1000);
    }
    return 0;
}
