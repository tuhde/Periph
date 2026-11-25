#pragma once
// Shared body of the ST7036 hardware-in-loop tests (one app per platform).
//
// The controller is write-only in serial mode (no read-back, no busy flag): the checks cover the
// geometry and that every command sequence is accepted without the driver going invalid.
// The picture itself is verified by eye (see the demo example).
#include <stdint.h>

/** @brief Run the ST7036 checks on an ST7036Full instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void st7036Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_16x2", chip.columns() == 16 && chip.rows() == 2);

    chip.clear();
    chip.print_at(0, 0, "Periph ST7036");
    chip.print_at(0, 1, "smoke test");
    report("valid_after_text", chip.valid());

    uint8_t glyph[8] = {0x1F, 0x11, 0x15, 0x11, 0x1F, 0x00, 0x1F, 0x00};
    chip.create_char(0, glyph);
    chip.set_cursor(15, 1);
    chip.write_char(0);
    chip.set_cursor(0, 5);   // out of range: clamped, must not break the driver
    report("valid_after_custom_char", chip.valid());

    chip.cursor(true, true);
    chip.cursor(false, false);
    chip.set_entry_mode(true, false);
    chip.shift_display(true);
    chip.shift_display(false);
    chip.shift_cursor(true);
    chip.return_home();
    chip.set_ddram_address(0x40);
    chip.set_contrast(40);
    chip.set_booster(true);
    chip.set_follower(true, 5);
    chip.set_bias(true);
    chip.set_contrast(24);
    chip.display(false);
    chip.display(true);
    chip.home();
    report("valid_after_commands", chip.valid());
}
