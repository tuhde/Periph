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
    MFRC522Full mfrc(transport);                                    // Create MFRC522 driver, (transport, bus_type=BUS_SPI)

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);                              // Read version register, (chip_type, version) → void
                                                                    // for MFRC522 chip_type=0x09, version=1 (v1.0) or 2 (v2.0)
    Serial.print("MFRC522 chip_type=0x");
    Serial.print(chip_type, HEX);
    Serial.print(" version=");
    Serial.println(version);

    bool ok = mfrc.self_test();                                    // Run digital self test, () → bool
                                                                    // compares 64 FIFO bytes against the version-specific reference
    check_true(ok, "self_test");

    mfrc.antenna_on();                                              // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                      // Set receiver gain, (dB=18/23/33/38/43/48) → void
                                                                    // 38 dB gives better read range on most antennas
    Serial.print("current gain: ");
    Serial.print(mfrc.antenna_gain());                              // Read receiver gain, () → int dB
    Serial.println(" dB");

    mfrc.reset();                                                   // Soft reset and reinitialise, () → void
                                                                    // re-runs the full initialization sequence

    uint8_t uid[10];
    size_t  uid_len = 0;
    if (mfrc.select_card(uid, uid_len)) {                           // Anticollision/Select (leaves card active), (out, len) → bool
        Serial.print("UID: ");
        for (size_t i = 0; i < uid_len; i++) {
            if (uid[i] < 0x10) Serial.print("0");
            Serial.print(uid[i], HEX);
        }
        Serial.println();
        // Authenticate MIFARE Classic sector 1 block 4 with factory default key A
        uint8_t factory_key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};    // well-known default key — see spec
        if (mfrc.authenticate(4, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            uint8_t block[16];
            if (mfrc.read_block(4, block)) {                        // Read 16-byte block, (block_address, out=16 B) → bool
                                                                    // requires successful authenticate for the containing sector
                Serial.print("block 4: ");
                for (int i = 0; i < 16; i++) {
                    if (block[i] < 0x10) Serial.print("0");
                    Serial.print(block[i], HEX);
                }
                Serial.println();
            }
            mfrc.decrement_value(4, 1);                             // Decrement value block, (block, delta=uint32) → bool
                                                                    // runs Decrement + Transfer to the same block
            mfrc.stop_crypto();                                     // Clear MFCrypto1On, () → void
                                                                    // required before authenticating a different sector
        }
        mfrc.halt_card();                                           // Send HLTA, () → void
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
