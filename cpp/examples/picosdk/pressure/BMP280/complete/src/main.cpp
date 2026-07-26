#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BMP280.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x76);
    BMP280Full bmp(transport, /*spi=*/false);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    uint8_t cid = bmp.chip_id();                       // Read chip ID, () → int
                                                        // returns 0x58 for BMP280
    check_true(cid == 0x58, "chip_id");

    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1, BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
                                                        // writes ctrl_meas and config registers
    bmp.set_oversampling(BMP280Full::OSRS_X4, BMP280Full::OSRS_X2);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5) → None
                                                        // changes conversion time vs resolution trade-off
    bmp.set_mode(BMP280Full::MODE_FORCED);              // Set power mode, (mode 0/1/3) → None
    bmp.set_filter(BMP280Full::FILTER_4);               // Set IIR filter, (coeff 0–4) → None
                                                        // suppresses short-term pressure disturbances
    bmp.set_standby(BMP280Full::T_SB_125_MS);           // Set standby time, (t_sb 0–7) → None
                                                        // only relevant in normal mode
    uint8_t st = bmp.status();                         // Read status register, () → int

    float t = bmp.temperature();                        // Read temperature, () → float °C
    float p = bmp.pressure();                          // Read pressure, () → float hPa
    float alt = bmp.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
    float slp = bmp.sea_level_pressure(alt);           // Compute sea-level pressure, (altitude_m) → float hPa
    bmp.reset();                                       // Soft reset chip, () → None

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
