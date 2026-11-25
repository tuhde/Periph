#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Ms5607.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    MS5607Full sensor(connection);                                  // Create MS5607 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    sensor.read_prom(prom);                                         // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
    printf("C1 %lu C2 %lu C3 %lu C4 %lu C5 %lu C6 %lu\n", (unsigned long)(k[0]), (unsigned long)(k[1]), (unsigned long)(k[2]), (unsigned long)(k[3]), (unsigned long)(k[4]), (unsigned long)(k[5]));
    printf("crc ok %lu serial %lu\n", (unsigned long)(sensor.prom_crc_ok()), (unsigned long)(sensor.serial_code())); // Verify the PROM CRC-4, () → bool

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    // Altitude relative to the first reading; the climb rate is the change per second.
    float reference = sensor.read_pressure();                       // Read pressure only, () → float Pa
    float previous = 0, lowest = 100;
    for (int i = 0; i < 20; i++) {
        sensor.read(t, p);                                          // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        if (t < lowest) lowest = t;
        float height = sensor.altitude(reference);                  // Altitude from pressure, (sea_level_pa) → float m
        printf("%.2f C %.1f Pa %.2f m %.2f m/s\n", (double)(t), (double)(p), (double)(height), (double)(i == 0 ? 0.0f : height - previous));
        previous = height;
        sleep_ms(1000);
    }

    // --- Noise and time versus oversampling: ten readings each ---
    // Higher ratios take longer per reading and spread less.
    static const uint16_t osrs[5] = {256, 512, 1024, 2048, 4096};
    for (int o = 0; o < 5; o++) {
        sensor.set_oversampling(osrs[o]);                           // Select oversampling ratio, (osr) → bool
        float lo = 1e9f, hi = -1e9f;
        for (int n = 0; n < 10; n++) {
            float v = sensor.read_pressure();                       // Read pressure only, () → float Pa
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        printf("OSR %lu spread %.1f Pa\n", (unsigned long)(osrs[o]), (double)(hi - lo));
    }
    sensor.set_oversampling(4096);

    // --- Raw codes with and without the second-order correction ---
    sensor.read_raw(d1, d2);                                        // Read raw ADC codes, (d1&, d2&) → bool
    printf("raw D1 %lu D2 %lu\n", (unsigned long)(d1), (unsigned long)(d2));
    sensor.compensate(d1, d2, t, p, false);                         // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order) → void
    printf("first order  %.2f C %.1f Pa\n", (double)(t), (double)(p));
    sensor.compensate(d1, d2, t, p);
    printf("second order %.2f C %.1f Pa\n", (double)(t), (double)(p));
    printf("lowest temperature seen %.2f C\n", (double)(lowest));

    // --- Non-blocking conversion, then reset ---
    sensor.start_conversion(MS5607Full::PRESSURE);                  // Start a conversion, (kind, osr=0) → bool
    sleep_ms(12);
    sensor.read_adc(adc);                                           // Read the ADC result, (value&) → bool
    printf("D1 %lu\n", (unsigned long)(adc));
    sensor.reset();                                                 // Reset and reload PROM, () → bool
    return 0;
}
