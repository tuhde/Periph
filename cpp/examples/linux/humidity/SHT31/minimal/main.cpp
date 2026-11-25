#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "SHT31.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x44;
    I2CConnectionLinux connection(bus, addr);

    SHT31Minimal sensor(connection);                                    // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;

    while (true) {
        bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%.2f C, %.1f %%RH (ok=%u)\n", t, rh, (unsigned)ok);
        usleep(2000 * 1000);
    }
    return 0;
}
