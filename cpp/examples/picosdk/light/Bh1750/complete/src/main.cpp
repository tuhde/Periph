#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Bh1750.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x23);
    BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float lux = 0;
    uint16_t raw = 0;
    bool ok = sensor.read(lux);                                     // Measure illuminance, (lux&) → bool
                                                                    // one-time H-resolution (1 lx), waits 180 ms
    printf("illuminance %.1f lx\n", (double)(lux));
    ok = sensor.read_raw(raw);                                      // Read the raw count, (raw&) → bool
                                                                    // 16-bit, one one-time measurement in the current mode
    printf("raw %lu\n", (unsigned long)(raw));
    ok = sensor.measure_once(BH1750Full::MODE_H2, lux);             // One-time measurement, (mode, lux&) → bool
                                                                    // MODE_H 1 lx, MODE_H2 0.5 lx, MODE_L 4 lx and 24 ms
    printf("H2 %.2f lx\n", (double)(lux));
    printf("measurement time %.0f ms\n", (double)(sensor.measurement_time() * 1000.0f)); // Maximum measurement time, (mode=MODE_H) → float s
    printf("lux per count %.3f\n", (double)(sensor.lux_per_count())); // Lux per raw count, (mode=MODE_H) → float
    ok = sensor.start_continuous(BH1750Full::MODE_H);               // Start continuous mode, (mode=MODE_H) → bool
                                                                    // first result after measurement_time()
    sleep_ms(200);
    ok = sensor.read_continuous(lux);                               // Read the latest result, (lux&) → bool
                                                                    // no wait
    printf("continuous %.1f lx\n", (double)(lux));
    ok = sensor.set_measurement_time(138);                          // Set MTreg, (mtreg 31-254) → bool
                                                                    // default 69; larger = more sensitive and slower
    printf("mtreg %lu\n", (unsigned long)(sensor.get_measurement_time_register())); // Get MTreg, () → uint8_t
                                                                    // cached, the chip has no read-back
    ok = sensor.set_sensitivity(1.0f);                              // Set sensitivity, (factor 0.45-3.68) → bool
                                                                    // MTreg = round(69 x factor)
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
    sensor.reset();                                                 // Clear the data register, () → void
                                                                    // powers on first; MTreg and mode are kept
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
