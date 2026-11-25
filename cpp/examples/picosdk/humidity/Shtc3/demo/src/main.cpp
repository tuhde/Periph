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

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    sensor.read_id(id);                                             // Read the ID word, (id&) → bool
    printf("id %02X\n", (unsigned)(id));

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for (int i = 0; i < 6; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("%.2f C %.1f %%RH\n", (double)(t), (double)(rh));
        sleep_ms(10000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    sensor.read(t, rh);
    printf("normal    %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
    sensor.read(t, rh);
    printf("low power %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(false);

    // --- Dew point and raw words, then finish asleep ---
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
    sensor.read_raw(t_raw, rh_raw);                                 // Read raw codes, (t_raw&, rh_raw&) → bool
    printf("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    sensor.sleep();                                                 // Sleep command, () → void
    return 0;
}
