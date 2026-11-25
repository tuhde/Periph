// SH1106 demo example: SH1106 128 x 64 OLED, Arduino (3.3 V board)
// Wiring: SDA/SCL = the board's Wire pins (ESP32: TEST_SDA 8 / TEST_SCL 9); VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SH1106, RAM columns 2..129), I2C address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1); pull-ups 4.7 k to 3.3 V.
// Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
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

static void run(SH1106Full& lcd) {
    // --- Draw the border once ---
    // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
    lcd.clear();                                    // Clear framebuffer, ()
    lcd.draw_rect(0, 0, 128, 64);                   // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()

    // --- Bounce a 6 x 6 ball at about 20 frames per second ---
    // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
    int16_t bx = 20, by = 12, dx = 3, dy = 2;
    for (int frame = 0; frame < 200; frame++) {
        lcd.draw_rect(bx, by, 6, 6, false, true);   // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
        lcd.draw_rect(bx, by, 6, 6, true, true);    // Draw rectangle, (x, y, w, h, on=true, fill=false)
        lcd.show();                                 // Send framebuffer to the display, ()
        DELAY_MS(50);
    }

    // --- Fade the contrast down and back up ---
    // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
    for (int v = 255; v >= 0; v -= 5) {
        lcd.set_contrast(static_cast<uint8_t>(v));  // Set contrast, (value 0..255)
        DELAY_MS(20);
    }
    for (int v = 0; v <= 255; v += 5) {
        lcd.set_contrast(static_cast<uint8_t>(v));  // Set contrast, (value 0..255)
        DELAY_MS(20);
    }

    // --- Flash the inverse display twice ---
    for (int i = 0; i < 2; i++) {
        lcd.set_invert(true);                       // Invert display, (on)
        DELAY_MS(400);
        lcd.set_invert(false);                      // Invert display, (on)
        DELAY_MS(400);
    }

    // --- Hardware scroll ---
    // Stepping the display start line moves the image up inside the controller, no data is re-sent;
    // the picture wraps around after 64 lines.
    for (int line = 0; line < 64; line++) {
        lcd.set_start_line(static_cast<uint8_t>(line));  // Scroll the image up, (line 0..63)
        DELAY_MS(30);
    }
    for (int line = 63; line >= 0; line--) {
        lcd.set_start_line(static_cast<uint8_t>(line));  // Scroll the image up, (line 0..63)
        DELAY_MS(30);
    }

    // --- Power down ---
    // Sleep turns the display and the charge pump off; RAM and registers are kept.
    lcd.sleep();                                    // Enter sleep, ()
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
    static SH1106Full lcd(conn);  // Create SH1106 driver, (connection, width=128, height=64, column_offset=2, internal_dcdc=true)
    if (!lcd.valid()) {
        PRINT("SH1106 init failed\n");
        return;
    }
    run(lcd);
}

void loop() {}
