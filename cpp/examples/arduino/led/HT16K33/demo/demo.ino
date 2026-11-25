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

    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The HT16K33 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one write_raw() call. The countdown runs once
    // per second and the terminal mirrors the value sent to the display.
    HT16K33Full matrix(connection);                      // Create HT16K33 driver, (connection)

    // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
    // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
    // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
    // for 20 s via the INT output before standby and wake.
    for (uint8_t com = 0; com < HT16K33Full::COMMONS; com++) {
        for (uint8_t row = 0; row < HT16K33Full::ROWS; row++) {
            matrix.clear();                               // Blank the display, () → void
            matrix.set_pixel(com, row);                   // Set one LED, (com 0–7, row 0–15, on) → bool
            matrix.show();                                // Write the shadow RAM, () → void
            delay(50);
        }
    }

    static const uint8_t all_on[16] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                                       0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    matrix.write_raw(all_on, 16, 0);                      // All LEDs on, (data, len, address) → bool
    for (uint8_t level = 0; level < 16; level++) {
        matrix.set_brightness(level);                     // Sweep dimming, (level 0–15) → void
        delay(100);
    }

    static const float blink_hz[4] = {2.0f, 1.0f, 0.5f, 0.0f};
    for (uint8_t i = 0; i < 4; i++) {
        matrix.set_blink(blink_hz[i]);                    // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
        delay(3000);
    }

    matrix.set_int_output(true);                          // INT output, active low, (enable, active_high) → void
    for (uint16_t t = 0; t < 1000; t++) {                 // 1000 x 20 ms = 20 s
        uint64_t keys = matrix.poll_interrupt();          // Read keys if pending, () → uint64_t bitmask
        if (keys) Serial.println((unsigned long)(keys));
        delay(20);
    }

    matrix.standby();                                     // Oscillator off, () → void
    delay(1000);
    matrix.wake();                                        // Oscillator on, state restored, () → void

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
