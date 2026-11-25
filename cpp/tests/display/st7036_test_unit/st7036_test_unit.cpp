// Unit test for the ST7036 driver against DisplayConnectionMock — no hardware.
#include <stdio.h>
#include <stdint.h>
#include <string>
#include <vector>
#include "DisplayConnectionMock.h"
#include "ST7036.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

typedef DisplayConnectionMock::Event Event;
typedef ST7036Minimal<DisplayConnectionMock> Mini;
typedef ST7036Full<DisplayConnectionMock> Full;

// Trace: R reset, I<hh> instruction, D<hh> data byte, d<ms> millisecond delay, u<us> microsecond delay.
static std::string trace(const DisplayConnectionMock& m, size_t from = 0) {
    static const char* hex = "0123456789ABCDEF";
    std::string s;
    const std::vector<Event>& ev = m.events();
    for (size_t i = from; i < ev.size(); i++) {
        if (!s.empty()) s += ' ';
        const Event& e = ev[i];
        switch (e.kind) {
            case Event::kReset:   s += "R"; break;
            case Event::kCommand: s += "I"; s += hex[e.cmd >> 4]; s += hex[e.cmd & 15]; break;
            case Event::kData:
                s += "D"; s += hex[e.bytes[0] >> 4]; s += hex[e.bytes[0] & 15];
                if (e.bytes.size() != 1) s += "!";
                break;
            case Event::kDelay:   s += (e.delayMicro ? "u" : "d") + std::to_string(e.timeoutMs); break;
            default:              s += "?"; break;
        }
    }
    return s;
}

// The instruction bytes only (no parameters allowed: every instruction is a single byte).
static bool noParams(const DisplayConnectionMock& m) {
    for (const Event& e : m.events()) if (e.kind == Event::kCommand && !e.bytes.empty()) return false;
    return true;
}

