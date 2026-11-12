#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "DisplayConnectionMock.h"
#include "SSD1306.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static bool eq(const Bytes& a, std::initializer_list<uint8_t> b) {
    return a == Bytes(b);
}

static size_t mark(const I2CConnectionMock& c) { return c.writes().size(); }
static const Bytes& last(const I2CConnectionMock& c) { return c.writes().back(); }

int main() {
    // ---- Minimal: initialization (I²C) ------------------------------------------------
    I2CConnectionMock c;
    SSD1306Minimal lcd(c);
    check_true(lcd.valid() && lcd.width() == 128 && lcd.height() == 64, "geometry_128x64");
    const Bytes setup = {0x00, 0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02, 0xA0, 0xC0,
                         0xDA, 0x12, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6};
    check_true(c.writes()[0] == setup, "init_setup_stream_charge_pump_on");
    check_true(eq(c.writes()[1], {0x00, 0xB0, 0x00, 0x10}), "init_clear_page0_address");
    bool chunks_ok = true;
    size_t expect[5] = {31, 31, 31, 31, 4};
    for (size_t i = 0; i < 5; i++) {
        const Bytes& w = c.writes()[2 + i];
        if (w[0] != 0x40 || w.size() - 1 != expect[i]) chunks_ok = false;
        for (size_t k = 1; k < w.size(); k++) if (w[k] != 0) chunks_ok = false;
    }
    check_true(chunks_ok, "init_clear_data_chunks_31x4_4");
    check_true(c.writes().size() == 1 + 8 * 6 + 1, "init_clears_8_pages");
    check_true(eq(c.writes()[1 + 7 * 6], {0x00, 0xB7, 0x00, 0x10}), "init_page7_address");
    check_true(eq(last(c), {0x00, 0xAF}), "init_display_on_last");

    // ---- Minimal: pixels and show -----------------------------------------------------
    size_t n0 = mark(c);
    lcd.set_pixel(3, 10);
    lcd.set_pixel(127, 63);
    lcd.set_pixel(128, 0);      // out of range, ignored
    lcd.set_pixel(-1, 5);
    check_true(mark(c) == n0, "set_pixel_framebuffer_only");
    check_true(lcd.show(), "show_returns_true");
    check_true(c.writes().size() - n0 == 48, "show_8_pages_6_writes");
    check_true(eq(c.writes()[n0 + 6], {0x00, 0xB1, 0x00, 0x10}), "show_page1_address_column0");
    check_true(c.writes()[n0 + 7][1 + 3] == 0x04, "show_pixel_page1_col3_bit2");
    check_true(c.writes()[n0 + 47].back() == 0x80, "show_pixel_page7_col127_bit7");
    lcd.set_pixel(3, 10, false);
    lcd.clear();
    n0 = mark(c);
    lcd.show();
    bool zero = true;
    for (size_t i = n0; i < c.writes().size(); i++)
        if (c.writes()[i][0] == 0x40) for (size_t k = 1; k < c.writes()[i].size(); k++) if (c.writes()[i][k]) zero = false;
    check_true(zero, "clear_zeroes_framebuffer");

    // ---- Minimal: options and validation ----------------------------------------------
    I2CConnectionMock o;
    SSD1306Minimal ext(o, 128, 32, true);
    check_true(o.writes()[0][9] == 0x8D && o.writes()[0][10] == 0x10, "external_vcc_pump_off");
    check_true(o.writes()[0][15] == 0xDA && o.writes()[0][16] == 0x02, "height_32_sequential_com");
    check_true(o.writes()[0][4] == 0xA8 && o.writes()[0][5] == 0x1F, "custom_height_multiplex");
    I2CConnectionMock bad;
    check_true(!SSD1306Minimal(bad, 129, 64).valid(), "invalid_width");
    check_true(!SSD1306Minimal(bad, 0, 64).valid(), "invalid_width_zero");
    check_true(!SSD1306Minimal(bad, 128, 60).valid(), "invalid_height_multiple");
    check_true(!SSD1306Minimal(bad, 128, 8).valid(), "invalid_height_min");
    check_true(!SSD1306Minimal(bad, 128, 72).valid(), "invalid_height_max");
    check_true(bad.writes().empty(), "invalid_arguments_no_bus_traffic");

    // ---- Full -------------------------------------------------------------------------
    I2CConnectionMock fc;
    SSD1306Full full(fc);
    full.set_contrast(0x55);
    check_true(eq(last(fc), {0x00, 0x81, 0x55}), "set_contrast");
    full.set_display(false);
    check_true(eq(last(fc), {0x00, 0xAE}), "display_off");
    full.set_display(true);
    check_true(eq(last(fc), {0x00, 0xAF}), "display_on");
    full.sleep();
    check_true(eq(last(fc), {0x00, 0xAE}), "sleep");
    full.wake();
    check_true(eq(last(fc), {0x00, 0xAF}), "wake");
    full.set_invert(true);
    check_true(eq(last(fc), {0x00, 0xA7}), "invert_on");
    full.set_invert(false);
    check_true(eq(last(fc), {0x00, 0xA6}), "invert_off");
    full.set_all_on(true);
    check_true(eq(last(fc), {0x00, 0xA5}), "all_on");
    full.set_all_on(false);
    check_true(eq(last(fc), {0x00, 0xA4}), "all_on_off");
    full.set_flip(true, true);
    check_true(eq(last(fc), {0x00, 0xA1, 0xC8}), "flip_both");
    full.set_flip(false, true);
    check_true(eq(last(fc), {0x00, 0xA0, 0xC8}), "flip_vertical_only");
    full.set_flip(false, false);
    check_true(eq(last(fc), {0x00, 0xA0, 0xC0}), "flip_none");
    full.set_start_line(40);
    check_true(eq(last(fc), {0x00, 0x68}), "start_line_40");
    full.set_display_offset(12);
    check_true(eq(last(fc), {0x00, 0xD3, 12}), "display_offset");
    full.set_multiplex(48);
    check_true(eq(last(fc), {0x00, 0xA8, 47}), "multiplex_48");
    full.set_clock(3, 8);
    check_true(eq(last(fc), {0x00, 0xD5, 0x82}), "clock");
    full.set_precharge(2, 5);
    check_true(eq(last(fc), {0x00, 0xD9, 0x52}), "precharge");
    full.set_com_config(false);
    check_true(eq(last(fc), {0x00, 0xDA, 0x02}), "com_sequential");
    full.set_com_config(true);
    check_true(eq(last(fc), {0x00, 0xDA, 0x12}), "com_alternative");
    full.set_com_config(true, true);
    check_true(eq(last(fc), {0x00, 0xDA, 0x32}), "com_alternative_remap");
    full.set_vcomh(0xFF);
    check_true(eq(last(fc), {0x00, 0xDB, 0x70}), "vcomh_masked");
    full.set_charge_pump(true);
    check_true(eq(last(fc), {0x00, 0x8D, 0x14}), "charge_pump_on");
    full.set_charge_pump(false);
    check_true(eq(last(fc), {0x00, 0x8D, 0x10}), "charge_pump_off");
    full.set_scroll_horizontal(true, 0, 7, 7);
    check_true(eq(last(fc), {0x00, 0x26, 0x00, 0, 7, 7, 0x00, 0xFF}), "scroll_h_right");
    full.set_scroll_horizontal(false, 1, 3, 4);
    check_true(eq(last(fc), {0x00, 0x27, 0x00, 1, 4, 3, 0x00, 0xFF}), "scroll_h_left");
    full.set_scroll_vertical(true, 0, 7, 6, 5);
    check_true(eq(last(fc), {0x00, 0x29, 0x00, 0, 6, 7, 5}), "scroll_diagonal");
    full.set_scroll_area(8, 40);
    check_true(eq(last(fc), {0x00, 0xA3, 8, 40}), "scroll_area");
    const uint8_t raw[2] = {0xE3, 0xE3};
    full.send_command(raw, 2);
    check_true(eq(last(fc), {0x00, 0xE3, 0xE3}), "send_command_raw");

    // ---- scrolling blocks RAM access --------------------------------------------------
    full.scroll_start();
    check_true(eq(last(fc), {0x00, 0x2F}), "scroll_start");
    n0 = mark(fc);
    check_true(!full.show() && !full.show_page(0), "show_refused_while_scrolling");
    check_true(mark(fc) == n0, "no_bus_traffic_when_refused");
    full.scroll_stop();
    check_true(eq(fc.writes()[n0], {0x00, 0x2E}) && fc.writes().size() - n0 == 1 + 48, "scroll_stop_deactivates_and_rewrites_ram");
    n0 = mark(fc);
    check_true(full.show() && fc.writes().size() - n0 == 48, "show_works_after_scroll_stop");

    // ---- addressing modes -------------------------------------------------------------
    full.set_addressing_mode(SSD1306Minimal::ADDR_HORIZONTAL);
    check_true(eq(last(fc), {0x00, 0x20, 0x00}), "addr_mode_horizontal");
    n0 = mark(fc);
    full.show();
    check_true(eq(fc.writes()[n0], {0x00, 0x21, 0, 127, 0x22, 0, 7}), "show_horizontal_ranges");
    size_t total = 0;
    for (size_t i = n0 + 1; i < fc.writes().size(); i++) total += fc.writes()[i].size() - 1;
    check_true(total == 128 * 8, "show_horizontal_data_stream");
    full.set_addressing_mode(SSD1306Minimal::ADDR_VERTICAL);
    check_true(eq(last(fc), {0x00, 0x20, 0x01}), "addr_mode_vertical");
    full.set_pixel(0, 9);
    n0 = mark(fc);
    full.show();
    check_true(fc.writes()[n0 + 1][1] == 0x00 && fc.writes()[n0 + 1][2] == 0x02, "show_vertical_column_major");
    full.set_pixel(0, 9, false);
    n0 = mark(fc);
    full.show_page(2);
    check_true(eq(fc.writes()[n0], {0x00, 0x21, 0, 127, 0x22, 2, 2}), "show_page_vertical_range");
    full.set_addressing_mode(SSD1306Minimal::ADDR_PAGE);
    check_true(eq(last(fc), {0x00, 0x20, 0x02}), "addr_mode_page");
    n0 = mark(fc);
    full.show_page(2);
    check_true(eq(fc.writes()[n0], {0x00, 0xB2, 0x00, 0x10}) && fc.writes().size() - n0 == 6, "show_page_2");
    full.show_page(8);
    check_true(fc.writes().size() - n0 == 6, "show_page_out_of_range_ignored");

    // ---- framebuffer drawing ----------------------------------------------------------
    full.fill(true);
    check_true(full.get_pixel(0, 0) && full.get_pixel(127, 63), "fill_on");
    full.fill(false);
    check_true(!full.get_pixel(0, 0) && !full.get_pixel(127, 63), "fill_off");
    check_true(!full.get_pixel(-1, 0) && !full.get_pixel(0, 64), "get_pixel_out_of_range_false");
    full.draw_line(0, 0, 9, 9);
    check_true(full.get_pixel(0, 0) && full.get_pixel(5, 5) && full.get_pixel(9, 9) && !full.get_pixel(5, 6), "draw_line_diagonal");
    full.fill(false);
    full.draw_rect(10, 20, 5, 4);
    check_true(full.get_pixel(10, 20) && full.get_pixel(14, 23) && full.get_pixel(12, 20) && !full.get_pixel(12, 21), "draw_rect_outline");
    full.fill(false);
    full.draw_rect(10, 20, 3, 3, true, true);
    check_true(full.get_pixel(11, 21) && full.get_pixel(12, 22) && !full.get_pixel(13, 22), "draw_rect_filled");
    full.fill(false);
    const uint8_t bmp[4] = {0xFF, 0x0F, 0x01, 0x80};
    check_true(full.blit(4, 8, bmp, 4, 2), "blit_returns_true");
    check_true(full.get_pixel(4, 8) && full.get_pixel(4, 15) && full.get_pixel(5, 8) && !full.get_pixel(5, 12) &&
               full.get_pixel(5, 11) && full.get_pixel(4, 16) && full.get_pixel(5, 23) && !full.get_pixel(4, 17), "blit_two_page_rows");
    check_true(!full.blit(4, 3, bmp, 2), "blit_unaligned_y_rejected");

    // ---- SPI --------------------------------------------------------------------------
    DisplayConnectionMock sm;
    SSD1306SpiMinimal<DisplayConnectionMock> spi(sm);
    Bytes cmds;
    size_t datas = 0;
    bool data_ok = true;
    for (const auto& e : sm.events()) {
        if (e.kind == DisplayConnectionMock::Event::kCommand) cmds.push_back(e.cmd);
        if (e.kind == DisplayConnectionMock::Event::kData) { datas++; if (e.bytes.size() != 128) data_ok = false; }
    }
    check_true(sm.events()[0].kind == DisplayConnectionMock::Event::kReset, "spi_reset_first");
    check_true(cmds.size() >= 24 && Bytes(cmds.begin(), cmds.begin() + 24) == Bytes(setup.begin() + 1, setup.end()), "spi_setup_stream");
    check_true(cmds.back() == 0xAF, "spi_display_on_last_command");
    check_true(datas == 8 && data_ok, "spi_init_clear_8_pages_128_bytes");
    size_t e0 = sm.events().size();
    spi.set_pixel(5, 0);
    spi.show();
    check_true(sm.events()[e0].cmd == 0xB0 && sm.events()[e0 + 1].cmd == 0x00 && sm.events()[e0 + 2].cmd == 0x10, "spi_show_page0_address");
    check_true(sm.events()[e0 + 3].kind == DisplayConnectionMock::Event::kData && sm.events()[e0 + 3].bytes.size() == 128 &&
               sm.events()[e0 + 3].bytes[5] == 1, "spi_show_page_data_128_bytes");
    DisplayConnectionMock sm2;
    SSD1306SpiFull<DisplayConnectionMock> sf(sm2);
    sf.set_contrast(0x20);
    const auto& ev = sm2.events();
    check_true(ev[ev.size() - 2].cmd == 0x81 && ev[ev.size() - 1].cmd == 0x20, "spi_full_contrast");
    sf.scroll_start();
    check_true(!sf.show(), "spi_show_refused_while_scrolling");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
