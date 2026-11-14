#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HTU21DF.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
    sensor.soft_reset();                                                // Soft reset, () → void
    reg = sensor.read_user_register();                                  // Read the user register, () → uint8_t
    printf("user register 0x%02X\n", (unsigned)reg);
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printf("resolution setting %u\n", (unsigned)res);

    // --- Five readings at 12/14 bit ---
    for (uint8_t i = 0; i < 5; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printf("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C\n", t, rh, sensor.read_humidity_compensated(), sensor.dew_point());
        // sensor.read_humidity_compensated() → float %RH ; sensor.dew_point() → float °C
        usleep(2000 * 1000);
    }
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
    printf("raw T %u raw RH %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.set_resolution(1);                                           // Set resolution, (res) → bool
    uint32_t t0 = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    sensor.read(t, rh);
    uint32_t dt = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count() - t0;
    printf("8/12 bit: %.2f C %.1f %%RH in %lu ms\n", t, rh, (unsigned long)dt);
    sensor.set_resolution(0);                                           // Back to 12/14 bit
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    usleep(10000 * 1000);
    printf("heater on %u\n", (unsigned)(sensor.heater()));
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    return 0;
}
