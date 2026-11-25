#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "AHT20.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x38);
    AHT20Full aht(connection);

    stdio_init_all();

    printf("%d\n", aht.is_calibrated());                               // Check calibration status, () → bool
                                                                       // reads CAL bit from status byte
    printf("%d\n", aht.is_busy());                                     // Check busy status, () → bool
                                                                       // reads BUSY bit from status byte

    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
                                                                       // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    printf("%f", t);   printf(" C  ");
    printf("%f", h);   printf(" %RH\n");

    printf("%d\n", aht.temperature());                                 // Read temperature only, () → float °C
                                                                       // triggers full measurement, returns temperature_c
    printf("%d\n", aht.humidity());                                    // Read humidity only, () → float %RH
                                                                       // triggers full measurement, returns humidity_pct

    uint32_t raw_rh, raw_t;
    aht.read_raw(raw_rh, raw_t);                                       // Read raw 20-bit values, (raw_humidity, raw_temperature) → bool
                                                                       // same measurement as read(), undecoded
    printf("%u %u\n", (unsigned)raw_rh, (unsigned)raw_t);
    bool crc_ok = false;
    aht.read_with_crc(t, h, crc_ok);                                   // Read with CRC check, (temperature_c, humidity_pct, crc_ok) → bool
                                                                       // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("crc_ok=%d\n", (int)crc_ok);


    printf("0x%02X\n", aht.read_status());                              // Read status byte, () → uint8_t
                                                                       // bit 7 BUSY, bit 3 CAL
    printf("%.2f\n", (double)aht.dew_point());                          // Dew point, () → float °C
                                                                       // one fresh measurement, Magnus formula

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms, re-runs the calibration check
    return 0;
}
