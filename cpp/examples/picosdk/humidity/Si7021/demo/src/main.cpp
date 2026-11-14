#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si7021.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.software_reset();                                            // Software reset, () → void
    printf("device type 0x%02X\n", (unsigned)sensor.device_type());
    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printf("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printf("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.device_type() / sensor.firmware_revision() → uint8_t
    printf("VDD low %u\n", (unsigned)sensor.vdd_low());

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, sensor.dew_point());
        // sensor.dew_point() → float °C
        sleep_ms(2000);
    }

    // --- Value accessors and raw codes ---
    printf("RH %.1f T %.2f T of last RH %.2f\n", sensor.read_humidity(), sensor.read_temperature(), sensor.read_temperature_previous());
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
    printf("raw RH %u\n", (unsigned)raw);
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    static const uint8_t PAIRS[4][2] = {{12, 14}, {10, 13}, {11, 11}, {8, 12}};
    for (uint8_t i = 0; i < 4; i++) {
        sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]);                // Set resolution, (rh_bits, temperature_bits) → bool
        uint32_t t0 = to_ms_since_boot(get_absolute_time());
        sensor.read(t, rh);
        uint32_t dt = to_ms_since_boot(get_absolute_time()) - t0;
        sensor.get_resolution(rh_bits, t_bits);
        printf("%u/%u bit: %.2f C %.1f %%RH, %lu ms (typ. %.1f ms)\n", (unsigned)rh_bits, (unsigned)t_bits, t, rh, (unsigned long)dt, sensor.conversion_time() * 1000.0f);
    }
    sensor.set_resolution(12, 14);

    // --- Heater test: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    sleep_ms(10000);
    printf("heater on %u\n", (unsigned)sensor.is_heater_on());
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);
    while (true) sleep_ms(1000);
    return 0;
}