int main() {
    // ---- Minimal: DOGM162 3.3 V sequence ------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini lcd(m);
        check_true(lcd.valid(), "init_valid");
        check_true(trace(m) == "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30",
                   "init_sequence_dogm162_3v3");
        check_true(noParams(m), "instructions_are_single_bytes");
        check_true(lcd.columns() == 16 && lcd.rows() == 2, "geometry_16x2");
    }
    {
        DisplayConnectionMock m;
        Mini lcd(m);
        size_t n = m.events().size();
        lcd.clear();
        check_true(trace(m, n) == "I01 d2", "clear_waits_2ms");
        n = m.events().size();
        lcd.home();
        check_true(trace(m, n) == "I02 d2", "home_waits_2ms");
        n = m.events().size();
        lcd.set_cursor(3, 1);
        check_true(trace(m, n) == "IC3 u30", "set_cursor_row1_col3");
        n = m.events().size();
        lcd.set_cursor(0, 0);
        check_true(trace(m, n) == "I80 u30", "set_cursor_origin");
        n = m.events().size();
        lcd.set_cursor(16, 0); lcd.set_cursor(0, 2);
        check_true(m.events().size() == n, "set_cursor_out_of_range_ignored");
        n = m.events().size();
        lcd.write("Hi");
        check_true(trace(m, n) == "D48 u30 D69 u30", "write_one_byte_per_transfer_with_gap");
        n = m.events().size();
        lcd.write("a\xE4z~");
        check_true(trace(m, n) == "D61 u30 D3F u30 D7A u30 D3F u30", "write_non_ascii_becomes_question_mark");
        n = m.events().size();
        lcd.write_char(0x05);
        check_true(trace(m, n) == "D05 u30", "write_char_raw");
        n = m.events().size();
        lcd.print_at(2, 1, "x");
        check_true(trace(m, n) == "IC2 u30 D78 u30", "print_at");
        n = m.events().size();
        lcd.write(nullptr);
        check_true(m.events().size() == n, "write_null_ignored");
    }
    // ---- Full: presets ------------------------------------------------------------------------
    {
        struct { uint8_t lines; Full::Supply sup; const char* want; const char* label; } cases[] = {
            {1, Full::SUPPLY_3V3, "R d40 I31 u30 I14 u30 I7C u30 I55 u30 I6D u30 d200 I30 u30 I0C u30 I01 d2 I06 u30", "dogm081_3v3"},
            {1, Full::SUPPLY_5V,  "R d40 I31 u30 I1C u30 I74 u30 I51 u30 I6A u30 d200 I30 u30 I0C u30 I01 d2 I06 u30", "dogm081_5v"},
            {2, Full::SUPPLY_3V3, "R d40 I39 u30 I14 u30 I78 u30 I55 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", "dogm162_3v3"},
            {2, Full::SUPPLY_5V,  "R d40 I39 u30 I1C u30 I74 u30 I52 u30 I69 u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", "dogm162_5v"},
            {3, Full::SUPPLY_3V3, "R d40 I39 u30 I15 u30 I72 u30 I55 u30 I6E u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", "dogm163_3v3"},
            {3, Full::SUPPLY_5V,  "R d40 I39 u30 I1D u30 I7C u30 I50 u30 I6C u30 d200 I38 u30 I0C u30 I01 d2 I06 u30", "dogm163_5v"},
        };
        for (auto& c : cases) {
            DisplayConnectionMock m;
            Full lcd(m, c.lines, c.sup);
            check_true(lcd.valid() && trace(m) == c.want, c.label);
        }
        DisplayConnectionMock m;
        Full lcd(m, 3);
        check_true(lcd.columns() == 16 && lcd.rows() == 3, "dogm163_geometry");
        size_t n = m.events().size();
        lcd.set_cursor(0, 2);
        check_true(trace(m, n) == "IA0 u30", "dogm163_row2_address_0x20");
        DisplayConnectionMock m1;
        Full one(m1, 1);
        check_true(one.columns() == 8 && one.rows() == 1, "dogm081_geometry");
        DisplayConnectionMock m2;
        Full ov(m2, 2, Full::SUPPLY_3V3, 0x2A);
        check_true(trace(m2) == "R d40 I39 u30 I14 u30 I7A u30 I56 u30 I6D u30 d200 I38 u30 I0C u30 I01 d2 I06 u30",
                   "contrast_override_0x2a");
        DisplayConnectionMock m3;
        Full bad(m3, 9);
        check_true(trace(m3).compare(0, 13, "R d40 I39 u30") == 0, "invalid_lines_falls_back_to_2");
    }
    // ---- Full: control instructions -----------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full lcd(m);
        size_t n;
        n = m.events().size(); lcd.display(false);
        check_true(trace(m, n) == "I08 u30", "display_off");
        n = m.events().size(); lcd.display(true);
        check_true(trace(m, n) == "I0C u30", "display_on");
        n = m.events().size(); lcd.cursor(true, true);
        check_true(trace(m, n) == "I0E u30 I0F u30", "cursor_and_blink");
        n = m.events().size(); lcd.cursor(false, false);
        check_true(trace(m, n) == "I0D u30 I0C u30", "cursor_off");
        n = m.events().size(); lcd.set_entry_mode(false, true);
        check_true(trace(m, n) == "I05 u30", "entry_mode_decrement_shift");
        n = m.events().size(); lcd.set_entry_mode(true, false);
        check_true(trace(m, n) == "I06 u30", "entry_mode_increment");
        n = m.events().size(); lcd.shift_cursor(true); lcd.shift_cursor(false);
        check_true(trace(m, n) == "I14 u30 I10 u30", "shift_cursor");
        n = m.events().size(); lcd.shift_display(true); lcd.shift_display(false);
        check_true(trace(m, n) == "I1C u30 I18 u30", "shift_display");
        n = m.events().size(); lcd.return_home();
        check_true(trace(m, n) == "I02 d2", "return_home");
        n = m.events().size(); lcd.set_ddram_address(0x45);
        check_true(trace(m, n) == "IC5 u30", "set_ddram_address");
        n = m.events().size(); lcd.send_command(0x1F); lcd.send_data(0x41);
        check_true(trace(m, n) == "I1F u30 D41 u30", "raw_command_and_data");
    }
    // ---- Full: extension table instructions ----------------------------------------------------
    {
        DisplayConnectionMock m;
        Full lcd(m);
        size_t n;
        n = m.events().size(); lcd.set_contrast(0x2D);
        check_true(trace(m, n) == "I39 u30 I56 u30 I7D u30 I38 u30", "set_contrast_0x2d");
        n = m.events().size(); lcd.set_booster(false);
        check_true(trace(m, n) == "I39 u30 I52 u30 I38 u30", "booster_off_keeps_contrast_high_bits");
        n = m.events().size(); lcd.set_booster(true);
        check_true(trace(m, n) == "I39 u30 I56 u30 I38 u30", "booster_on");
        n = m.events().size(); lcd.set_follower(true, 3);
        check_true(trace(m, n) == "I39 u30 I6B u30 I38 u30", "follower_on_ratio_3");
        n = m.events().size(); lcd.set_follower(false, 7);
        check_true(trace(m, n) == "I39 u30 I67 u30 I38 u30", "follower_off_ratio_7");
        n = m.events().size(); lcd.set_bias(true);
        check_true(trace(m, n) == "I39 u30 I1C u30 I38 u30", "bias_quarter");
        n = m.events().size(); lcd.set_bias(false);
        check_true(trace(m, n) == "I39 u30 I14 u30 I38 u30", "bias_fifth");
        n = m.events().size(); lcd.icon(true);
        check_true(trace(m, n) == "I39 u30 I5E u30 I38 u30", "icon_on");
        n = m.events().size(); lcd.write_icon(3, 0x15);
        check_true(trace(m, n) == "I39 u30 I43 u30 D15 u30 I38 u30 I80 u30", "write_icon_restores_ddram");
        DisplayConnectionMock m3;
        Full three(m3, 3);
        n = m3.events().size(); three.set_bias(false);
        check_true(trace(m3, n) == "I39 u30 I15 u30 I38 u30", "bias_keeps_fx_on_3_line_module");
    }
    // ---- Full: custom characters and double height --------------------------------------------
    {
        DisplayConnectionMock m;
        Full lcd(m);
        lcd.set_cursor(5, 1);
        size_t n = m.events().size();
        const uint8_t rows[8] = {0x1F, 0x00, 0xFF, 0x01, 0x02, 0x04, 0x08, 0x10};
        lcd.create_char(2, rows);
        check_true(trace(m, n) == "I50 u30 D1F u30 D00 u30 D1F u30 D01 u30 D02 u30 D04 u30 D08 u30 D10 u30 IC5 u30",
                   "create_char_masks_rows_and_restores_address");
        n = m.events().size(); lcd.create_char(9, rows);
        check_true(trace(m, n).compare(0, 7, "I48 u30") == 0, "create_char_index_masked_to_0_7");
        n = m.events().size(); lcd.set_double_height(true);
        check_true(m.events().size() == n, "double_height_ignored_on_2_line");
        DisplayConnectionMock m1;
        Full one(m1, 1);
        n = m1.events().size(); one.set_double_height(true);
        check_true(trace(m1, n) == "I34 u30 I34 u30", "double_height_1_line");
        n = m1.events().size(); one.set_double_height(false);
        check_true(trace(m1, n) == "I30 u30 I30 u30", "double_height_off_1_line");
        DisplayConnectionMock m3;
        Full three(m3, 3);
        n = m3.events().size(); three.set_double_height(true, true);
        check_true(trace(m3, n) == "I3E u30 I18 u30 I3C u30", "double_height_3_line_upper");
        n = m3.events().size(); three.set_double_height(true, false);
        check_true(trace(m3, n) == "I3E u30 I10 u30 I3C u30", "double_height_3_line_lower");
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
