#include <Wire.h>
#include <Periph.h>

I2CConnection accelConn(Wire, 0x19);                        // Accelerometer connection at 0x19
I2CConnection magConn(Wire, 0x1E);                          // Magnetometer connection at 0x1E

void setup() {
    Serial.begin(115200);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(21, 22, 400000);                             // SDA=GPIO21, SCL=GPIO22
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
}

void loop() {
    // The driver's constructor does I²C, so it is created here, after Wire.begin().
    static LSM303Minimal chip(accelConn, magConn);          // Create LSM303 driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    float ax, ay, az, mx, my, mz;
    chip.readAcceleration(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
    chip.readMagnetic(mx, my, mz);                          // Read magnetic field, (x, y, z) → µT, µT, µT
    Serial.print("a=");
    Serial.print(ax, 3); Serial.print(" ");
    Serial.print(ay, 3); Serial.print(" ");
    Serial.print(az, 3); Serial.print(" g  m=");
    Serial.print(mx, 1); Serial.print(" ");
    Serial.print(my, 1); Serial.print(" ");
    Serial.print(mz, 1); Serial.println(" uT");
    delay(200);
}
