#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static int passed = 0, failed = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x60);
    MPL115A2Full baro(connection);                      // Create MPL115A2 driver, (connection)

    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);                   // Read cached coefficients, (a0&, b1&, b2&, c12&) → void
    Serial.print("a0=");
    Serial.print(a0, 3);
    Serial.print(" b1=");
    Serial.print(b1, 5);
    Serial.print(" b2=");
    Serial.print(b2, 5);
    Serial.print(" c12=");
    Serial.println(c12, 8);

    // --- Barometer log ---
    // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
    // altitude and the raw counts, then the min/max of the run.
    float pmin = 1e9f, pmax = 0.0f;
    for (int n = 0; n < 15; n++) {
        float pa = baro.read_pressure();                  // Read pressure, () → float Pa
        float hpa = baro.pressure_hpa();                  // Read pressure, () → float hPa
        float alt = baro.altitude();                      // Compute altitude, (sea_level_pa=101325.0) → float m
        uint16_t padc, tadc;
        baro.read_raw(padc, tadc);                        // One conversion, (padc&, tadc&) → void
        if (pa < pmin) pmin = pa;
        if (pa > pmax) pmax = pa;
        Serial.print(n * 2);
        Serial.print("s: ");
        Serial.print(pa, 0);
        Serial.print(" Pa, ");
        Serial.print(hpa, 2);
        Serial.print(" hPa, alt=");
        Serial.print(alt, 1);
        Serial.print(" m, raw=(");
        Serial.print((int)padc);
        Serial.print(", ");
        Serial.print((int)tadc);
        Serial.println(")");
        delay(2000);
    }
    Serial.print("Summary: min=");
    Serial.print(pmin, 0);
    Serial.print(" Pa, max=");
    Serial.print(pmax, 0);
    Serial.println(" Pa");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
