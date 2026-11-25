#pragma once
// Shared body of the IL0373 hardware-in-loop tests (one app per platform).
//
// Needs an IL0373 panel on the connection: show() waits for the real refresh
// and fails when BUSY never reports ready.
#include <stdint.h>

/** @brief Run the IL0373 checks on an IL0373Full instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void il0373Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());

    chip.clear();
    chip.set_pixel(1, 1, CHIP::BLACK);
    report("pixel_black_readback", chip.get_pixel(1, 1) == CHIP::BLACK);
    chip.set_pixel(3, 1, CHIP::WHITE);
    report("pixel_white_readback", chip.get_pixel(3, 1) == CHIP::WHITE);
    chip.draw_rect(0, 0, chip.width(), chip.height(), CHIP::BLACK);
    chip.draw_line(0, 0, chip.width() - 1, chip.height() - 1, CHIP::BLACK);
    report("drawing_accepted", chip.get_pixel(0, 0) == CHIP::BLACK);

    report("show_returns_true", chip.show());
    report("valid_after_show", chip.valid());
    report("sleep_accepted", chip.sleep());
}
