#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "SHT31.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x44);
    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.soft_reset();                                                // Soft reset, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X\n", (unsigned)status);
    // --- Single shots at all three repeatability levels ---
    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);                                // Select repeatability, (level) → bool
        uint32_t t0 = to_ms_since_boot(get_absolute_time());
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        uint32_t dt = to_ms_since_boot(get_absolute_time()) - t0;
        printf("level %u: %.2f C %.1f %%RH (%lu ms)\n", (unsigned)level, t, rh, (unsigned long)dt);
    }
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
    printf("raw %u %u, T %.2f, RH %.1f\n", (unsigned)raw_t, (unsigned)raw_rh, sensor.read_temperature(), sensor.read_humidity());

    // --- Periodic mode at 2 mps: ten samples ---
    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
    for (uint8_t got = 0; got < 10;) {
        if (!sensor.fetch(t, rh)) {                                     // Fetch the latest periodic result, (t, rh) → bool
            sleep_ms(50);
            continue;
        }
        got++;
        printf("periodic %u: %.2f C %.1f %%RH\n", (unsigned)got, t, rh);
    }
    sensor.stop_periodic();                                             // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    float before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    sleep_ms(10000);
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("heater status bit: %u\n", (unsigned)((status >> 13) & 1));
    printf("temperature rise: %.2f K\n", sensor.read_temperature() - before);
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
    sensor.read_status(status);                                         // Read status register, (status) → bool
    printf("status 0x%04X, dew point %.2f C\n", (unsigned)status, sensor.dew_point());
    while (true) sleep_ms(1000);
    return 0;
}
