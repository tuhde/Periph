#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x70
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
    HT16K33Minimal matrix(connection);                      // Create HT16K33 driver, (connection)

    for (uint8_t row = 0; row < HT16K33Minimal::ROWS; row++) {
        matrix.set_pixel(0, row);                         // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show();                                        // Write the shadow RAM to the chip, () → void
    delay(1000);

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
