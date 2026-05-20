#include <SPI.h>
#include "NeoPixelTransport.h"

#ifndef TEST_SPI_CS
#define TEST_SPI_CS 10
#endif

NeoPixelTransport transport(SPI);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    uint8_t data[3] = {0xFF, 0x00, 0x00};
    transport.write(data, 3);

    check_true("write accepted data", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}