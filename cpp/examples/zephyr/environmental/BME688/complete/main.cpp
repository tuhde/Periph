#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BME688.h"

#ifndef BME688_I2C_NODE
#define BME688_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BME688_ADDR
#define BME688_ADDR 0x76
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BME688_I2C_NODE);
    I2CConnectionZephyr connection(dev, BME688_ADDR);
    BME688Full bme(connection);                           // Create BME688 driver, (connection)
    uint8_t cid = bme.chip_id();                       // Read chip ID, () → int
    check_true(cid == 0x61, "chip_id");

    bme.configure(BME688Full::OSRS_X1, BME688Full::OSRS_X1, BME688Full::OSRS_X1, BME688Full::MODE_SLEEP, BME688Full::FILTER_0);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → void
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME688Full::OSRS_X4, BME688Full::OSRS_X2, BME688Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
                                                        // changes conversion time vs resolution trade-off
    bme.set_filter(BME688Full::FILTER_7);               // Set IIR filter, (coeff 0–7) → void
                                                        // applies to temperature and pressure only
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c, duration_ms) → void
                                                        // sets target temperature and on-time, then selects profile 0
    bme.set_heater_profile(1, 200, 100);                // Configure heater profile 1, (index 0–9, temp_c, duration_ms) → void
                                                        // stores profile 1 without activating it
    bme.select_heater_profile(1);                       // Activate heater profile 1, (index 0–9) → void
                                                        // subsequent measurements use profile 1's heater settings
    bme.select_heater_profile(0);                       // Switch back to profile 0, (index 0–9) → void
                                                        // profile 0 is the default 320 °C / 150 ms configuration
    bme.set_gas_enabled(false);                         // Disable gas conversion, (enabled) → void
                                                        // skips the gas measurement phase to save power and time
    bme.set_gas_enabled(true);                          // Re-enable gas conversion, (enabled) → void
                                                        // restores gas measurement in the forced-mode cycle
    bme.set_heater_off(true);                           // Disable heater via heat_off override, (off) → void
                                                        // prevents heater activation regardless of profile settings
    bme.set_heater_off(false);                          // Re-enable heater, (off) → void
                                                        // clears the heat_off override bit
    bme.set_ambient_temperature(25.0f);                 // Override ambient for heater calc, (temp_c) → void
                                                        // recomputes heater resistance register using the new ambient value
    uint8_t st = bme.status();                          // Read status register, () → uint8_t
                                                        // bit 7 = new_data, bit 6 = gas_measuring, bit 5 = measuring

    float t, p, h, g;
    bme.read_all(t, p, h, g);                           // Read all sensors in one cycle, (t, p, h, g) → void
                                                        // returns (T, P, RH, R_gas) from single TPHG trigger
    bool gv = bme.gas_valid();                          // Check gas validity, () → bool
    bool hs = bme.heater_stable();                      // Check heater stability, () → bool
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
    bme.reset();                                        // Soft reset chip, () → void

    printk("T=%.1f C, P=%.1f hPa, RH=%.1f %%, R_gas=%.0f Ohm\n", (double)t, (double)p, (double)h, (double)g);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
