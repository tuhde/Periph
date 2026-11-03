#pragma once
// Shared body of the UC8276C hardware-in-loop tests (one app per platform).
//
// Needs a UC8276C panel on the connection: show() waits for the real refresh
// (about 17 s at 400 x 300) and fails when BUSY_N never reports ready.
#include <stdint.h>

/** @brief Run the UC8276C checks on a UC8276CFull instance.
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void uc8276cChecks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());

    chip.clear();
    chip.set_pixel(1, 1, CHIP::BLACK);
    report("pixel_black_readback", chip.get_pixel(1, 1) == CHIP::BLACK);
    chip.set_pixel(3, 1, CHIP::RED);
    report("pixel_red_readback", chip.get_pixel(3, 1) == (chip.has_red() ? CHIP::RED : CHIP::BLACK));
    chip.draw_rect(0, 0, chip.width(), chip.height(), CHIP::BLACK);
    chip.draw_line(0, 0, chip.width() - 1, chip.height() - 1, CHIP::BLACK);
    report("drawing_accepted", chip.get_pixel(0, 0) == CHIP::BLACK);

    report("show_returns_true", chip.show());
    report("valid_after_show", chip.valid());
    report("sleep_accepted", chip.sleep());
}
