#pragma once
// Shared body of the SSD1306 hardware-in-loop tests (one app per platform).
//
// The SSD1306 cannot be read over its serial interfaces, so the checks cover the framebuffer and
// that every command is accepted; the picture itself is verified by eye (see the demo example).
#include <stdint.h>

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
