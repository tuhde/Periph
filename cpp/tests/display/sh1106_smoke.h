#pragma once
// Shared body of the SH1106 hardware-in-loop tests (one app per platform).
//
// Over I²C the chip can be read back, so the checks go beyond "accepted": the status byte
// (BUSY, display on/off) and a RAM read-back of what show() wrote. The picture itself is
// verified by eye (see the demo example).
#include <stdint.h>

/** @brief Run the SH1106 checks on an SH1106Full instance (I²C: the chip can be read back).
 *  @param chip   Driver instance, already constructed.
 *  @param report Callable (const char* label, bool ok).
 */
template <typename CHIP, typename REPORT>
void sh1106Checks(CHIP& chip, REPORT report) {
    report("init_valid", chip.valid());
    report("geometry_128x64", chip.width() == 128 && chip.height() == 64);

    uint8_t s = 0xFF;
    bool ok = chip.read_status(s);
    report("status_read", ok);
    report("status_not_busy", (s & 0x80) == 0);
    report("status_display_on", (s & 0x40) == 0);

    chip.sleep();
    chip.read_status(s);
    report("sleep_sets_off_bit", (s & 0x40) != 0);
    chip.wake();
    chip.read_status(s);
    report("wake_clears_off_bit", (s & 0x40) == 0);

    // RAM read-back: draw, show, read page 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.draw_line(0, 0, 31, 7);
    chip.draw_line(0, 31, 31, 24);
    chip.draw_rect(40, 4, 20, 20);
    chip.show();
    uint8_t ram[16];
    bool match0 = true, match3 = true;
    for (int part = 0; part < 4; part++) {
        chip.read_page(0, (uint8_t)(2 + part * 16), ram, 16);
        for (int x = 0; x < 16; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(part * 16 + x, b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match0 = false;
        }
        chip.read_page(3, (uint8_t)(2 + part * 16), ram, 16);
        for (int x = 0; x < 16; x++) {
            uint8_t expected = 0;
            for (int b = 0; b < 8; b++) if (chip.get_pixel(part * 16 + x, 24 + b)) expected |= (uint8_t)(1 << b);
            if (ram[x] != expected) match3 = false;
        }
    }
    report("ram_readback_page0", match0);
    report("ram_readback_page3", match3);

    chip.set_contrast(0x40);
    chip.set_contrast(0x80);
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
    chip.set_clock(1, 5);
    chip.set_precharge(2, 2);
    chip.set_com_config(true);
    chip.set_vcom_deselect(0x35);
    chip.set_pump_voltage(0);
    chip.show_page(0);
    chip.show();
    chip.read_status(s);
    report("commands_accepted", chip.valid() && (s & 0x80) == 0);
}
