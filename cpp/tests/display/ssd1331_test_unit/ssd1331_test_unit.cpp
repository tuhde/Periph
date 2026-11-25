// Unit test for the SSD1331 driver against DisplayConnectionMock — no hardware.
#include <stdio.h>
#include <stdint.h>
#include <string>
#include <vector>
#include "DisplayConnectionMock.h"
#include "SSD1331.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

typedef DisplayConnectionMock::Event Event;
typedef SSD1331Minimal<DisplayConnectionMock> Mini;
typedef SSD1331Full<DisplayConnectionMock> Full;

// Command/parameter bytes (all D/C# low) as a list; data as "D<n>"; R reset; "d<ms>" delay.
static std::string trace(const DisplayConnectionMock& m, size_t from = 0) {
    static const char* hex = "0123456789ABCDEF";
    std::string s;
    const std::vector<Event>& ev = m.events();
    for (size_t i = from; i < ev.size(); i++) {
        if (!s.empty()) s += ' ';
        const Event& e = ev[i];
        switch (e.kind) {
            case Event::kReset:    s += "R"; break;
            case Event::kCommand:  s += hex[e.cmd >> 4]; s += hex[e.cmd & 15]; break;
            case Event::kData:     s += "D" + std::to_string(e.bytes.size()); break;
            case Event::kDelay:    s += "d" + std::to_string(e.timeoutMs); break;
            default:               s += "?"; break;
        }
    }
    return s;
}

static size_t dataBytes(const DisplayConnectionMock& m, size_t from = 0) {
    size_t n = 0;
    for (size_t i = from; i < m.events().size(); i++)
        if (m.events()[i].kind == Event::kData) n += m.events()[i].bytes.size();
    return n;
}

