#ifndef TEST_SCK
#define TEST_SCK 13
#endif
#ifndef TEST_MISO
#define TEST_MISO 12
#endif
#ifndef TEST_MOSI
#define TEST_MOSI 11
#endif
#ifndef TEST_CS
#define TEST_CS 10
#endif

#include <Arduino.h>
#include <SPI.h>
#include "../../src/transport/SPITransport.h"
#include "../../src/chips/rfid/MFRC522.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin(TEST_SCK, TEST_MISO, TEST_MOSI, TEST_CS);
    SPITransport transport(SPI, TEST_CS, SPISettings(1000000, MSBFIRST, SPI_MODE0));
    MFRC522Minimal mfrc(transport);                                 // Create MFRC522 driver, (transport, bus_type=BUS_SPI)

    for (int i = 0; i < 10; i++) {
        bool present = mfrc.is_card_present();                     // Detect card in field, () → bool
        uint8_t uid[10];
        size_t  uid_len = 0;
        bool ok = mfrc.read_uid(uid, uid_len);                     // Read card UID (REQA → anticollision → HLTA), (out, len) → bool
        Serial.print("present=");
        Serial.print(present);
        Serial.print(" uid=");
        for (size_t j = 0; j < uid_len; j++) {
            if (uid[j] < 0x10) Serial.print("0");
            Serial.print(uid[j], HEX);
        }
        Serial.println();
        delay(500);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
