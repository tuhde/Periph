#include <SPI.h>
#include "SPIConnection.h"
#include "RFM9x.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN SS
#endif
#ifndef TEST_FREQ_HZ
#define TEST_FREQ_HZ 868000000UL
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE0);
SPIConnection transport(SPI, TEST_CS_PIN, settings);
RFM95Full    radio(transport, TEST_FREQ_HZ);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got 0x"); Serial.print(got, HEX);
        Serial.print(", expected 0x"); Serial.println(expected, HEX);
        failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    check_eq("version", radio.version(), 0x12);

    radio.configure(7, 125.0, 5);
    radio.set_tx_power(17, true);
    radio.set_frequency(TEST_FREQ_HZ);
    check_true("configure", true);

    radio.standby();
    check_eq("standby_mode", radio._read_reg(0x01) & 0x07, 0x01);

    radio.send((const uint8_t*)"test", 4);
    check_eq("irq_tx_done_cleared", radio._read_reg(0x12) & 0x08, 0x00);

    radio.sleep();
    check_eq("sleep_mode", radio._read_reg(0x01) & 0x07, 0x00);

    radio.standby();
    check_true("wake", true);

    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
