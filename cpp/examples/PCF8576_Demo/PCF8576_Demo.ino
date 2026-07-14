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

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once
    // per second and the terminal mirrors the value sent to the display.
    PCF8576Full lcd(transport);                          // Create PCF8576 driver, (transport)

    for (int n = 9999; n >= 0; n--) {
        uint8_t d0 = (n / 1000) % 10;
        uint8_t d1 = (n / 100) % 10;
        uint8_t d2 = (n / 10) % 10;
        uint8_t d3 = n % 10;
        uint8_t out[4] = {
            PCF8576Full::SEVEN_SEG[d0],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d1],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d2],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
            PCF8576Full::SEVEN_SEG[d3],                  // Encode 7-segment digit, (digit 0–9) → uint8_t
        };
        lcd.write_raw(0, out, 4);                        // Write all four digits, (address 0, 4 bytes) → void
        Serial.print("countdown: ");
        if (n < 1000) Serial.print('0');
        if (n < 100) Serial.print('0');
        if (n < 10) Serial.print('0');
        Serial.println(n);
        delay(1000);
    }

    // --- Stop indicator: light only the middle segments (g) on every digit ---
    // When the counter reaches zero we replace the "0000" pattern with "----" to
    // signal that the demo has finished. Each digit's g segment is bit 1, so a
    // 0x02 byte lights just the bar across the middle.
    uint8_t dash[4] = {0x02, 0x02, 0x02, 0x02};
    lcd.write_raw(0, dash, 4);                           // Write dash pattern, (address 0, 4 bytes) → void
    Serial.println("countdown complete");

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
