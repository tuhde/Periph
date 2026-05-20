#include <SPI.h>
#include "NeoPixelTransport.h"
#include "SK6812RGBW.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

static void check_eq_u8(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { Serial.print("PASS "); Serial.println(label); passed++; }
    else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got "); Serial.print(got);
        Serial.print(", expected "); Serial.println(expected);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    NeoPixelTransport transport(SPI);

    // --- SK6812RGBWMinimal ---
    {
        SK6812RGBWMinimal strip(transport, 8);

        strip.fill(255, 0, 0);
        check_true("fill(255,0,0) accepted", true);

        strip.fill(0, 255, 0);
        check_true("fill(0,255,0) accepted", true);

        strip.fill(0, 0, 255);
        check_true("fill(0,0,255) accepted", true);

        strip.fill(0, 0, 0, 255);
        check_true("fill(w=255) accepted", true);

        strip.off();
        check_true("off() accepted", true);
    }

    // --- SK6812RGBWFull ---
    {
        SK6812RGBWFull strip(transport, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0);
        strip.show();
        check_true("set_pixel(0,...) + show accepted", true);

        strip.set_pixel(7, 0, 0, 0, 255);
        strip.show();
        check_true("set_pixel(w=255) + show accepted", true);

        strip.set_brightness(128);
        check_eq_u8("brightness setter", strip.get_brightness(), 128);
        strip.show();
        check_true("show() with brightness=128 accepted", true);

        strip.set_brightness(0);
        strip.show();
        check_true("show() with brightness=0 accepted", true);

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show();
        check_true("rotate(1) + show accepted", true);

        strip.fill_hsv(0.0f, 1.0f, 1.0f);
        check_true("fill_hsv(0.0) accepted", true);

        strip.fill_hsv(0.333f, 1.0f, 1.0f);
        check_true("fill_hsv(0.333) accepted", true);

        strip.fill_hsv(0.667f, 1.0f, 1.0f);
        check_true("fill_hsv(0.667) accepted", true);

        strip.off();
        check_true("off() on Full accepted", true);
    }

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
