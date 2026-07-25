#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "AHT21.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x38);
AHT21Full aht(transport);

int main(void) {

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

    float tc, hc;
    bool crc_ok = aht.read_with_crc(tc, hc);                           // Read with CRC verification, (temperature_c, humidity_pct) → bool
                                                                       // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("%f", tc);  printf(" C  ");
    printf("%f", hc);  printf(" %RH  CRC: ");
    printf("%d\n", crc_ok);

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                       // sends 0xBA, waits 20 ms for recovery
    return 0;
}
