#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Hts221.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x5F;
    I2CConnectionLinux connection(bus, addr);

    HTS221Minimal sensor(connection);                               // Create HTS221 driver, (connection) → void
    bool ok = sensor.init();                                        // Read calibration and start one-shot mode, () → bool
    float t = 0, rh = 0;

    while (true) {
        ok = sensor.read(t, rh);                                    // Read temperature and humidity, (temperature_c, humidity_pct) → bool
        printf("temperature %.2f C humidity %.1f %%rH (ok=%lu)\n", (double)(t), (double)(rh), (unsigned long)(ok));
        usleep(2000 * 1000);
    }
    return 0;
}
