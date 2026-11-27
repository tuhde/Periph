#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Si1145.h"

static unsigned uv_whole(float uv) { return (unsigned)(uv * 100.0f + 0.5f) / 100; }
static unsigned uv_frac(float uv) { return (unsigned)(uv * 100.0f + 0.5f) % 100; }

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    Si1145Minimal sensor(connection);                              // Create Si1145 driver, (connection) → void
    sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    while (true) {
        Si1145Reading r;
        bool ok = sensor.read(r);                                  // Forced PS + ALS, (reading&) → bool
        printf("prox %u UV %u.%02u vis %u ir %u (ok=%u)\n", (unsigned)r.proximity, uv_whole(r.uv_index),
              uv_frac(r.uv_index), (unsigned)r.visible, (unsigned)r.infrared, (unsigned)ok);
        usleep((unsigned)(1000) * 1000);
    }
    return 0;
}
