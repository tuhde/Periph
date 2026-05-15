#include <SPI.h>
#include <Arduino.h>
#include "../../../src/chips/comms/RFM9x.h"
#include "../../../src/transport/SPITransport.h"

#ifndef TEST_SPI_CS
#define TEST_SPI_CS 13
#endif
#ifndef TEST_RESET_PIN
#define TEST_RESET_PIN 14
#endif

int passed = 0;
int failed = 0;

void check_true(const char* label, bool cond) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { Serial.print("PASS "); Serial.println(label); passed++; }
    else { Serial.print("FAIL "); Serial.print(label); Serial.print(": got 0x"); Serial.println(got, HEX); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
    SPITransport transport(SPI, TEST_SPI_CS, settings);
    RFM95Full rfm(transport, 868000000, TEST_RESET_PIN, 0);

    uint8_t ver = rfm.version();
    check_eq("version_reg", ver, 0x12);
    check_true("version_nonzero", ver != 0);
    check_true("rssi_sane", rfm.rssi() > -150.0f && rfm.rssi() < 0.0f);

    rfm.send(reinterpret_cast<const uint8_t*>("test"), 4);
    delay(50);

    rfm.standby();
    rfm.sleep();
    rfm.standby();

    rfm.set_tx_power(14, false);
    rfm.set_tx_power(17, true);
    rfm.set_frequency(868000000);
    rfm.configure(7, 125, 5, true);
    check_true("configure_valid", true);

    Serial.print("===DONE: ");
    Serial.print(passed, DEC);
    Serial.print(" passed, ");
    Serial.print(failed, DEC);
    Serial.println(" failed===");
}

void loop() { delay(1000); }