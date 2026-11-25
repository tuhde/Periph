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

// Same checks as cpp/tests/display/ssd1305_smoke.h, inlined: the Arduino test runner copies
// each sketch on its own, so a relative include would not resolve.
/** @brief Run the SSD1305 checks on an SSD1305Full instance (I²C: the chip can be read back).
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void ssd1305Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_128x64", chip.width() == 128 && chip.height() == 64);

    uint8_t s = 0xFF;
    bool ok = chip.read_status(s);
    report("status_read", ok);
    report("status_not_busy", (s & 0x80) == 0);
    report("status_display_on", (s & 0x40) == 0);

    chip.sleep();
    chip.read_status(s);
    report("sleep_sets_off_bit", (s & 0x40) != 0);
    chip.wake();
    chip.read_status(s);
    report("wake_clears_off_bit", (s & 0x40) == 0);

    // RAM read-back: draw, show, read page 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7);
    chip.draw_line(0, 31, 31, 24);
    chip.draw_rect(40, 4, 20, 20);
    chip.show();
    uint8_t ram[16];
    bool match0 = true, match3 = true;
    for (int part = 0; part < 4; part++) {
        chip.read_page(0, (uint8_t)(2 + part * 16), ram, 16);
        for (int x = 0; x < 16; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(part * 16 + x, b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match0 = false;
        }
        chip.read_page(3, (uint8_t)(2 + part * 16), ram, 16);
        for (int x = 0; x < 16; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(part * 16 + x, 24 + b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match3 = false;
        }
    }
    report("ram_readback_page0", match0);
    report("ram_readback_page3", match3);

    chip.set_contrast(0x40);
    chip.set_contrast(0x80);
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
    chip.set_clock(1, 7);
    chip.set_precharge(2, 2);
    chip.set_com_config(true);
    chip.set_vcomh(0x34);
    chip.set_dim_mode(0x40, 0x40);
    chip.set_dim(true);
    chip.set_dim(false);
    chip.set_scroll_horizontal(true, 0, 7, 0);
    chip.scroll_start();
    chip.scroll_stop();
    chip.set_addressing_mode(SSD1305Minimal::ADDR_HORIZONTAL);
    chip.show();
    chip.set_addressing_mode(SSD1305Minimal::ADDR_PAGE);
    chip.show_page(0);
    chip.show();
    chip.read_status(s);
    report("commands_accepted", chip.valid() && (s & 0x80) == 0);
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
    static SSD1305Full chip(conn);
    ssd1305Checks(chip, report);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
