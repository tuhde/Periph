#pragma once
// Shared body of the UC1611s 4-wire SPI hardware-in-loop tests (one app per platform).
//
// The module is write-only over SPI (no read-back, no acknowledge): the checks cover the geometry,
// that every command sequence is accepted without the driver going invalid, and that the reads
// report "unsupported". The picture itself is verified by eye (see the demo example).
#include <stdint.h>

/** @brief Run the UC1611s SPI checks on a UC1611sSpiFull instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc1611sSpiChecks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_240x128", chip.width() == 240 && chip.height() == 128);

    chip.clear();
    chip.draw_rect(0, 0, 240, 128);
    chip.draw_line(0, 0, 239, 127);
    chip.draw_rect(20, 20, 40, 30, true, true);
    chip.show();
    report("valid_after_show", chip.valid() && chip.ok());

    chip.show_page(3);
    chip.set_contrast(0x70);
    chip.set_contrast(0x8F);
    chip.set_invert(true);
    chip.set_invert(false);
    chip.set_all_on(true);
    chip.set_all_on(false);
    chip.set_flip(false, true);
    chip.set_flip(true, false);
    chip.set_fixed_lines(2);
    chip.set_scroll_line(8);
    chip.set_scroll_line(0);
    chip.set_fixed_lines(0);
    chip.set_bias_ratio(2);
    chip.set_temperature_compensation(1);
    chip.set_line_rate(3);
    chip.set_pump(true);
    chip.set_panel_loading(true);
    chip.set_nline_inversion(0);
    chip.set_isolation_clock(1, 0);
    chip.set_partial_display(true, 32, 95);
    chip.set_partial_display(false);
    chip.set_write_pattern(1);
    chip.show();
    chip.set_display_pattern(1);
    chip.set_display_pattern(0);
    chip.set_write_pattern(0);
    report("valid_after_commands", chip.valid() && chip.ok());

    chip.sleep();
    chip.wake();
    chip.reset();
    chip.show();
    report("valid_after_sleep_wake_reset", chip.valid() && chip.ok());

    uint8_t status[3] = {1, 2, 3};
    uint8_t ram[4] = {1, 2, 3, 4};
    report("read_status_unsupported", !chip.read_status(status) && status[0] == 0);
    report("read_ram_unsupported", !chip.read_ram(0, 0, ram, 4) && ram[0] == 0);
    report("no_retries_or_errors", chip.retry_count() == 0 && chip.error_count() == 0);
}
