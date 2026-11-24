#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Bh1750.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x23;
    I2CConnectionLinux connection(bus, addr);

    BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void

    // --- Light meter ---
    // Reads the illuminance once in each one-time mode, prints continuous H-mode
    // values ten times per second for 5 s (cover and uncover the sensor), shows the
    // effect of the lowest and highest sensitivity on the raw count and the
    // measurement time, and finishes with reset and a power cycle.
    float lux = 0;
    uint16_t raw = 0;
    sensor.measure_once(BH1750Full::MODE_H, lux);                   // One-time measurement, (mode, lux&) → bool
    printf("H  %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_H2, lux);
    printf("H2 %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_L, lux);
    printf("L  %.2f lx\n", (double)(lux));
    sensor.start_continuous(BH1750Full::MODE_H);                    // Start continuous mode, (mode) → bool
    for (int i = 0; i < 50; i++) {
        usleep(100 * 1000);
        sensor.read_continuous(lux);                                // Read the latest result, (lux&) → bool
        printf("%.1f lx\n", (double)(lux));
    }
    sensor.set_sensitivity(0.45f);                                  // Set sensitivity, (factor) → bool
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printf("x0.45: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(3.68f);
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printf("x3.68: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(1.0f);
    sensor.reset();                                                 // Clear the data register, () → void
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
    return 0;
}
