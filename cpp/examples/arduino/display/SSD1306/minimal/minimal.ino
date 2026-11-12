// SSD1306 minimal example: SSD1306 128 x 64 OLED, Arduino (3.3 V board)
// Wiring: SDA/SCL = the board's Wire pins (ESP32: TEST_SDA 8 / TEST_SCL 9); VDD = 3V3
// Module: 0.96" 128 x 64 OLED (SSD1306), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 128 x 64 panel.
#include <stdarg.h>
#include <stdio.h>
#include <Wire.h>
#include <Periph.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x3C
#endif

static void PRINT(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}
#define DELAY_MS(ms) delay(ms)

static void run(SSD1306Minimal& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 128; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, 63);                       // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x / 2));  // Set pixel, (x 0..127, y 0..63, on=true)
    }
    for (int16_t y = 0; y < 64; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.set_pixel(127, y);                      // Set pixel, (x 0..127, y 0..63, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    static I2CConnection conn(Wire, TEST_ADDR);                   // Create I2C connection, (wire, addr=0x3C)
    static SSD1306Minimal lcd(conn);  // Create SSD1306 driver, (connection, width=128, height=64, external_vcc=false)
    if (!lcd.valid()) {
        PRINT("SSD1306 init failed\n");
        return;
    }
    run(lcd);
}

void loop() {}
