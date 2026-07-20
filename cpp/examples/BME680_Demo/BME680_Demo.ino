#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/environmental/BME680.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);

    // --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
    // Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
    // At tick 30, the user is prompted to expose the sensor to a VOC source.
    // Gas resistance drops sharply on exposure and recovers over the remaining
    // ticks, demonstrating raw VOC sensitivity without the BSEC library.
    BME680Full bme(transport);                           // Create BME680 driver, (transport)
    bme.configure(BME680Full::OSRS_X2, BME680Full::OSRS_X16, BME680Full::OSRS_X1, BME680Full::MODE_FORCED, BME680Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=forced, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float g_min = 1e12, g_max = 0;
    int gas_count = 0;

    for (int n = 0; n < 60; n++) {
        if (n == 30) {
            Serial.println("--- Expose sensor to VOC source now (alcohol/marker) ---");
        }
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        if (t < t_min) t_min = t;
        if (t > t_max) t_max = t;
        t_sum += t;
        if (!isnan(g)) {
            if (g < g_min) g_min = g;
            if (g > g_max) g_max = g;
            gas_count++;
        }
        Serial.print(n);
        Serial.print(": ");
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(h, 1);
        Serial.print(" %RH, ");
        Serial.print(p, 1);
        Serial.print(" hPa, ");
        Serial.print(g, 0);
        Serial.println(" Ohm");
        delay(5000);
    }

    float t_avg = t_sum / 60.0f;
    Serial.print("T: ");
    Serial.print(t_min, 1);
    Serial.print("/");
    Serial.print(t_avg, 1);
    Serial.print("/");
    Serial.print(t_max, 1);
    Serial.println(" C");
    if (gas_count > 0 && g_min > 0) {
        Serial.print("VOC response ratio: ");
        Serial.print(g_max / g_min, 1);
        Serial.println("x");
    }

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
