#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Shtc3.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x70;
    I2CConnectionLinux connection(bus, addr);

    SHTC3Minimal sensor(connection);                                // Create SHTC3 driver, (connection) → void
    sensor.init();                                                  // Soft reset and ID check, () → bool
    while (true) {
        float t = 0, rh = 0;
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("temperature %.2f C humidity %.1f %%RH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        usleep(2000 * 1000);
    }
    return 0;
}
