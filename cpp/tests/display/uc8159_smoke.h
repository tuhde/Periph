#pragma once
// Shared body of the UC8159 hardware-in-loop tests (one app per platform).
//
// Needs an UC8159 panel on the connection: show() waits for the real refresh
// and fails when BUSY never reports ready.
#include <stdint.h>

/** @brief Run the UC8159 checks on an UC8159Full instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc8159Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());

    chip.clear();
    chip.set_pixel(1, 1, CHIP::BLACK);
    report("pixel_black_readback", chip.get_pixel(1, 1) == CHIP::BLACK);
    chip.set_pixel(3, 1, CHIP::RED);
    report("pixel_red_readback", chip.get_pixel(3, 1) == CHIP::RED);
    chip.set_pixel(4, 1, CHIP::ORANGE);
    report("pixel_orange_readback", chip.get_pixel(4, 1) == CHIP::ORANGE);
    chip.draw_rect(0, 0, 600, 448, CHIP::BLACK);
    chip.draw_line(0, 0, 599, 447, CHIP::BLACK);
    report("drawing_accepted", chip.get_pixel(0, 0) == CHIP::BLACK);

    report("show_returns_true", chip.show());
    report("valid_after_show", chip.valid());
    report("sleep_accepted", chip.sleep());
}
