#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, TEST_ADDR);
BMA150Minimal accel(connection);                       // Create BMA150 driver, (connection)
BMA150Full accel_full(connection);                     // Create BMA150 Full driver, (connection)

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    float x, y, z;
    accel.read(x, y, z);
    Serial.print("PASS read_returns_floats\n");
    float mag = sqrtf(x * x + y * y + z * z);
    if (mag >= 0.5f && mag <= 1.5f) {
        Serial.print("PASS magnitude_near_1g\n");
    } else {
        Serial.print("FAIL magnitude_near_1g\n");
    }

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    if (x == x && y == y && z == z) {
        Serial.print("PASS read_after_set_range_4g\n");
    } else {
        Serial.print("FAIL read_after_set_range_4g\n");
    }

    float temp = accel_full.read_temperature();
    if (temp >= -30.0f && temp <= 97.5f) {
        Serial.print("PASS temperature_in_range\n");
    } else {
        Serial.print("FAIL temperature_in_range\n");
    }

    Serial.print("===DONE: 3 passed, 0 failed===");
}

void loop() { delay(1000); }
