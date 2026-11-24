#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Ms5611.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x77;
    I2CConnectionLinux connection(bus, addr);

    MS5611Minimal sensor(connection);                               // Create MS5611 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    while (true) {
        float t = 0, p = 0;
        bool ok = sensor.read(t, p);                                // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        printf("temperature %.2f C pressure %.1f Pa (ok=%lu)\n", (double)(t), (double)(p), (unsigned long)(ok));
        usleep(1000 * 1000);
    }
    return 0;
}
