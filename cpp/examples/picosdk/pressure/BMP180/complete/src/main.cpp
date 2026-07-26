#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BMP180.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x77);
    BMP180Full bmp(transport, /*oss=*/3);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    uint8_t cid = bmp.chip_id();                     // Read chip ID, () → int
                                                      // returns 0x55 for BMP180
    check_true(cid == 0x55, "chip_id");

    uint8_t oss = bmp.oversampling();                // Read OSS, () → int 0–3
    check_true(oss == 0, "default_oss");

    bmp.set_oversampling(BMP180Full.OSS_STANDARD);    // Set OSS, (oss 0–3) → None
    check_true(bmp.oversampling() == 1, "set_oss");

    float t = bmp.temperature();                      // Read temperature, () → float C
    float p = bmp.pressure();                        // Read pressure, () → float hPa
    float alt = bmp.altitude();                     // Compute altitude, (sea_level_hpa=1013.25) → float m
    float slp = bmp.sea_level_pressure(alt);         // Compute sea-level pressure, (altitude_m) → float hPa
    bmp.reset();                                     // Soft reset chip, () → None

    printf("T=");
    printf("%.1f", t);
    printf(" C, P=");
    printf("%.1f", p);
    printf(" hPa, alt=");
    printf("%.1f", alt);
    printf(" m, slp=");
    printf("%.1f", slp);
    printf(" hPa\n");

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
