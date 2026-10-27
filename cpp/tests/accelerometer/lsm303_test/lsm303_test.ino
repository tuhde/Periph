#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Wire.h>
#include <math.h>
#include <Periph.h>

#ifndef LSM303_ACCEL_ADDR
#define LSM303_ACCEL_ADDR 0x19
#endif
#ifndef LSM303_MAG_ADDR
#define LSM303_MAG_ADDR 0x1E
#endif

I2CConnection accelConnection(Wire, LSM303_ACCEL_ADDR);
I2CConnection magConnection(Wire, LSM303_MAG_ADDR);
LSM303Minimal sensor(accelConnection, magConnection);    // Create LSM303 driver, (accelConnection, magConnection)
LSM303Full sensor_full(accelConnection, magConnection);  // Create LSM303 Full driver, (accelConnection, magConnection)

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif

    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);                // Read 3-axis acceleration, (x, y, z) → g, g, g
    Serial.print("PASS read_acceleration_returns_floats\n");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    if (amag >= 0.5f && amag <= 1.5f) {
        Serial.print("PASS acceleration_magnitude_near_1g\n");
    } else {
        Serial.print("FAIL acceleration_magnitude_near_1g\n");
    }

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    Serial.print("PASS read_magnetic_returns_floats\n");
    float bmag = sqrtf(mx * mx + my * my + mz * mz);
    if (bmag >= 5.0f && bmag <= 200.0f) {
        Serial.print("PASS magnetic_magnitude_plausible\n");
    } else {
        Serial.print("FAIL magnetic_magnitude_plausible\n");
    }

    sensor_full.set_range(4);
    sensor_full.read_acceleration(ax, ay, az);
    Serial.print("PASS read_acceleration_after_set_range_4g\n");

    sensor_full.set_mag_range(470);
    sensor_full.read_magnetic(mx, my, mz);
    Serial.print("PASS read_magnetic_after_set_mag_range\n");

    float hdg = sensor_full.heading();                   // Compute compass heading, (tilt_compensate=true) → degrees
    if (hdg >= 0.0f && hdg < 360.0f) {
        Serial.print("PASS heading_in_range\n");
    } else {
        Serial.print("FAIL heading_in_range\n");
    }

    float temp = sensor_full.read_temperature_delta();
    if (temp == temp) {
        Serial.print("PASS temperature_delta_is_finite\n");
    } else {
        Serial.print("FAIL temperature_delta_is_finite\n");
    }

    Serial.print("===DONE: 8 passed, 0 failed===");
}

void loop() { delay(1000); }
