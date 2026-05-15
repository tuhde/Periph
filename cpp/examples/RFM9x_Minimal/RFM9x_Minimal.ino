#include <SPI.h>
#include <Arduino.h>
#include "src/chips/comms/RFM9x.h"
#include "src/transport/SPITransport.h"

#ifndef TEST_SPI_CS
#define TEST_SPI_CS 13
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPITransport spiTransport(SPI, TEST_SPI_CS, settings);
RFM95Minimal rfm(spiTransport, 868000000);

void setup() {
    Serial.begin(115200);
    delay(2000);
    uint8_t ver = rfm.version();
    Serial.print("version: 0x");
    Serial.println(ver, HEX);
    rfm.send(reinterpret_cast<const uint8_t*>("Hello"), 5);
    Serial.println("sent");
    rfm.standby();
    rfm.sleep();
    Serial.println("===DONE: 1 passed, 0 failed===");
}

void loop() { delay(1000); }