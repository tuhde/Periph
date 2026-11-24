#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x76);

    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    BME688Full bme(connection);                           // Create BME688 driver, (connection)
    bme.configure(BME688Full::OSRS_X2, BME688Full::OSRS_X16, BME688Full::OSRS_X1, BME688Full::MODE_SLEEP, BME688Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    Serial.println("--- forced mode ---");
    for (int n = 0; n < 10; n++) {
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        float gm = log((h > 0.01f ? h : 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * gm / (17.62f - gm);       // Magnus dew point, °C
        Serial.print(n); Serial.print(": ");
        Serial.print(t, 1); Serial.print(" C, ");
        Serial.print(h, 1); Serial.print(" %RH, ");
        Serial.print(p, 1); Serial.print(" hPa, ");
        Serial.print(g, 0); Serial.print(" Ohm, dew ");
        Serial.print(dew, 1); Serial.println(" C");
        delay(5000);
    }

    Serial.println("--- parallel mode ---");
    const int16_t temps[3] = {320, 250, 180};
    const uint8_t repeats[3] = {1, 2, 1};
    bme.start_parallel(temps, repeats, 3, 100.0f);      // Start parallel mode, (temps, repeats, steps=3, shared_wait_ms=100) → void
    for (int n = 0; n < 60; n++) {
        BME688Field f[3];
        uint8_t cnt = bme.read_parallel(f, 3);          // Read the new data fields, (out, max) → uint8_t count
        for (uint8_t i = 0; i < cnt; i++) {
            Serial.print("step "); Serial.print(f[i].step);
            Serial.print(" #"); Serial.print(f[i].sub_index);
            Serial.print(": "); Serial.print(f[i].temperature, 1);
            Serial.print(" C, "); Serial.print(f[i].humidity, 1);
            Serial.print(" %RH, "); Serial.print(f[i].gas_resistance, 0);
            Serial.print(" Ohm (valid="); Serial.print(f[i].gas_valid);
            Serial.print(", stable="); Serial.print(f[i].heater_stable);
            Serial.println(")");
        }
        delay(1000);
    }
    bme.stop_parallel();                                // Back to sleep, () → void

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
