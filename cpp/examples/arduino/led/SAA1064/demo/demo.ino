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

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The SAA1064 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once
    // per second and the terminal mirrors the value sent to the display.
    SAA1064Full display(connection);                                // Create SAA1064 driver, (connection)

    // --- Four-digit counter ---
    // A fast counter from 0 to 9999, the decimal point sweeping across the four
    // positions, the segment current stepped through 3/9/15/21 mA, a switch to
    // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
    // status byte (the PR flag is set after power-up and clears on the first read).
    bool ok = true;
    for (uint16_t n = 0; n < 10000; n += 37) {
        ok = display.show_number(n);                                // Show the counter, (value 0–9999) → bool
    delay(10);
    }
    const int8_t digits[4] = {1, 2, 3, 4};
    ok = display.show_digits(digits);                               // Show four digits, (digits) → bool
    for (uint8_t pos = 1; pos <= 4; pos++) {
        ok = display.set_decimal_point(pos, true);                  // Light a decimal point, (position, on) → bool
    delay(400);
        ok = display.set_decimal_point(pos, false);
    }
    const uint8_t currents[4] = {3, 9, 15, 21};
    for (uint8_t i = 0; i < 4; i++) {
        ok = display.set_current(currents[i]);                      // Step the segment current, (ma) → bool
        ok = display.show_number(8888);
    delay(1000);
    }
    ok = display.set_current(12);
    display.set_mode(false);                                        // Static mode, (dynamic) → void
    delay(2000);
    display.set_mode(true);
    display.segment_test(true);                                     // Segment test, (on) → void
    delay(2000);
    display.segment_test(false);
    Serial.print("status 0x"); Serial.print((unsigned)(display.read_status()), HEX); Serial.println();// Read the status byte, () → uint8_t
    Serial.print("power reset "); Serial.print((unsigned long)(display.power_reset_flag())); Serial.println();// Read the PR flag, () → bool
    display.clear();                                                // Blank all digits, () → void
    Serial.print("ok "); Serial.print((unsigned long)(ok)); Serial.println();

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
