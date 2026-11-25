#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

#ifndef TEST_SDA
#define TEST_SDA 21
#endif
#ifndef TEST_SCL
#define TEST_SCL 22
#endif

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection accel_connection(Wire, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnection mag_connection(Wire, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Minimal accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    while (true) {
        float ax, ay, az;
        float mx, my, mz;
        accel.readAcceleration(ax, ay, az);      // Read acceleration, (x, y, z) → g, g, g
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        Serial.print("accel x="); Serial.print(ax, 4);
        Serial.print(" y="); Serial.print(ay, 4);
        Serial.print(" z="); Serial.println(az, 4);
        Serial.print("mag   x="); Serial.print(mx, 2);
        Serial.print(" y="); Serial.print(my, 2);
        Serial.print(" z="); Serial.println(mz, 2);
        delay(1000);
    }
}

void loop() {}
