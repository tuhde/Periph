#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Si1133.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x55;
    I2CConnectionLinux connection(bus, addr);

    Si1133Minimal sensor(connection);                              // Create Si1133 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    while (true) {
        int32_t uv = 0, white = 0;
        bool ok = sensor.read(uv, white);                          // Forced measurement, (uv&, white&) → bool
        printf("UV %ld white %ld (ok=%u)\n", (long)(uv), (long)(white), (unsigned)(ok));
        usleep((unsigned)(1000) * 1000);
    }
    return 0;
}
