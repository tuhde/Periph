#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "APDS9960.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CTransportLinux transport(bus, addr);

    APDS9960Minimal apds(transport);                                       // Create APDS9960 driver, (transport)

    while (true) {
        uint16_t r, g, b, c;
        apds.read_color(r, g, b, c);                                       // Read RGBC channels, (r, g, b, clear) → void
        printf("r=%u g=%u b=%u c=%u\n", r, g, b, c);
        usleep(500000);
    }
    return 0;
}
