#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "APDS9960.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);

    APDS9960Minimal apds(connection);                                       // Create APDS9960 driver, (connection)
                                                                           // ALS enabled, ATIME 0xB6 (~200 ms), gain 4×

    while (true) {
        uint16_t c, r, g, b;
        apds.color(c, r, g, b);                                            // Read all four channels, (clear, red, green, blue) → void
        printf("c=%u r=%u g=%u b=%u\n", c, r, g, b);
        usleep(500000);
    }
    return 0;
}
