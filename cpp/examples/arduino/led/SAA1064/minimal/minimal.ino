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
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, TEST_ADDR);
    SAA1064Minimal display(connection);                             // Create SAA1064 driver, (connection)
    uint16_t n = 0;
    for (int i = 0; i < 100; i++) {
        display.show_number(n);                                     // Show a right-aligned number, (value 0–9999) → bool
        n++;
        delay(100);
    }

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
