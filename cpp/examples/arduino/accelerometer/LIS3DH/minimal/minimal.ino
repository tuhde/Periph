#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

I2CConnection connection(Wire, 0x18);
LIS3DHMinimal accel(connection);                     // Create LIS3DH driver, (connection)

static void lis3dh_sleep_ms(uint32_t ms) { delay(ms); }

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    for (int i = 0; i < 10; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        Serial.print("x="); Serial.print(x, 3);
        Serial.print(" y="); Serial.print(y, 3);
        Serial.print(" z="); Serial.print(z, 3); Serial.println(" g");
        lis3dh_sleep_ms(100);
    }
}

void loop() { delay(1000); }
