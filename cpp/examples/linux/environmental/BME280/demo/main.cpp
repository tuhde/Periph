#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME280.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME280Full bme(connection);                                             // Create BME280 driver, (connection)

    // --- Indoor air quality monitor ---
    // Read T/H/P every second and compute altitude from pressure.
    // Sea-level reference pressure (Pa) — update to local forecast for accuracy.
    const float P0 = 101325.0f;
    bme.configure(2, 5, 1, 4, 1);                                         // Configure oversampling+filter+standby, (...) → void
    while (true) {
        float t, h, p;
        bme.read(t, h, p);                                                 // Read T/H/P with compensation, (temp_c, humidity_pct, pressure_pa) → void
        // Hypsometric formula: altitude from pressure ratio
        float alt = 44330.0f * (1.0f - __builtin_powf(p / P0, 0.1903f));
        printf("%.2f C  %.2f %%RH  %.1f Pa  %.1f m\n", t, h, p, alt);
        usleep(1000000);
    }
    return 0;
}
