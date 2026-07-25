#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BME280.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x76);
BME280Full bme(transport, /*spi=*/false);

int main(void) {

    stdio_init_all();
    sleep_ms(2000);

    uint8_t cid = bme.chip_id();                        // Read chip ID, () → uint8_t
                                                         // returns 0x60 for BME280
    bme.configure(1, 1, 1, 0, 0, 0);                    // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → void
                                                         // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
                                                         // humidity update requires ctrl_meas write to latch
    bme.set_mode(BME280Full::MODE_FORCED);              // Set power mode, (mode 0/1/3) → void
    bme.set_filter(BME280Full::FILTER_4);               // Set IIR filter, (coeff 0–4) → void
                                                         // suppresses short-term pressure disturbances
    bme.set_standby(BME280Full::T_SB_125_MS);           // Set standby time, (t_sb 0–7) → void
                                                         // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    uint8_t st = bme.status();                          // Read status register, () → uint8_t
    float t = bme.temperature();                        // Read temperature, () → float °C
    float p = bme.pressure();                           // Read pressure, () → float hPa
    float h = bme.humidity();                           // Read humidity, () → float %RH
    float alt = bme.altitude();                         // Compute altitude, (sea_level_hpa=1013.25) → float m
                                                         // uses barometric formula to convert pressure to metres
    float slp = bme.sea_level_pressure(alt);            // Compute sea-level pressure, (altitude_m) → float hPa
    float dp = bme.dew_point();                         // Compute dew point, () → float °C
                                                         // Magnus-Tetens approximation from current T and RH
    bme.reset();                                        // Soft reset chip, () → void
                                                         // re-reads calibration and re-applies configuration

    printf("T="); printf("%.1f", t); printf(" C, P=");
    printf("%.1f", p); printf(" hPa, RH=");
    printf("%.1f", h); printf(" %RH, alt=");
    printf("%.1f", alt); printf(" m, dp=");
    printf("%.1f", dp); printf(" C\n");
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
