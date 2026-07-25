#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "BME680.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CTransportLinux transport(bus, addr);

    BME680Minimal bme(transport);                                          // Create BME680 driver, (transport)

    while (true) {
        float t, h, p, gas;
        bme.read(t, h, p, gas);                                            // Trigger forced-mode measurement, (temp_c, humidity_pct, pressure_pa, gas_ohm) → void
        printf("%.2f C  %.2f %%RH  %.2f Pa  %.0f Ω\n", t, h, p, gas);
        usleep(1000000);
    }
    return 0;
}