int main() {
    static uint8_t fb[96 * 64 * 2];

    // ---- initialisation: the vendor sequence, every parameter a separate command byte -----------
    {
        DisplayConnectionMock m;
        Mini chip(m, fb);
        check_true(chip.valid(), "init_valid");
        check_true(trace(m) ==
            "R d100 AE 81 FF 82 FF 83 FF 87 06 8A 64 8B 78 8C 64 A0 72 A1 00 A2 00 A4 A8 3F AD 8E "
            "B0 0B B1 31 B3 F0 BB 3A BE 3E 2E 25 00 00 5F 3F d100 AF", "init_sequence_vendor");
        bool noParams = true;
        for (const Event& e : m.events()) if (e.kind == Event::kCommand && !e.bytes.empty()) noParams = false;
        check_true(noParams, "parameters_sent_as_commands_dc_low");
        check_true(dataBytes(m) == 0, "init_sends_no_pixel_data");
        check_true(fb[0] == 0 && fb[12287] == 0, "init_clears_framebuffer");
        check_true(chip.width() == 96 && chip.height() == 64, "geometry");
    }
    // ---- invalid configurations -------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini nobuf(m, nullptr);
        check_true(!nobuf.valid(), "null_buffer_invalid");
        size_t before = m.events().size();
        nobuf.show();
        check_true(m.events().size() == before, "invalid_show_is_noop");
    }
    // ---- framebuffer ---------------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fb);
        chip.set_pixel(0, 0, Full::RED);
        check_true(fb[0] == 0xF8 && fb[1] == 0x00, "pixel_rgb565_high_byte_first");
        chip.set_pixel(95, 63, Full::BLUE);
        check_true(fb[12286] == 0x00 && fb[12287] == 0x1F, "pixel_last_position");
        chip.set_pixel(1, 2, 0x1234);
        check_true(fb[(2 * 96 + 1) * 2] == 0x12 && fb[(2 * 96 + 1) * 2 + 1] == 0x34 && chip.get_pixel(1, 2) == 0x1234,
                   "pixel_index_and_readback");
        uint8_t b0 = fb[0];
        chip.set_pixel(-1, 0); chip.set_pixel(0, -1); chip.set_pixel(96, 0); chip.set_pixel(0, 64);
        check_true(fb[0] == b0 && chip.get_pixel(99, 99) == 0, "out_of_range_ignored");
        chip.clear(Full::GREEN);
        check_true(chip.get_pixel(0, 0) == Full::GREEN && chip.get_pixel(95, 63) == Full::GREEN, "clear_fills");
        check_true(Full::rgb(255, 0, 0) == 0xF800 && Full::rgb(0, 255, 0) == 0x07E0
                   && Full::rgb(0, 0, 255) == 0x001F && Full::rgb(255, 255, 255) == 0xFFFF, "rgb_packing");
        chip.clear();
        chip.draw_line(0, 0, 4, 4, Full::WHITE);
        check_true(chip.get_pixel(0, 0) == Full::WHITE && chip.get_pixel(2, 2) == Full::WHITE
                   && chip.get_pixel(4, 4) == Full::WHITE && chip.get_pixel(4, 0) == 0, "line_diagonal");
        chip.clear();
        chip.draw_rect(2, 2, 4, 3, Full::WHITE);
        check_true(chip.get_pixel(2, 2) == Full::WHITE && chip.get_pixel(5, 4) == Full::WHITE
                   && chip.get_pixel(3, 3) == 0 && chip.get_pixel(6, 2) == 0, "rect_outline");
        chip.draw_rect(10, 10, 3, 3, Full::RED, true);
        check_true(chip.get_pixel(11, 11) == Full::RED && chip.get_pixel(13, 13) == 0, "rect_filled");
        const uint16_t bmp[4] = {1, 2, 3, 4};
        chip.blit(20, 20, bmp, 2, 2);
        check_true(chip.get_pixel(20, 20) == 1 && chip.get_pixel(21, 20) == 2
                   && chip.get_pixel(20, 21) == 3 && chip.get_pixel(21, 21) == 4, "blit");
    }
    // ---- show / show_region -------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fb);
        chip.clear(Full::RED);
        size_t from = m.events().size();
        chip.show();
        std::string t = trace(m, from);
        check_true(t.compare(0, 20, "15 00 5F 75 00 3F D1") == 0, "show_sets_full_window");
        check_true(dataBytes(m, from) == 12288, "show_sends_12288_bytes");
        check_true(m.events()[from + 6].bytes[0] == 0xF8, "show_first_data_byte");
        from = m.events().size();
        chip.show_region(10, 20, 4, 2);
        check_true(trace(m, from) == "15 0A 0D 75 14 15 D8 D8" && dataBytes(m, from) == 16, "show_region_window_and_rows");
        from = m.events().size();
        chip.show_region(90, 60, 20, 20);   // clipped to 6 x 4
        check_true(trace(m, from).compare(0, 17, "15 5A 5F 75 3C 3F") == 0 && dataBytes(m, from) == 48, "show_region_clipped");
        from = m.events().size();
        chip.show_region(200, 0, 5, 5);
        check_true(m.events().size() == from, "show_region_off_panel_noop");
    }
    // ---- hardware primitives and settings -----------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fb);
        size_t f = m.events().size();
        chip.hw_line(1, 2, 3, 4, Full::RED);
        check_true(trace(m, f) == "21 01 02 03 04 3E 00 00", "hw_line_red");
        f = m.events().size();
        chip.hw_rect(10, 20, 5, 6, Full::GREEN, Full::BLUE, true);
        check_true(trace(m, f) == "26 01 22 0A 14 0E 19 00 3F 00 00 00 3E", "hw_rect_filled");
        f = m.events().size();
        chip.hw_rect(10, 20, 5, 6, Full::WHITE);
        check_true(trace(m, f).compare(0, 5, "26 00") == 0, "hw_rect_fill_off");
        f = m.events().size();
        chip.hw_rect(10, 20, 0, 6, Full::WHITE);
        check_true(m.events().size() == f, "hw_rect_zero_size_noop");
        f = m.events().size(); chip.hw_clear(0, 1, 2, 3);
        check_true(trace(m, f) == "25 00 01 02 03", "hw_clear");
        f = m.events().size(); chip.hw_dim(0, 1, 2, 3);
        check_true(trace(m, f) == "24 00 01 02 03", "hw_dim");
        f = m.events().size(); chip.hw_copy(1, 2, 3, 4, 5, 6);
        check_true(trace(m, f) == "23 01 02 03 04 05 06", "hw_copy");
        f = m.events().size(); chip.scroll(1, 0, 64, 2, 7);
        check_true(trace(m, f) == "27 01 00 40 02 03 2F", "scroll_interval_masked_and_started");
        f = m.events().size(); chip.scroll_stop();
        check_true(trace(m, f) == "2E", "scroll_stop");
        f = m.events().size(); chip.set_contrast(1, 2, 3);
        check_true(trace(m, f) == "81 01 82 02 83 03", "contrast");
        f = m.events().size(); chip.set_master_current(16);
        check_true(trace(m, f) == "87 0F", "master_current_16");
        f = m.events().size(); chip.set_master_current(0);
        check_true(trace(m, f) == "87 00", "master_current_clamped_low");
        f = m.events().size(); chip.set_display_mode(Full::MODE_INVERSE);
        check_true(trace(m, f) == "A7", "mode_inverse");
        f = m.events().size(); chip.set_flip(false, false);
        check_true(trace(m, f) == "A0 60", "flip_none_keeps_other_bits");
        f = m.events().size(); chip.set_flip(true, true);
        check_true(trace(m, f) == "A0 72", "flip_both");
        f = m.events().size(); chip.set_power_save(true);
        check_true(trace(m, f) == "B0 1A", "power_save_on");
        f = m.events().size(); chip.set_power_save(false);
        check_true(trace(m, f) == "B0 0B", "power_save_off");
        f = m.events().size(); chip.dim_mode(1, 2, 3, 0xFF);
        check_true(trace(m, f) == "AB 00 01 02 03 1F AC", "dim_mode_precharge_masked");
        f = m.events().size(); chip.display_off(); chip.display_on();
        check_true(trace(m, f) == "AE AF", "display_off_on");
        f = m.events().size(); chip.gray_linear();
        check_true(trace(m, f) == "B9", "gray_linear");
        f = m.events().size();
        const uint8_t p[2] = {0x12, 0x34};
        chip.send_command(0xAA, p, 2);
        check_true(trace(m, f) == "AA 12 34", "raw_command_params_as_commands");
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
