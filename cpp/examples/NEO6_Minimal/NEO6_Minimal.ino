// To use I2C (DDC) instead of UART:
//   #include "../../src/transport/I2CTransport.h"
//   I2CTransport transport(Wire, 0x42);
//   NEO6Minimal gps(transport, NEO6BusType::I2c);
// To use SPI instead of UART:
//   #include "../../src/transport/SPITransport.h"
//   SPITransport transport(SPI, 5, SPISettings(200000, MSBFIRST, SPI_MODE0));
//   NEO6Minimal gps(transport, NEO6BusType::Spi);

#include <Arduino.h>
#include "../../src/transport/UARTTransport.h"
#include "../../src/chips/gnss/NEO6.h"

UARTTransport transport(Serial1);
NEO6Minimal gps(transport);                              // Create NEO-6 driver, (transport, bus_type=Uart)

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
