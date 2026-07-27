// To use I2C (DDC) instead of UART:
//   #include "../../src/connection/I2CConnection.h"
//   I2CConnection connection(Wire, 0x42);
//   NEO6Minimal gps(connection, NEO6BusType::I2c);
// To use SPI instead of UART:
//   #include "../../src/connection/SPIConnection.h"
//   SPIConnection connection(SPI, 5, SPISettings(200000, MSBFIRST, SPI_MODE0));
//   NEO6Minimal gps(connection, NEO6BusType::Spi);

#include <Arduino.h>
#include "../../src/connection/UARTConnection.h"
#include "../../src/chips/gnss/NEO6.h"

UARTConnection connection(Serial1);
NEO6Minimal gps(connection);                              // Create NEO-6 driver, (connection, bus_type=Uart)

void setup() {
    Serial.begin(115200);
    Serial1.begin(9600);
}

void loop() {
    if (gps.update()) {                                  // Read + parse one NMEA sentence, () → bool
        Serial.print(gps.latitude(), 6);
        Serial.print(", ");
        Serial.print(gps.longitude(), 6);
        Serial.print(", ");
        Serial.println(gps.altitude(), 1);
    }
    delay(50);
}
