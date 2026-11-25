#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HS30xx.h"

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { usleep(100 * 1000); }

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x44;
    I2CConnectionLinux connection(bus, addr);

    HS30xxFull sensor(connection);                                  // Create HS30xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    // --- Optional: show and change the stored resolution (set show_resolution to true) ---
    // The NVM is only reachable within 10 ms after power-up, so this needs power_cycle().
    const bool show_resolution = false;
    if (show_resolution) {
    sensor.get_resolution(hb, tb, power_cycle);                     // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    printf("resolution %lu/%lu bit\n", (unsigned long)(hb), (unsigned long)(tb));
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
    sensor.set_measurement_wait(15);                                // 12-bit RH + T need about 10 ms, (ms) → void
    }
    // --- Hygrometer: one reading every 2 s for 30 s ---
    // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
    for (uint8_t n = 0; n < 15; n++) {
        sensor.read_raw(rh_raw, t_raw, status);                     // Read raw values, (humidity_raw, temperature_raw, status) → bool
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%lu s: RH raw %lu T raw %lu status %lu | %.2f C %.1f %%RH dew %.2f C\n", (unsigned long)(n * 2), (unsigned long)(rh_raw), (unsigned long)(t_raw), (unsigned long)(status), t, rh, sensor.dew_point());
        usleep(2000 * 1000);
    }
    return 0;
}
