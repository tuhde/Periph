#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

static void sleepMs(uint32_t ms) { delay(ms); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    Serial.print(label);
    Serial.print(" x="); Serial.print(x, 3);
    Serial.print(" y="); Serial.print(y, 3);
    Serial.print(" z="); Serial.print(z, 3);
    Serial.print(" "); Serial.println(unit);
}

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, 0x1E);
    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.readAccel(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
        compass.readMag(mx, my, mz);                        // Read magnetic field, (x, y, z) → µT, µT, µT
        printVec("accel", ax, ay, az, "g");
        printVec("mag", mx, my, mz, "uT");
        sleepMs(500);
    }
}

void loop() { delay(1000); }
