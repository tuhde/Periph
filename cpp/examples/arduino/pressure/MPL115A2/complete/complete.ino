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

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

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
    baro.reload_coefficients();                           // Re-read coefficient block, () → void
                                                              // use after a power cycle
    float pa, tc;
    baro.read(pa, tc);                                    // One conversion, (pressure_pa&, temperature_c&) → void
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);                            // One conversion, (padc&, tadc&) → void, 10-bit counts
    float p = baro.compensate(padc, tadc);                // Compensate raw counts, (padc, tadc) → float Pa
                                                              // pure function, no bus access
    float hpa = baro.pressure_hpa();                      // Read pressure, () → float hPa
    float alt = baro.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
                                                              // barometric formula, informative
    Serial.print("a0=");
    Serial.print(a0, 3);
    Serial.print(" b1=");
    Serial.print(b1, 5);
    Serial.print(" b2=");
    Serial.print(b2, 5);
    Serial.print(" c12=");
    Serial.println(c12, 8);
    Serial.print(pa, 0);
    Serial.print(" Pa, ");
    Serial.print(tc, 1);
    Serial.print(" C, raw=(");
    Serial.print((int)padc);
    Serial.print(", ");
    Serial.print((int)tadc);
    Serial.print("), ");
    Serial.print(p, 0);
    Serial.print(" Pa, ");
    Serial.print(hpa, 2);
    Serial.print(" hPa, alt=");
    Serial.print(alt, 1);
    Serial.println(" m");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
