#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x74
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
    Is31fl3731Minimal matrix(connection);                 // Create IS31FL3731 driver, (connection)

    for (uint8_t col = 0; col < Is31fl3731Minimal::COLS; col++) {
        matrix.set_pixel(0, col, 128);                    // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    delay(1000);
    matrix.fill(32);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    delay(1000);
    matrix.clear();                                       // Switch all LEDs off, () → void

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
