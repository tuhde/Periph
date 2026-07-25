#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/environmental/BME280.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);
    BME280Full bme(transport);                          // Create BME280 driver, (transport, spi=false)
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

    Serial.print("T="); Serial.print(t, 1); Serial.print(" C, P=");
    Serial.print(p, 1); Serial.print(" hPa, RH=");
    Serial.print(h, 1); Serial.print(" %RH, alt=");
    Serial.print(alt, 1); Serial.print(" m, dp=");
    Serial.print(dp, 1); Serial.println(" C");
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
