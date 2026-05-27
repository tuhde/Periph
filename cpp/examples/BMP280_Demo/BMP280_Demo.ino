#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/pressure/BMP280.h"

static int passed = 0, failed = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);

    // --- Weather monitoring preset: lowest power, forced mode ---
    // BMP280 datasheet Table 7: ×1/×1, filter off, forced mode.
    // One sample per second for 30 seconds.
    BMP280Full bmp(transport);                           // Create BMP280 driver, (transport, spi=false)
    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1, BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        Serial.print(n);
        Serial.print("s: ");
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 1);
        Serial.print(" hPa, alt=");
        Serial.print(a, 1);
        Serial.println(" m");
        delay(1000);
    }

    // --- Indoor navigation preset: high resolution with IIR filter ---
    // ×16/×2, filter coefficient 16, normal mode at ~26 Hz.
    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X16, BMP280Full::MODE_NORMAL, BMP280Full::FILTER_16, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        Serial.print(n);
        Serial.print("s: alt=");
        Serial.print(a, 4);
        Serial.println(" m");
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
