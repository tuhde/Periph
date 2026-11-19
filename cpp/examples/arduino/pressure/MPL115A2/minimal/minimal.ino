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
    MPL115A2Minimal baro(connection);                   // Create MPL115A2 driver, (connection)

    for (int i = 0; i < 5; i++) {
        float p = baro.read_pressure();                   // Read pressure, () → float Pa
        float t = baro.read_temperature();                // Read temperature, () → float °C (approximate)
        Serial.print(p, 0);
        Serial.print(" Pa, ");
        Serial.print(t, 1);
        Serial.println(" C");
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
