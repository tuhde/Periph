#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME680.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME680Full bme(connection);                                             // Create BME680 driver, (connection)

    // --- IAQ index monitor ---
    // Reads all four channels at 1 Hz. Gas resistance rises with cleaner air.
    // Print a simple "good/moderate/poor" classification using raw resistance.
    bme.configure(2, 5, 1, 4, 320, 150);                                  // Configure oversampling+filter+heater, (...) → void
    while (true) {
        float t, h, p, gas;
        bme.read(t, h, p, gas);                                            // Trigger forced-mode measurement, (temp_c, humidity_pct, pressure_pa, gas_ohm) → void
        const char* aq = gas > 50000 ? "good" : gas > 10000 ? "moderate" : "poor";
        printf("%.2f C  %.2f %%  %.1f Pa  %.0f Ω  [%s]\n", t, h, p, gas, aq);
        usleep(1000000);
    }
    return 0;
}
