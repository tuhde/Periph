#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x50, nullptr, nullptr, 2);
AT24C64Minimal ee(connection);                                        // Create AT24C64 driver, (connection) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint8_t v = 0;
    uint8_t counter = 0;
    ee.write_byte(0x0000, counter);                                     // Write a single byte, (address 0-8191, value 0-255) → bool
    v = ee.read_byte(0x0000);                                           // Read a single byte, (address 0-8191) → uint8_t
    Serial.print("byte 0x"); Serial.print((unsigned)v, HEX); Serial.println();
    counter++;
    delay(1000);
}
