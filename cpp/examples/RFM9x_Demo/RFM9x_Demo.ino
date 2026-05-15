#include <SPI.h>
#include <Arduino.h>
#include "src/chips/comms/RFM9x.h"
#include "src/transport/SPITransport.h"

#ifndef TEST_SPI_CS
#define TEST_SPI_CS 13
#endif
#ifndef TEST_RESET_PIN
#define TEST_RESET_PIN 14
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPITransport spiTransport(SPI, TEST_SPI_CS, settings);
RFM95Full rfm(spiTransport, 868000000, TEST_RESET_PIN, 0);

void setup() {
    Serial.begin(115200);
    delay(2000);
    rfm.configure(7, 125, 5, true);
    rfm.set_tx_power(17, true);
    uint16_t counter = 0;
    uint8_t successes = 0;
    uint8_t failures = 0;
    for (uint8_t i = 0; i < 10; i++) {
        uint8_t tx_payload[2] = { static_cast<uint8_t>((counter >> 8) & 0xFF), static_cast<uint8_t>(counter & 0xFF) };
        rfm.send(tx_payload, 2);
        uint8_t len = 0;
        uint8_t* rx = rfm.receive(2000, &len);
        if (rx && len > 0) {
            Serial.print("seq=");
            Serial.print(counter);
            Serial.print(" rssi=");
            Serial.print(rfm.last_packet_rssi());
            Serial.print(" snr=");
            Serial.println(rfm.last_packet_snr());
            successes++;
        } else {
            Serial.print("seq=");
            Serial.print(counter);
            Serial.println(" timeout");
            failures++;
        }
        counter++;
        delay(100);
    }
    Serial.print("=== ");
    Serial.print(successes, DEC);
    Serial.print(" success, ");
    Serial.print(failures, DEC);
    Serial.println(" lost ===");
}

void loop() { delay(1000); }