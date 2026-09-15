#include <SPI.h>
#include "SPIConnection.h"
#include "RFM9x.h"

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPIConnection connection(SPI, SS, settings);             // Create SPI connection, (bus, cs_pin, settings) → SPIConnection
RFM95Minimal radio(connection, 868000000);              // Create RFM95W driver, (connection, frequency_hz=868e6) → RFM95Minimal

void setup() {
    Serial.begin(115200);
    SPI.begin();
    radio.send((const uint8_t*)"hello", 5);             // Send packet, (data, len) → void
    Serial.println("sent");
}

void loop() {}
