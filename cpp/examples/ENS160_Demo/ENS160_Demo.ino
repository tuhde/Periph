#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/gas/ENS160.h"

static int passed = 0, failed = 0;

const char* aqi_label(uint8_t aqi) {
    switch (aqi) {
        case 1: return "Excellent";
        case 2: return "Good";
        case 3: return "Moderate";
        case 4: return "Poor";
        case 5: return "Unhealthy";
        default: return "Unknown";
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x52);
    ENS160Full sensor(transport);                        // Create ENS160 driver, (transport)

    // --- Wait for sensor warm-up ---
    // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
    // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
    // status so the application can display progress to the user.
    Serial.println("Waiting for sensor warm-up...");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            uint8_t s = sensor.status();
            if (s == 1) Serial.println("Warm-up in progress...");
            else if (s == 2) Serial.println("Initial start-up (first power-on, up to 1 hour)...");
            else Serial.println("No valid output");
            delay(1000);
        }
    }
    Serial.println("Sensor ready!");

    // --- Set compensation from external sensor ---
    // If an external temperature/humidity sensor is available, feeding its readings
    // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
    // fixed 22C/45%RH as an example.
    sensor.set_compensation(22.0f, 45.0f);               // Set compensation, (temp_celsius=22.0, rh_percent=45.0) → void

    // --- Indoor air quality monitoring loop ---
    // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
    // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
    for (int n = 0; n < 60; n++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, () → bool
        if (ok) {
            Serial.print(n);
            Serial.print("s: AQI=");
            Serial.print(aqi);
            Serial.print(" (");
            Serial.print(aqi_label(aqi));
            Serial.print(") TVOC=");
            Serial.print(tvoc_ppb, 0);
            Serial.print(" ppb eCO2=");
            Serial.print(eco2_ppm, 0);
            Serial.println(" ppm");
        }
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
