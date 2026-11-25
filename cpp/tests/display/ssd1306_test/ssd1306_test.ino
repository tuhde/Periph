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

int passed = 0;
int failed = 0;

void report(const char* label, bool ok) {
    if (ok) { Serial.print("PASS "); Serial.println(label); passed++; }
    else    { Serial.print("FAIL "); Serial.println(label); failed++; }
}

// Same checks as cpp/tests/display/ssd1306_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the SSD1306 checks on an SSD1306Full instance .
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void ssd1306Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_128x64", chip.width() == 128 && chip.height() == 64);

    chip.fill(false);
    chip.draw_line(0, 0, 31, 7);
    chip.draw_line(0, 31, 31, 24);
    chip.draw_rect(40, 4, 20, 20);
    chip.show();
    report("framebuffer_pixels", chip.get_pixel(0, 0) && chip.get_pixel(40, 4));

    chip.set_contrast(0x40);
    chip.set_contrast(0x7F);
    chip.set_invert(true);
    chip.set_invert(false);
    chip.set_all_on(true);
    chip.set_all_on(false);
    chip.set_flip(true, true);
    chip.set_flip(false, false);
    chip.set_start_line(16);
    chip.set_start_line(0);
    chip.set_display_offset(0);
    chip.set_multiplex(64);
    chip.set_clock(1, 8);
    chip.set_precharge(2, 2);
    chip.set_com_config(true);
    chip.set_vcomh(0x20);
    chip.set_charge_pump(true);
    chip.set_scroll_horizontal(true, 0, 7, 7);
    chip.scroll_start();
    report("show_refused_while_scrolling", !chip.show());
    chip.scroll_stop();
    chip.set_addressing_mode(SSD1306Minimal::ADDR_HORIZONTAL);
    chip.show();
    chip.set_addressing_mode(SSD1306Minimal::ADDR_PAGE);
    chip.show_page(0);
    chip.show();
    report("commands_accepted", chip.valid());
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
    static I2CConnection conn(Wire, TEST_ADDR);
    static SSD1306Full chip(conn);
    ssd1306Checks(chip, report);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
