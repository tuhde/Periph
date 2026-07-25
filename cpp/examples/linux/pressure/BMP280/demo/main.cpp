#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "BMP280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CTransportLinux transport(bus, addr);

    BMP280Full bmp(transport);                                             // Create BMP280 driver, (transport)

    // --- Altitude tracker ---
    // Uses the hypsometric formula with a local sea-level reference.
    const float P0 = 101325.0f;
    bmp.configure(5, 5, 3, 4);                                            // Configure oversampling+filter, (osrs_t=×16, osrs_p=×16, normal, filter=16) → void
    while (true) {
        float p = bmp.pressure();                                          // Read pressure, () → float Pa
        float alt = 44330.0f * (1.0f - __builtin_powf(p / P0, 0.1903f));
        printf("%.2f Pa  %.1f m\n", p, alt);
        usleep(1000000);
    }
    return 0;
}
