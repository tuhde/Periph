#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "UC1611s.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static bool eq(const Bytes& a, std::initializer_list<uint8_t> b) {
    return a == Bytes(b);
}

// Writes made by the last call: everything after index `from`.
static size_t mark(const I2CConnectionMock& c) { return c.writes().size(); }

int main() {
    // ---- Minimal: initialization ----------------------------------------------------
    I2CConnectionMock cmd, dat;
    UC1611sMinimal lcd(cmd, dat);
    check_true(lcd.valid() && lcd.width() == 240 && lcd.height() == 128, "geometry_240x128");
    // 1 System Reset + 1 configuration stream + 20 page addresses + 1 display enable
    check_true(cmd.writes().size() == 23, "init_command_transactions");
    check_true(eq(cmd.writes()[0], {0xE2}), "init_system_reset_own_transaction");
    check_true(eq(cmd.writes()[1], {0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F,
                                     0x81, 0x8F, 0x89, 0xD1}), "init_configuration_stream");
    check_true(eq(cmd.writes()[2], {0x60, 0x70, 0x00, 0x10}), "init_clear_page0_address");
    check_true(eq(cmd.writes()[21], {0x63, 0x71, 0x00, 0x10}), "init_clear_page19_address");
    check_true(eq(cmd.writes()[22], {0xA9}), "init_display_enable_last");
    check_true(dat.writes().size() == 160, "init_clear_data_chunks_20_pages_x_8");
    bool chunks_ok = true, zeros_ok = true;
    for (size_t i = 0; i < dat.writes().size(); i++) {
        size_t expected = (i % 8 == 7) ? 16 : 32;
        if (dat.writes()[i].size() != expected) chunks_ok = false;
        for (uint8_t b : dat.writes()[i]) if (b != 0) zeros_ok = false;
    }
    check_true(chunks_ok, "init_data_chunks_32_32_..._16");
    check_true(zeros_ok, "init_data_all_zero");

    // ---- Minimal: pixels and show ---------------------------------------------------
    size_t c0 = mark(cmd), d0 = mark(dat);
    lcd.set_pixel(3, 10);
    lcd.set_pixel(239, 127);
    lcd.set_pixel(240, 0);      // out of range, ignored
    lcd.set_pixel(-1, 5);
    check_true(mark(cmd) == c0 && mark(dat) == d0, "set_pixel_framebuffer_only");
    lcd.show();
    check_true(cmd.writes().size() - c0 == 16 && dat.writes().size() - d0 == 128, "show_16_pages_8_chunks");
    check_true(eq(cmd.writes()[c0 + 1], {0x61, 0x70, 0x00, 0x10}), "show_page1_address");
    // pixel (3,10): page 1, column 3, bit 2 -> first chunk of page 1 (data write index 8)
    check_true(dat.writes()[d0 + 8][3] == 0x04, "show_pixel_page1_col3_bit2");
    // pixel (239,127): page 15, column 239, bit 7 -> last chunk (16 bytes), last byte
    check_true(dat.writes()[d0 + 127].size() == 16 && dat.writes()[d0 + 127][15] == 0x80, "show_pixel_page15_col239_bit7");
    lcd.set_pixel(3, 10, false);
    lcd.clear();
    d0 = mark(dat);
    lcd.show();
    bool blank = true;
    for (size_t i = d0; i < dat.writes().size(); i++) for (uint8_t b : dat.writes()[i]) if (b) blank = false;
    check_true(blank, "clear_zeroes_framebuffer");

    // ---- Minimal: argument validation -----------------------------------------------
    I2CConnectionMock c1, d1;
    UC1611sMinimal bad(c1, d1, 241, 128);
    check_true(!bad.valid() && c1.writes().empty() && d1.writes().empty(), "invalid_width_does_nothing");
    UC1611sMinimal bad2(c1, d1, 240, 130);
    check_true(!bad2.valid(), "invalid_height");

    // ---- Full ------------------------------------------------------------------------
    I2CConnectionMock fc, fd;
    UC1611sFull full(fc, fd);
    check_true(full.valid(), "full_valid");

    full.set_contrast(0x55);
    check_true(eq(fc.writes().back(), {0x81, 0x55}), "set_contrast");
    full.set_display(false);
    check_true(eq(fc.writes().back(), {0xA8}), "display_off_sleep");
    full.wake();
    check_true(eq(fc.writes().back(), {0xA9}), "wake_display_on");
    full.sleep();
    check_true(eq(fc.writes().back(), {0xA8}), "sleep");
    full.set_invert(true);
    check_true(eq(fc.writes().back(), {0xA7}), "invert_on");
    full.set_invert(false);
    check_true(eq(fc.writes().back(), {0xA6}), "invert_off");
    full.set_all_on(true);
    check_true(eq(fc.writes().back(), {0xA5}), "all_on");
    full.set_all_on(false);
    check_true(eq(fc.writes().back(), {0xA4}), "all_on_off");
    full.set_flip(true, true);
    check_true(eq(fc.writes().back(), {0xC0, 0x06}), "flip_mx_my");
    full.set_flip(false, true);
    check_true(eq(fc.writes().back(), {0xC0, 0x04}), "flip_my_only");
    full.set_scroll_line(40);
    check_true(eq(fc.writes().back(), {0x48, 0x52}), "scroll_line_40");
    full.set_fixed_lines(3);
    check_true(eq(fc.writes().back(), {0x93}), "fixed_lines");
    full.set_bias_ratio(3);
    check_true(eq(fc.writes().back(), {0xEB}), "bias_ratio");
    full.set_temperature_compensation(2);
    check_true(eq(fc.writes().back(), {0x26}), "temperature_compensation");
    full.set_line_rate(1);
    check_true(eq(fc.writes().back(), {0xA1}), "line_rate");
    full.set_pump(false);
    check_true(eq(fc.writes().back(), {0x2C}), "pump_external");
    full.set_pump(true);
    check_true(eq(fc.writes().back(), {0x2F}), "pump_internal");
    full.set_panel_loading(true);
    check_true(eq(fc.writes().back(), {0x2B}), "panel_loading_high");
    full.set_panel_loading(false);
    check_true(eq(fc.writes().back(), {0x28}), "panel_loading_low");
    full.set_partial_display(true, 10, 100);
    check_true(eq(fc.writes().back(), {0xF2, 10, 0xF3, 100, 0x87}), "partial_display_on");
    full.set_partial_display(true, 4);
    check_true(eq(fc.writes().back(), {0xF2, 4, 0xF3, 127, 0x87}), "partial_display_default_end");
    full.set_partial_display(false);
    check_true(eq(fc.writes().back(), {0x84}), "partial_display_off");
    full.set_nline_inversion(5, true);
    check_true(eq(fc.writes().back(), {0xC8, 0x45}), "nline_inversion_xor");
    size_t k = mark(fc);
    full.set_isolation_clock(2, 3);
    check_true(fc.writes().size() - k == 2 && eq(fc.writes()[k], {0x82, 0x13, 0x02}) &&
               eq(fc.writes()[k + 1], {0x82, 0x14, 0x03}), "isolation_clock_two_triple_bytes");
    full.set_display_pattern(2);
    check_true(eq(fc.writes().back(), {0xD5}), "display_pattern_2");
    full.set_display_pattern(0);
    check_true(eq(fc.writes().back(), {0xD1}), "display_pattern_0");

    // write pattern goes into PA[6:5]
    size_t fd0 = mark(fd);
    full.set_write_pattern(1);
    full.show_page(0);
    check_true(eq(fc.writes().back(), {0x60, 0x72, 0x00, 0x10}), "write_pattern_1_page0_address");
    check_true(mark(fd) - fd0 == 8, "show_page_8_chunks");
    full.set_write_pattern(0);

    size_t before = mark(fd);
    full.show_page(16);
    check_true(mark(fd) == before, "show_page_out_of_range_ignored");

    // raw access
    const uint8_t raw[] = {0xE3, 0xE3};
    full.send_command(raw, 2);
    check_true(eq(fc.writes().back(), {0xE3, 0xE3}), "send_command_raw");
    uint8_t big[70] = {0};
    size_t db = mark(fd);
    full.send_data(big, sizeof(big));
    check_true(mark(fd) - db == 3 && fd.writes().back().size() == 6, "send_data_chunked_32_32_6");

    // reset: System Reset in its own transaction, configuration, enable
    k = mark(fc);
    full.reset();
    check_true(fc.writes().size() - k == 3 && eq(fc.writes()[k], {0xE2}) && eq(fc.writes()[k + 2], {0xA9}), "reset_sequence");
    check_true(eq(fc.writes()[k + 1], {0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F,
                                        0x81, 0x55, 0x89, 0xD1}), "reset_keeps_contrast_and_mapping");

    // framebuffer drawing
    full.fill(true);
    check_true(full.get_pixel(0, 0) && full.get_pixel(239, 127), "fill_on");
    full.fill(false);
    check_true(!full.get_pixel(0, 0) && !full.get_pixel(239, 127), "fill_off");
    check_true(!full.get_pixel(-1, 0) && !full.get_pixel(0, 128), "get_pixel_out_of_range_false");
    full.draw_line(0, 0, 9, 9);
    check_true(full.get_pixel(0, 0) && full.get_pixel(5, 5) && full.get_pixel(9, 9) && !full.get_pixel(5, 6), "draw_line_diagonal");
    full.fill(false);
    full.draw_rect(10, 20, 5, 4);
    check_true(full.get_pixel(10, 20) && full.get_pixel(14, 23) && full.get_pixel(12, 20) && !full.get_pixel(12, 21), "draw_rect_outline");
    full.fill(false);
    full.draw_rect(10, 20, 3, 3, true, true);
    check_true(full.get_pixel(11, 21) && full.get_pixel(12, 22) && !full.get_pixel(13, 22), "draw_rect_filled");
    full.fill(false);
    const uint8_t bmp[] = {0xFF, 0x0F, 0x01, 0x80};   // 2 columns x 2 page rows
    full.blit(4, 8, bmp, sizeof(bmp), 2);
    check_true(full.get_pixel(4, 8) && full.get_pixel(4, 15) && full.get_pixel(5, 8) && !full.get_pixel(5, 12) &&
               full.get_pixel(5, 11) && full.get_pixel(4, 16) && full.get_pixel(5, 23) && !full.get_pixel(4, 17),
               "blit_two_page_rows");
    full.fill(false);
    full.blit(4, 3, bmp, 2, 1);   // y not a multiple of 8: ignored
    check_true(!full.get_pixel(4, 3) && !full.get_pixel(4, 8), "blit_unaligned_y_ignored");

    // reads
    I2CConnectionMock rc, rd;
    UC1611sFull rd_chip(rc, rd);
    rc.queueRead({0x80 | 0x40, 0x20 | 0x05, 0x10});
    uint8_t status[3] = {0, 0, 0};
    rd_chip.read_status(status);
    check_true(status[0] == 0xC0 && status[1] == 0x25 && status[2] == 0x10, "read_status_three_bytes");
    rd.queueRead({0xEE, 0x11, 0x22, 0x33});
    uint8_t ram[3] = {0, 0, 0};
    size_t rk = mark(rc);
    rd_chip.read_ram(2, 5, ram, 3);
    check_true(ram[0] == 0x11 && ram[1] == 0x22 && ram[2] == 0x33, "read_ram_discards_dummy");
    check_true(eq(rc.writes()[rk], {0x62, 0x70, 0x05, 0x10}), "read_ram_sets_address");

    // top view and contrast options
    I2CConnectionMock tc, td;
    UC1611sFull top(tc, td, 240, 128, true, 0x60);
    check_true(eq(tc.writes()[1], {0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F,
                                    0x81, 0x60, 0x89, 0xD1}), "top_view_and_contrast_option");

    // smaller panel
    I2CConnectionMock sc, sd;
    UC1611sMinimal small(sc, sd, 100, 64);
    check_true(small.valid() && eq(sc.writes()[1], {0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F,
                                                    0x81, 0x8F, 0x89, 0xD1}), "custom_height_com_end");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
