#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Spl06.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    SPL06Minimal sensor(connection);                               // Create SPL06 driver, (connection) → void
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                               // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printf("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        usleep((unsigned)(1000) * 1000);
    }
    return 0;
}
