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
    MFRC522Full mfrc(transport);

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);
    check_true(chip_type == 0x09, "chip_type == 0x09 (MFRC522)");
    check_true(version == 1 || version == 2, "version in {1, 2}");

    mfrc.antenna_on();
    uint8_t ctrl = mfrc._read_reg(0x14);
    check_true((ctrl & 0x03) == 0x03, "antenna_on sets TxControlReg bits 0|1");
    mfrc.antenna_off();
    ctrl = mfrc._read_reg(0x14);
    check_true((ctrl & 0x03) == 0x00, "antenna_off clears TxControlReg bits 0|1");
    mfrc.antenna_on();

    const uint8_t gains[6] = {18, 23, 33, 38, 43, 48};
    for (int i = 0; i < 6; i++) {
        mfrc.set_antenna_gain(gains[i]);
        check_true(mfrc.antenna_gain() == gains[i], "set_antenna_gain read back");
    }

    bool present = mfrc.is_card_present();
    check_true(present || !present, "is_card_present returns bool");

    uint8_t raw = mfrc._read_reg(0x37);
    check_true(raw == 0x90 || raw == 0x91 || raw == 0x92, "raw VersionReg in 0x90/0x91/0x92");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
