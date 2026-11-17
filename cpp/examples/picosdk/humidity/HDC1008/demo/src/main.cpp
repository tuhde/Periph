#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HDC1008.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    HDC1008Full sensor(connection);                                     // Create HDC1008 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, dew);
        sleep_ms(2000);
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    sleep_ms(10000);
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh0 - rh1, t1c - t0c);
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = to_ms_since_boot(get_absolute_time());
    sensor.read(t, rh);
    uint32_t dt = to_ms_since_boot(get_absolute_time()) - ts;
    printf("11/8 bit: %.2f C %.1f %%RH in %u ms\n", t, rh, (unsigned)(dt));
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    while (true) sleep_ms(1000);
    return 0;
}
