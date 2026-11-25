#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HTU21DF.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
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
        sleep_ms(2000);
    }
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
    printf("raw T %u raw RH %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.set_resolution(1);                                           // Set resolution, (res) → bool
    uint32_t t0 = to_ms_since_boot(get_absolute_time());
    sensor.read(t, rh);
    uint32_t dt = to_ms_since_boot(get_absolute_time()) - t0;
    printf("8/12 bit: %.2f C %.1f %%RH in %lu ms\n", t, rh, (unsigned long)dt);
    sensor.set_resolution(0);                                           // Back to 12/14 bit
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    sleep_ms(10000);
    printf("heater on %u\n", (unsigned)(sensor.heater()));
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    while (true) sleep_ms(1000);
    return 0;
}
