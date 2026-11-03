#pragma once
// Shared body of the SSD1331 hardware-in-loop tests (one app per platform).
//
// The controller is write-only (no read-back, no BUSY): the checks cover the framebuffer
// round trip and that every command sequence is accepted without the driver going invalid.
// The picture itself is verified by eye (see the demo example).
#include <stdint.h>

/** @brief Run the SSD1331 checks on an SSD1331Full instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void ssd1331Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_96x64", chip.width() == 96 && chip.height() == 64);

    chip.clear(CHIP::BLUE);
    report("clear_readback", chip.get_pixel(0, 0) == CHIP::BLUE && chip.get_pixel(95, 63) == CHIP::BLUE);
    chip.set_pixel(5, 6, CHIP::RED);
    report("pixel_readback", chip.get_pixel(5, 6) == CHIP::RED);
    chip.set_pixel(200, 6, CHIP::WHITE);
    report("pixel_out_of_range_ignored", chip.get_pixel(200, 6) == 0);
    chip.draw_rect(10, 10, 20, 10, CHIP::GREEN, true);
    report("draw_rect_fill", chip.get_pixel(15, 15) == CHIP::GREEN);
    chip.draw_line(0, 0, 95, 63, CHIP::WHITE);
    report("draw_line_endpoints", chip.get_pixel(0, 0) == CHIP::WHITE && chip.get_pixel(95, 63) == CHIP::WHITE);

    chip.show();
    chip.show_region(8, 8, 32, 16);
    chip.hw_clear(0, 0, 95, 63);
    chip.hw_rect(10, 10, 40, 30, CHIP::YELLOW, CHIP::RED, true);
    chip.hw_line(0, 0, 95, 63, CHIP::WHITE);
    chip.hw_copy(10, 10, 30, 30, 50, 20);
    chip.hw_dim(0, 0, 47, 63);
    chip.set_contrast(0x80, 0x80, 0x80);
    chip.set_master_current(7);
    chip.set_display_mode(CHIP::MODE_INVERSE);
    chip.set_display_mode(CHIP::MODE_NORMAL);
    chip.set_flip(false, false);
    chip.scroll(1, 0, 64, 0, 0);
    chip.scroll_stop();
    chip.display_off();
    chip.display_on();
    report("valid_after_commands", chip.valid());
}
