#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, TEST_ADDR);
    PCF8576Minimal lcd(transport);                       // Create PCF8576 driver, (transport)

    static const uint8_t digits[] = {1, 2, 3, 4};
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t seg = PCF8576Minimal::SEVEN_SEG[digits[i]];  // Encode 7-segment digit, (digit 0–9) → uint8_t
        lcd.set_digit_7seg(i, seg);                      // Write one digit, (position 0–19, segments 0–255) → void
    }

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
