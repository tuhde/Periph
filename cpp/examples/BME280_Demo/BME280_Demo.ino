#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/environmental/BME280.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);

    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    BME280Full bme(transport);                          // Create BME280 driver, (transport, spi=false)
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_FORCED, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float h_min = 999, h_max = -999, h_sum = 0;
    float p_min = 1e9, p_max = -1e9, p_sum = 0;
    int n_samples = 10;

    for (int n = 0; n < n_samples; n++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float a = bme.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        Serial.print(n);
        Serial.print(": ");
        Serial.print(t, 1); Serial.print(" C, ");
        Serial.print(h, 1); Serial.print(" %RH, ");
        Serial.print(p, 1); Serial.print(" hPa, dew=");
        Serial.print(d, 1); Serial.print(" C, alt=");
        Serial.print(a, 1); Serial.println(" m");
        delay(1000);
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    Serial.println("--- Breathe gently on the sensor for 3 seconds ---");
    delay(3000);
    {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        Serial.print("after breath: ");
        Serial.print(t, 1); Serial.print(" C, ");
        Serial.print(h, 1); Serial.print(" %RH, ");
        Serial.print(p, 1); Serial.print(" hPa, dew=");
        Serial.print(d, 1); Serial.println(" C");
        n_samples++;
    }

    Serial.print("T: "); Serial.print(t_min, 1); Serial.print("/");
    Serial.print(t_sum / n_samples, 1); Serial.print("/");
    Serial.print(t_max, 1); Serial.println(" C");
    Serial.print("RH: "); Serial.print(h_min, 1); Serial.print("/");
    Serial.print(h_sum / n_samples, 1); Serial.print("/");
    Serial.print(h_max, 1); Serial.println(" %");
    Serial.print("P: "); Serial.print(p_min, 1); Serial.print("/");
    Serial.print(p_sum / n_samples, 1); Serial.print("/");
    Serial.print(p_max, 1); Serial.println(" hPa");

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
