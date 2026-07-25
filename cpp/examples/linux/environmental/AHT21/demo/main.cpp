#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "AHT21.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CTransportLinux transport(bus, addr);

    AHT21Full aht(transport);                                              // Create AHT21 driver, (transport, addr=0x38) → void

    // --- Greenhouse humidity alert ---
    // Sample every 10 s and warn when humidity exceeds 80 %RH or drops
    // below 40 %RH — the comfort range for most plants.
    while (true) {
        float t, h;
        aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
        const char* status = h > 80.0f ? "HIGH" : h < 40.0f ? "LOW" : "OK";
        printf("%.2f C  %.2f %%RH  [%s]\n", t, h, status);
        usleep(10000000);
    }
    return 0;
}
