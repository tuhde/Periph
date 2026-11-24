#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Mpl3115a2.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MPL3115A2Minimal sensor(connection);                           // Create MPL3115A2 driver, (connection) → void
    sensor.init();                                                 // Reset and apply defaults, () → bool
    while (true) {
        float p = 0, t = 0;
        bool ok = sensor.read(p, t);                               // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        printf("pressure %.2f Pa temperature %.2f C (ok=%lu)\n", (double)(p), (double)(t), (unsigned long)(ok));
        usleep(2000 * 1000);
    }
    return 0;
}
