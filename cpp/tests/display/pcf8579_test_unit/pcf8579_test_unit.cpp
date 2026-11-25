#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "PCF8579.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static Bytes cat(std::initializer_list<uint8_t> head, size_t n, uint8_t fill) {
    Bytes out(head);
    out.insert(out.end(), n, fill);
    return out;
}

static Bytes b(std::initializer_list<uint8_t> v) { return Bytes(v); }

int main() {
    I2CConnectionMock connection;
    PCF8579Full lcd(connection);
    const auto& w = connection.writes();

    // init (row mode, 1:16, subaddress 0): clear-RAM transaction, then display on (normal).
    check_true(w[0] == cat({0xE0, 0xC2, 0xFC, 0xF8, 0x00}, 160, 0), "init_clear_write");
    check_true(w[1] == b({0x46}), "init_display_on");

    {
        // subaddress + mixed mode (T = 1)
        I2CConnectionMock c;
        PCF8579Minimal m(c, 3, 16, true);
        check_true(c.writes()[0] == cat({0xE3, 0xD2, 0xFC, 0xF8, 0x00}, 160, 0), "init_subaddress_mixed");
        check_true(c.writes()[1] == b({0x56}), "init_mixed_on");

        const uint8_t rows[3] = {8, 24, 32};
        const uint8_t want[3] = {0x45, 0x47, 0x44};
        for (int i = 0; i < 3; i++) {
            I2CConnectionMock c2;
            PCF8579Minimal m2(c2, 0, rows[i]);
            check_true(c2.writes()[1] == b({want[i]}), "init_on_rows");
        }
        I2CConnectionMock c3;
        PCF8579Minimal m3(c3, 0, 12);  // invalid rows fall back to 16
        check_true(c3.writes()[1] == b({0x46}), "rows_invalid_falls_back_to_16");
    }

    // clear()
    lcd.clear();
    check_true(w.back() == cat({0xE0, 0xF8, 0x00}, 160, 0), "clear");

    // set_pixel(): (0,0) -> RAM X=0, bank 0, bit 0.
    lcd.set_pixel(0, 0);
    check_true(w.back() == b({0xE0, 0xF0, 0, 0x01}), "set_pixel_write");
    lcd.set_pixel(3, 9);
    check_true(w.back() == b({0xE0, 0xF1, 3, 0x02}), "set_pixel_bank1");
    lcd.set_pixel(3, 10);
    check_true(w.back() == b({0xE0, 0xF1, 3, 0x06}), "set_pixel_shadow_or");
    lcd.set_pixel(3, 9, false);
    check_true(w.back() == b({0xE0, 0xF1, 3, 0x04}), "set_pixel_clear");
    lcd.set_pixel(39, 15);
    check_true(w.back() == b({0xE0, 0xF1, 39, 0x80}), "set_pixel_last_column");
    size_t n = w.size();
    lcd.set_pixel(40, 0);
    lcd.set_pixel(0, 16);
    lcd.set_pixel(-1, 0);
    check_true(w.size() == n, "set_pixel_out_of_range_ignored");

    // get_pixel() from the shadow, no bus traffic
    check_true(lcd.get_pixel(0, 0), "get_pixel_set");
    check_true(!lcd.get_pixel(3, 9), "get_pixel_clear");
    check_true(w.size() == n, "get_pixel_no_traffic");

    // write_columns(): truncated at column 39
    const uint8_t cols[4] = {1, 2, 3, 4};
    lcd.write_columns(38, 0, cols, 4);
    check_true(w.back() == b({0xE0, 0xF0, 38, 1, 2}), "write_columns_truncated");
    check_true(!lcd.write_columns(0, 2, cols, 1), "write_columns_bad_bank_rejected");

    // fill()
    lcd.fill(true);
    check_true(w[w.size() - 2] == cat({0xE0, 0xF0, 0}, 40, 0xFF), "fill_bank0");
    check_true(w.back() == cat({0xE0, 0xF1, 0}, 40, 0xFF), "fill_bank1");
    lcd.fill(false);
    check_true(!lcd.get_pixel(5, 5), "fill_off_shadow");

    // draw_line(): horizontal line on row 0 -> one batched write
    lcd.clear();
    n = w.size();
    lcd.draw_line(0, 0, 3, 0);
    check_true(w.size() == n + 1, "draw_line_one_write");
    check_true(w.back() == b({0xE0, 0xF0, 0, 1, 1, 1, 1}), "draw_line_bytes");

    // draw_rect()
    lcd.clear();
    lcd.draw_rect(0, 0, 3, 3);
    check_true(lcd.get_pixel(0, 0) && lcd.get_pixel(2, 2) && !lcd.get_pixel(1, 1), "draw_rect_outline");
    lcd.draw_rect(0, 0, 3, 3, true, true);
    check_true(lcd.get_pixel(1, 1), "draw_rect_filled");

    // show_bitmap()
    const uint8_t bmp[2] = {0xAA, 0xBB};
    lcd.show_bitmap(2, 8, bmp, 2);
    check_true(w.back() == b({0xE0, 0xF1, 2, 0xAA, 0xBB}), "show_bitmap");
    check_true(!lcd.show_bitmap(0, 3, bmp, 2), "show_bitmap_unaligned_rejected");

    // set_display()
    lcd.set_display(PCF8579Full::DISPLAY_BLANK);
    check_true(w.back() == b({0x42}), "set_display_blank");
    lcd.set_display(PCF8579Full::DISPLAY_INVERSE);
    check_true(w.back() == b({0x4E}), "set_display_inverse");
    lcd.set_display(PCF8579Full::DISPLAY_ALL_ON);
    check_true(w.back() == b({0x4A}), "set_display_all_on");
    lcd.set_display(PCF8579Full::DISPLAY_NORMAL);
    check_true(w.back() == b({0x46}), "set_display_normal");

    // set_start_bank() / scroll()
    lcd.set_start_bank(1);
    check_true(w.back() == b({0x7D}), "set_start_bank");
    lcd.scroll(1);
    check_true(w.back() == b({0x7C}), "scroll_wraps");
    lcd.scroll(-1);
    check_true(w.back() == b({0x7D}), "scroll_negative");
    check_true(!lcd.set_start_bank(4), "start_bank_invalid_rejected");

    // write_ram(): modes
    const uint8_t d2[2] = {1, 2};
    const uint8_t d4[4] = {1, 2, 3, 4};
    const uint8_t d8[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    lcd.write_ram(10, 0, d2, 2);
    check_true(w.back() == b({0xE0, 0xF0, 10, 1, 2}), "write_ram_character");
    lcd.write_ram(10, 2, d4, 4, PCF8579Full::ACCESS_HALF_GRAPHIC);
    check_true(w.back() == b({0xE0, 0xF6, 10, 1, 2, 3, 4}), "write_ram_half");
    lcd.write_ram(10, 0, d8, 8, PCF8579Full::ACCESS_FULL_GRAPHIC);
    check_true(w.back() == b({0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8}), "write_ram_full");
    check_true(!lcd.write_ram(39, 0, d2, 2), "write_ram_past_39_rejected");
    check_true(!lcd.write_ram(0, 4, d2, 1), "write_ram_bad_bank_rejected");
    check_true(!lcd.write_ram(0, 1, d2, 1, PCF8579Full::ACCESS_HALF_GRAPHIC), "write_ram_half_odd_bank_rejected");

    // write_ram_cascade(): runs past column 39
    uint8_t big[200];
    for (int i = 0; i < 200; i++) big[i] = 7;
    check_true(lcd.write_ram_cascade(big, sizeof(big)), "cascade_ok");
    check_true(w.back() == cat({0xE0, 0xF8, 0}, 200, 7), "cascade_write");
    uint8_t huge[PCF8579Full::MAX_CASCADE_BYTES + 1] = {0};
    check_true(!lcd.write_ram_cascade(huge, sizeof(huge)), "cascade_too_long_rejected");

    // read_ram(): address-set commands then a repeated-start read
    uint8_t buf[4];
    check_true(lcd.read_ram(16, 0, buf, 4), "read_ram_ok");
    check_true(w.back() == b({0xE0, 0xF0, 16}), "read_ram_commands");
    lcd.read_ram(5, 2, buf, 3, PCF8579Full::ACCESS_HALF_GRAPHIC);
    check_true(w.back() == b({0xE0, 0xF6, 5}), "read_ram_half_commands");

    // set_mode(): rows and T bit
    lcd.set_mode(24, true);
    check_true(w.back() == b({0x57}), "set_mode_24_mixed");
    lcd.set_mode(16, false);
    check_true(w.back() == b({0x46}), "set_mode_row");
    check_true(!lcd.set_mode(12), "set_mode_invalid_rejected");

    // subaddress is re-sent before every RAM access
    {
        I2CConnectionMock c;
        PCF8579Full sub(c, 5);
        sub.set_pixel(0, 0);
        check_true(c.writes().back()[0] == 0xE5, "pixel_uses_subaddress");
    }

    // reset(): re-runs init
    lcd.reset();
    check_true(w[w.size() - 2] == cat({0xE0, 0xC2, 0xFC, 0xF8, 0x00}, 160, 0), "reset_clear");
    check_true(w.back() == b({0x46}), "reset_on");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
