#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Bh1750.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x23;
    I2CConnectionLinux connection(bus, addr);

    BH1750Minimal sensor(connection);                               // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void
    while (true) {
        float lux = 0;
        bool ok = sensor.read(lux);                                 // Measure illuminance, (lux&) → bool
        printf("illuminance %.1f lx (ok=%lu)\n", (double)(lux), (unsigned long)(ok));
        usleep(1000 * 1000);
    }
    return 0;
}
