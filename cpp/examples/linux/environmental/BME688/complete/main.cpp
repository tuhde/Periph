#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME688.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME688Full bme(connection);                                             // Create BME688 driver, (connection)

    uint8_t id = bme.chip_id();                                            // Read chip ID, () → uint8_t
                                                                           // 0x61 for BME688
    printf("chip_id=0x%02X\n", id);

    bme.configure(BME688Full::OSRS_X2, BME688Full::OSRS_X16, BME688Full::OSRS_X1,
                  BME688Full::MODE_FORCED, BME688Full::FILTER_3);          // Configure in one write, (osrs_t, osrs_p, osrs_h, mode, filter) → void
    bme.set_oversampling(BME688Full::OSRS_X2, BME688Full::OSRS_X4, BME688Full::OSRS_X2);  // Set oversampling, (osrs_t, osrs_p, osrs_h) → void
    bme.set_filter(BME688Full::FILTER_7);                                  // Set IIR filter coefficient, (coeff) → void

    bme.set_ambient_temperature(22.0f);                                    // Ambient estimate for heater math, (temp_c °C) → void
                                                                           // improves heater-resistance calculation before the first reading
    bme.set_heater(320, 150);                                              // Set heater profile 0, (temp_c °C, duration_ms ms) → void
    bme.set_heater_profile(1, 250, 100);                                   // Set another heater step, (index 0–9, temp_c °C, duration_ms ms) → void
    bme.select_heater_profile(0);                                          // Choose the profile used next, (index 0–9) → void
    bme.set_gas_enabled(true);                                             // Enable gas measurement, (enabled) → void
    bme.set_heater_off(false);                                             // Keep the heater powered, (off) → void

    float t, p, h, g;
    bme.read_all(t, p, h, g);                                              // One forced measurement of every channel, (t °C, p hPa, h %RH, g Ω) → void
                                                                           // a single conversion keeps all four values coherent
    printf("t=%.2f C p=%.2f hPa h=%.2f %%RH g=%.0f Ohm\n", (double)t, (double)p, (double)h, (double)g);
    bool valid = bme.gas_valid();                                          // Gas reading valid?, () → bool
    bool stable = bme.heater_stable();                                     // Heater reached target?, () → bool
                                                                           // unstable readings should be discarded
    uint8_t st = bme.status();                                             // Read measurement status, () → uint8_t
    printf("gas_valid=%d heater_stable=%d status=0x%02X\n", valid, stable, st);

    uint8_t vid = bme.variant_id();                        // Read variant ID, () → uint8_t
                                                        // 0x01 for the BME688 (tells it apart from the BME680)
    (void)vid;
    const int16_t ptemps[2] = {320, 250};
    const uint8_t prepeats[2] = {1, 2};
    bme.start_parallel(ptemps, prepeats, 2, 100.0f);    // Start parallel mode, (temps, repeats, steps 1–10, shared_wait_ms) → void
                                                        // programs res_heat_x, gas_wait_x, gas_wait_shared, nb_conv, then mode=parallel
    BME688Field fields[3];
    uint8_t nfields = bme.read_parallel(fields, 3);     // Read new data fields, (out, max) → uint8_t count
    (void)nfields;
    bme.stop_parallel();                                // Leave parallel mode, () → void
    bme.reset();                                                           // Soft reset, () → void
    return 0;
}
