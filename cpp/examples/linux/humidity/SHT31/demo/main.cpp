#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "SHT31.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x44;
    I2CConnectionLinux connection(bus, addr);

    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.soft_reset();                                                // Soft reset, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X\n", (unsigned)status);
    // --- Single shots at all three repeatability levels ---
    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);                                // Select repeatability, (level) → bool
        uint32_t t0 = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        uint32_t dt = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count() - t0;
        printf("level %u: %.2f C %.1f %%RH (%lu ms)\n", (unsigned)level, t, rh, (unsigned long)dt);
    }
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
    printf("raw %u %u, T %.2f, RH %.1f\n", (unsigned)raw_t, (unsigned)raw_rh, sensor.read_temperature(), sensor.read_humidity());

    // --- Periodic mode at 2 mps: ten samples ---
    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
    for (uint8_t got = 0; got < 10;) {
        if (!sensor.fetch(t, rh)) {                                     // Fetch the latest periodic result, (t, rh) → bool
            usleep(50 * 1000);
            continue;
        }
        got++;
        printf("periodic %u: %.2f C %.1f %%RH\n", (unsigned)got, t, rh);
    }
    sensor.stop_periodic();                                             // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    float before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    usleep(10000 * 1000);
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("heater status bit: %u\n", (unsigned)((status >> 13) & 1));
    printf("temperature rise: %.2f K\n", sensor.read_temperature() - before);
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X, dew point %.2f C\n", (unsigned)status, sensor.dew_point());
    return 0;
}
