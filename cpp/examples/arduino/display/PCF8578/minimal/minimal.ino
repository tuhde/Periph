#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x3C
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 100000);
#else
    Wire.begin();                                    // other cores: board's default SDA/SCL
    Wire.setClock(100000);
#endif
    I2CConnection connection(Wire, TEST_ADDR);
    PCF8578Minimal lcd(connection, 16);                       // Create PCF8578 driver, (connection, rows=16 8/16/24/32)

    lcd.clear();                                              // Clear display RAM, () → void
    lcd.set_pixel(0, 0);                                      // Set one pixel, (x 0–23, y 0–15, on=true) → void
    lcd.set_pixel(23, 15);                                    // Set opposite corner, (x 0–23, y 0–15, on=true) → void
    static const uint8_t frame[] = {0xFF, 0x81, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–23, bank 0–1, data, len) → bool

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
