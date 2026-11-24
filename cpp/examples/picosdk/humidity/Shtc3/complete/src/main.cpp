#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Shtc3.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void
    sensor.init();                                                  // Soft reset and ID check, () → bool
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // normal mode, both CRCs verified, one retry on a bad CRC
    printf("temperature %.2f C humidity %.1f %%RH\n", (double)(t), (double)(rh));
    printf("humidity %.1f %%RH\n", (double)(sensor.read_humidity())); // Read humidity only, () → float %RH
                                                                    // NAN on failure
    printf("temperature %.2f C\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printf("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.read_id(id);                                        // Read the ID word, (id&) → bool
                                                                    // (id & 0x083F) == 0x0807 for the SHTC3
    printf("id %02X\n", (unsigned)(id));
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
                                                                    // 0.7 ms measurement, slightly noisier
    ok = sensor.read(t, rh);
    printf("low power %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(false);
    sensor.set_clock_stretching(true);                              // Select clock stretching, (on) → void
                                                                    // needs a controller that tolerates SCL held low
    ok = sensor.read(t, rh);
    sensor.set_clock_stretching(false);
    sensor.set_sleep_between(false);                                // Sleep after every read?, (on) → void
                                                                    // off keeps the sensor idle (higher supply current)
    ok = sensor.read(t, rh);
    sensor.set_sleep_between(true);
    sensor.wake();                                                  // Wake-up command, () → void
    sensor.sleep();                                                 // Sleep command, () → void
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    sensor.soft_reset();                                            // Soft reset, () → void
                                                                    // 0x805D, leaves the sensor idle
    printf("ok %lu\n", (unsigned long)(ok));
    return 0;
}
