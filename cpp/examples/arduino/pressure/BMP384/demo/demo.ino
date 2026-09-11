#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/BMP384.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x76);
    BMP384Full bmp(connection);                             // Create BMP384 driver, (connection, spi=false)

    // --- Configure for noise-sensitive altitude logging ---
    // osr_p=×16 gives ~12 cm noise-equivalent altitude resolution; the IIR
    // coefficient 3 suppresses door-slam / gust spikes without too much step lag.
    // ODR=25 Hz gives us a sample every 40 ms, well above the ~38 ms T_conv.
    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None

    // --- Sample for 30 seconds, logging altitude every 500 ms ---
    // P0 = 1013.25 hPa (ISA sea-level reference). 30 s × 2 Hz = 60 rows.
    const float SEA_LEVEL_HPA = 1013.25f;
    const unsigned long START_MS = millis();
    const unsigned long DURATION_MS = 30000UL;
    const unsigned long PERIOD_MS = 500UL;
    unsigned long next_ms = START_MS;
    unsigned int rows = 0;
    while (millis() - START_MS < DURATION_MS) {
        if ((long)(millis() - next_ms) >= 0) {
            float t = bmp.temperature();                    // Read temperature, () → float °C
            float p = bmp.pressure();                       // Read pressure, () → float hPa
            float altitude = 44330.0f * (1.0f - powf(p / SEA_LEVEL_HPA, 1.0f / 5.255f));
            float elapsed = (millis() - START_MS) / 1000.0f;
            Serial.print(elapsed, 1);
            Serial.print("s  "); Serial.print(p, 2);
            Serial.print(" hPa  "); Serial.print(t, 1);
            Serial.print(" C  "); Serial.print(altitude, 1);
            Serial.println(" m");
            rows++;
            next_ms += PERIOD_MS;
        }
    }

    Serial.print("Sampled "); Serial.print(rows);
    Serial.println(" rows over 30 s");
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
