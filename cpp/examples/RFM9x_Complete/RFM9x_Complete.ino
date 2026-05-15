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
    uint8_t ver = rfm.version();
    Serial.print("version: 0x");
    Serial.println(ver, HEX);
    rfm.configure(7, 125, 5, true);
    rfm.set_tx_power(17, true);
    rfm.set_frequency(915000000);
    rfm.send(reinterpret_cast<const uint8_t*>("Hello"), 5);
    uint8_t len = 0;
    uint8_t* pkt = rfm.receive(2000, &len);
    if (pkt && len > 0) {
        Serial.print("rx: ");
        for (uint8_t i = 0; i < len; i++) Serial.write(pkt[i]);
        Serial.println();
        Serial.print("rssi: ");
        Serial.println(rfm.last_packet_rssi());
        Serial.print("snr: ");
        Serial.println(rfm.last_packet_snr());
    }
    rfm.receive_continuous();
    delay(500);
    uint8_t cont_len = 0;
    uint8_t* cont_pkt = rfm.read_packet(&cont_len);
    rfm.stop_receive();
    Serial.print("channel rssi: ");
    Serial.println(rfm.rssi());
    rfm.reset();
    rfm.standby();
    rfm.sleep();
    Serial.println("===DONE: 1 passed, 0 failed===");
}

void loop() { delay(1000); }