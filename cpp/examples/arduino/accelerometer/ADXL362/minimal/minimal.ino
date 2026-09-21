#include <SPI.h>
#include "SPIConnection.h"
#include "ADXL362.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(8000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, TEST_CS_PIN, settings);                   // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
ADXL362Minimal accel(connection);                                       // Create ADXL362 driver, (connection) → ADXL362Minimal

void setup() {
    Serial.begin(115200);
    SPI.begin();
    delay(2000);
}

void loop() {
    float x, y, z;
    accel.read(x, y, z);                                                // Read 3-axis acceleration, (x, y, z) → g, g, g
    Serial.print(x, 3); Serial.print(" ");
    Serial.print(y, 3); Serial.print(" ");
    Serial.print(z, 3); Serial.println(" g");
    delay(100);
}