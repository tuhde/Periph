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

    // --- Air quality monitor ---
    // One forced measurement per second. Gas resistance rises with cleaner
    // air; readings with an unstable heater are skipped, and a simple
    // good/moderate/poor label is printed from the raw resistance.
    bme.configure(BME680Full::OSRS_X2, BME680Full::OSRS_X16, BME680Full::OSRS_X1,
                  BME680Full::MODE_FORCED, BME680Full::FILTER_3);          // Configure in one write, (osrs_t, osrs_p, osrs_h, mode, filter) → void
    bme.set_heater(320, 150);                                              // Set heater profile 0, (temp_c °C, duration_ms ms) → void
    while (true) {
        float t, p, h, g;
        bme.read_all(t, p, h, g);                                          // One forced measurement of every channel, (t °C, p hPa, h %RH, g Ω) → void
        if (!bme.heater_stable()) {                                        // Heater reached target?, () → bool
            printf("heater not stable yet\n");
        } else {
            const char* aq = g > 50000.0f ? "good" : g > 10000.0f ? "moderate" : "poor";
            printf("%.2f C  %.2f %%RH  %.2f hPa  %.0f Ohm  [%s]\n",
                   (double)t, (double)h, (double)p, (double)g, aq);
        }
        usleep(1000000);
    }
    return 0;
}
