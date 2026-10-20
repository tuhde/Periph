#pragma once
// Shared body of the UC1611s hardware-in-loop tests (one app per platform).
//
// Over I²C the chip can be read back, so the checks go beyond "accepted": the status bytes
// (version, display enable, mirror bits, product code) and a RAM read-back of what show() wrote.
// The picture itself is verified by eye (see the demo example).
#include <stdint.h>

/** @brief Run the UC1611s checks on a UC1611sFull instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc1611sChecks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_240x128", chip.width() == 240 && chip.height() == 128);

    uint8_t s[3] = {0, 0, 0};
    chip.read_status(s);
    report("status_version_bit", (s[0] & 0x80) != 0);
    report("status_product_code_1", (s[2] >> 4) == 1);
    report("status_display_enabled", (s[0] & 0x08) != 0);
    report("status_mirror_x_bottom_view", (s[0] & 0x40) != 0 && (s[0] & 0x20) == 0);

    chip.sleep();
    chip.read_status(s);
    report("sleep_clears_display_enable", (s[0] & 0x08) == 0);
    chip.wake();
    chip.read_status(s);
    report("wake_sets_display_enable", (s[0] & 0x08) != 0);

    chip.set_flip(false, true);
    chip.read_status(s);
    report("flip_my_status", (s[0] & 0x40) == 0 && (s[0] & 0x20) != 0);
    chip.set_flip(true, false);
    chip.read_status(s);
    report("flip_mx_status", (s[0] & 0x40) != 0 && (s[0] & 0x20) == 0);

    // RAM read-back: draw, show, read page 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7);
    chip.draw_line(0, 31, 31, 24);
    chip.draw_rect(40, 4, 20, 20);
    chip.show();
    uint8_t ram[32];
    bool match0 = true, match3 = true;
    for (int half = 0; half < 2; half++) {
        chip.read_ram(0, (uint8_t)(half * 32), ram, 32);
        for (int x = 0; x < 32; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(half * 32 + x, b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match0 = false;
        }
        chip.read_ram(3, (uint8_t)(half * 32), ram, 32);
        for (int x = 0; x < 32; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(half * 32 + x, 24 + b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match3 = false;
        }
    }
    report("ram_readback_page0", match0);
    report("ram_readback_page3", match3);

    chip.set_contrast(0x70);
    chip.set_contrast(0x8F);
    chip.set_invert(true);
    chip.set_invert(false);
    chip.set_all_on(true);
    chip.set_all_on(false);
    chip.set_scroll_line(16);
    chip.set_scroll_line(0);
    chip.set_fixed_lines(0);
    chip.set_bias_ratio(2);
    chip.set_temperature_compensation(1);
    chip.set_line_rate(3);
    chip.set_pump(true);
    chip.set_panel_loading(true);
    chip.set_partial_display(true, 0, 127);
    chip.set_partial_display(false);
    chip.set_nline_inversion(0);
    chip.set_isolation_clock(1, 0);
    chip.set_write_pattern(1);
    chip.show_page(0);
    chip.set_write_pattern(0);
    chip.set_display_pattern(1);
    chip.set_display_pattern(0);
    chip.show();
    report("valid_after_commands", chip.valid());

    chip.reset();
    chip.read_status(s);
    report("reset_restores_display_enable", (s[0] & 0x08) != 0);
}
