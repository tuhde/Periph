#include <SPI.h>
#include "SPITransport.h"

#ifndef TEST_CS_PIN
#define TEST_CS_PIN  10
#endif
#ifndef TEST_SPI_FREQ
#define TEST_SPI_FREQ 1000000
#endif
#ifndef TEST_SPI_MODE
#define TEST_SPI_MODE SPI_MODE0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    SPI.begin();
    SPITransport transport(SPI, TEST_CS_PIN, SPISettings(TEST_SPI_FREQ, MSBFIRST, TEST_SPI_MODE));

    uint8_t cmd[1] = {0x00};
    transport.write(cmd, 1);
    check_true("write accepted", true);

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true("read returns data", true);

    transport.write_read(cmd, 1, buf, 1);
    check_true("write_read returns data", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
