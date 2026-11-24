#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Htu31d.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    HTU31DMinimal sensor(connection);                               // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
    while (true) {
        float t = 0, rh = 0;
        bool ok = sensor.read(t, rh);                               // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("temperature %.2f C humidity %.1f %%RH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        usleep(2000 * 1000);
    }
    return 0;
}
