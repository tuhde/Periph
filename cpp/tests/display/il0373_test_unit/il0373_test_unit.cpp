// Unit test for the IL0373 driver against DisplayConnectionMock — no hardware.
#include <stdio.h>
#include <stdint.h>
#include <string>
#include <vector>
#include "DisplayConnectionMock.h"
#include "IL0373.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

typedef DisplayConnectionMock::Event Event;

// Compact trace of the connection events: R reset, Cxx command, Dn data of n bytes,
// W<0|1> waitBusy(active level), rn read of n bytes.
static std::string trace(const DisplayConnectionMock& m, size_t from = 0) {
    static const char* hex = "0123456789ABCDEF";
    std::string s;
    const std::vector<Event>& ev = m.events();
    for (size_t i = from; i < ev.size(); i++) {
        if (!s.empty()) s += ' ';
        const Event& e = ev[i];
        switch (e.kind) {
            case Event::kReset:   s += "R"; break;
            case Event::kCommand: s += "C"; s += hex[e.cmd >> 4]; s += hex[e.cmd & 15]; break;
            case Event::kData:    s += "D" + std::to_string(e.bytes.size()); break;
            case Event::kWaitBusy: s += e.busyActiveHigh ? "W1" : "W0"; break;
            case Event::kRead:    s += "r" + std::to_string(e.length); break;
        }
    }
    return s;
}

// First command event with the given opcode at or after `from`; nullptr if none.
static const Event* findCommand(const DisplayConnectionMock& m, uint8_t cmd, size_t from = 0) {
    const std::vector<Event>& ev = m.events();
    for (size_t i = from; i < ev.size(); i++)
        if (ev[i].kind == Event::kCommand && ev[i].cmd == cmd) return &ev[i];
    return nullptr;
}

static bool bytesEq(const std::vector<uint8_t>& got, std::initializer_list<uint8_t> want) {
    if (got.size() != want.size()) return false;
    size_t i = 0;
    for (uint8_t b : want) if (got[i++] != b) return false;
    return true;
}

typedef IL0373Minimal<DisplayConnectionMock> Mini;
typedef IL0373Full<DisplayConnectionMock> Full;

int main() {
    // ---- construction on the default 104 x 212 panel ---------------------------------------
    {
        static uint8_t fb[2756];
        DisplayConnectionMock m;
        Mini chip(m, fb);
        check_true(chip.valid(), "init_valid");
        check_true(trace(m) == "R W0 C06 C04 W0 C00 C61 C50", "init_sequence_vendor_otp_flow");
        check_true(bytesEq(m.events()[2].bytes, {0x17, 0x17, 0x17}), "init_btst_17_17_17");
        check_true(bytesEq(m.events()[5].bytes, {0x1F}), "init_psr_0x1F");
        check_true(bytesEq(m.events()[6].bytes, {0x68, 0x00, 0xD4}), "init_tres_104x212");
        check_true(bytesEq(m.events()[7].bytes, {0x97}), "init_cdi_0x97");
        check_true(m.events()[1].timeoutMs == 30000 && !m.events()[1].busyActiveHigh, "init_busy_wait_active_low");
        check_true(chip.width() == 104 && chip.height() == 212, "init_geometry");
        check_true(fb[0] == 0xFF && fb[2755] == 0xFF, "init_clears_to_white");
        check_true(Mini::buffer_size(104, 212) == 2756, "buffer_size_104x212");
    }
    // ---- invalid configurations --------------------------------------------------------------
    {
        uint8_t buf[64];
        DisplayConnectionMock m;
        Mini nobuf(m, nullptr);
        check_true(!nobuf.valid(), "null_buffer_invalid");
        Mini badw(m, buf, 100, 8);
        check_true(!badw.valid(), "width_not_multiple_of_8_invalid");
        Mini wide(m, buf, 168, 8);
        check_true(!wide.valid(), "width_over_160_invalid");
        Mini tall(m, buf, 16, 297);
        check_true(!tall.valid(), "height_over_296_invalid");
        Mini zero(m, buf, 16, 0);
        check_true(!zero.valid(), "height_zero_invalid");
        check_true(!nobuf.show(), "invalid_show_false");
        check_true(!nobuf.sleep(), "invalid_sleep_false");
    }
    // ---- framebuffer mapping (16 x 8) ----------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Full chip(m, fb, 16, 8);
        chip.set_pixel(0, 0, Full::BLACK);
        check_true(fb[0] == 0x7F, "black_clears_bit7_of_first_pixel");
        chip.set_pixel(9, 1, Full::BLACK);
        check_true((fb[3] & 0x40) == 0, "black_pixel_9_1");
        chip.set_pixel(9, 1, Full::WHITE);
        check_true((fb[3] & 0x40) == 0x40, "white_sets_bit");
        check_true(chip.get_pixel(0, 0) == Full::BLACK && chip.get_pixel(9, 1) == Full::WHITE
                   && chip.get_pixel(1, 0) == Full::WHITE, "get_pixel_roundtrip");
        uint8_t before = fb[0];
        chip.set_pixel(-1, 0); chip.set_pixel(0, -1); chip.set_pixel(16, 0); chip.set_pixel(0, 8);
        check_true(fb[0] == before && chip.get_pixel(99, 99) == Full::BLACK, "out_of_range_ignored");
        chip.clear(Full::BLACK);
        check_true(fb[0] == 0x00 && fb[15] == 0x00, "clear_black");
        chip.clear();
        check_true(fb[15] == 0xFF, "clear_default_white");
    }
    // ---- show ------------------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Mini chip(m, fb, 16, 8);
        chip.set_pixel(0, 0, Mini::BLACK);
        chip.set_pixel(15, 7, Mini::BLACK);
        size_t from = m.events().size();
        bool ok = chip.show();
        check_true(ok, "show_returns_true");
        check_true(trace(m, from) == "C10 D16 C13 D16 C12 W0 C50 C02 W0", "show_sequence_dtm1_dtm2_drf_cdi_pof");
        const std::vector<Event>& ev = m.events();
        check_true(ev[from + 1].bytes[0] == 0x80 && ev[from + 1].bytes[15] == 0x01, "show_old_plane_is_inverted");
        check_true(ev[from + 3].bytes[0] == 0x7F && ev[from + 3].bytes[15] == 0xFE, "show_new_plane_is_framebuffer");
        check_true(bytesEq(ev[from + 6].bytes, {0xF7}), "show_cdi_border_floating");
        check_true(ev[from + 5].timeoutMs == 30000 && !ev[from + 5].busyActiveHigh, "show_waits_busy_low_active");
        // second show: booster is off, so PON + CDI come first
        from = m.events().size();
        chip.show();
        check_true(trace(m, from) == "C04 W0 C50 C10 D16 C13 D16 C12 W0 C50 C02 W0", "second_show_powers_on_first");
        check_true(bytesEq(m.events()[from + 2].bytes, {0x97}), "second_show_cdi_0x97");
    }
    // ---- BUSY timeout ----------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Mini chip(m, fb, 16, 8);
        m.setReady(false);
        check_true(!chip.show(), "show_false_on_busy_timeout");
        check_true(!chip.valid(), "timeout_marks_invalid");
        DisplayConnectionMock m2;
        m2.setReady(false);
        Mini dead(m2, fb, 16, 8);
        check_true(!dead.valid(), "init_busy_timeout_invalid");
    }
    // ---- sleep / wake --------------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Full chip(m, fb, 16, 8);
        size_t from = m.events().size();
        check_true(chip.sleep(), "sleep_true");
        check_true(trace(m, from) == "C50 C02 W0 C07", "sleep_powers_off_then_dslp");
        check_true(bytesEq(m.events()[from + 3].bytes, {0xA5}), "sleep_check_code_a5");
        from = m.events().size();
        check_true(chip.show(), "show_after_sleep");
        check_true(trace(m, from).rfind("R W0 C06", 0) == 0, "show_after_sleep_wakes_first");
        chip.sleep();
        from = m.events().size();
        check_true(chip.wake(), "wake_true");
        check_true(trace(m, from).find("R W0 C06 C04 W0 C00 C61 C50") == 0, "wake_resets_and_reinitialises");
    }
    // ---- data chunking --------------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Full chip(m, fb, 16, 8);
        chip.set_data_chunk(4);
        size_t from = m.events().size();
        chip.show();
        check_true(trace(m, from) == "C10 D4 D4 D4 D4 C13 D4 D4 D4 D4 C12 W0 C50 C02 W0", "chunked_pixel_transfers");
    }
    // ---- Full: settings -----------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Full chip(m, fb, 16, 8);
        auto last = [&](uint8_t cmd) {
            const Event* r = nullptr;
            for (size_t i = 0; i < m.events().size(); i++)
                if (m.events()[i].kind == Event::kCommand && m.events()[i].cmd == cmd) r = &m.events()[i];
            return r;
        };
        chip.set_flip(true, false);
        check_true(bytesEq(last(0x00)->bytes, {0x1B}), "flip_horizontal_clears_shl");
        chip.set_flip(false, true);
        check_true(bytesEq(last(0x00)->bytes, {0x17}), "flip_vertical_clears_ud");
        chip.set_flip(true, true);
        check_true(bytesEq(last(0x00)->bytes, {0x13}), "flip_both");
        chip.set_flip(false, false);
        check_true(bytesEq(last(0x00)->bytes, {0x1F}), "flip_none_restores");

        chip.set_power(14.0f, -14.0f, 20.0f);
        check_true(bytesEq(last(0x01)->bytes, {0x03, 0x00, 0x26, 0x26, 0x03}), "power_defaults");
        chip.set_power(15.0f, 5.0f, 17.0f);
        check_true(bytesEq(last(0x01)->bytes, {0x03, 0x03, 0x2B, 0x00, 0x03}), "power_clamps_and_vgh");
        chip.set_vcom_dc(-1.00f);
        check_true(bytesEq(last(0x82)->bytes, {0x12}), "vcom_dc_minus_1_0_is_0x12");
        chip.set_vcom_dc(0.0f);
        check_true(bytesEq(last(0x82)->bytes, {0x00}), "vcom_dc_clamps_low");
        chip.set_vcom_dc(-9.0f);
        check_true(bytesEq(last(0x82)->bytes, {0x3A}), "vcom_dc_clamps_high");
        chip.set_frame_rate(50);
        check_true(bytesEq(last(0x30)->bytes, {0x3C}), "frame_rate_50hz_is_0x3C");
        chip.set_frame_rate(200);
        check_true(bytesEq(last(0x30)->bytes, {0x39}), "frame_rate_200hz");
        chip.set_soft_start(0x17, 0x27, 0x07);
        check_true(bytesEq(last(0x06)->bytes, {0x17, 0x27, 0x07}), "soft_start");
        chip.set_interval(7);
        check_true(bytesEq(last(0x50)->bytes, {0x97}), "interval_default_is_0x97");
        chip.set_interval(0x0F, 0, 0);
        check_true(bytesEq(last(0x50)->bytes, {0x0F}), "interval_custom");
        chip.set_temperature_sensor(true, -3);
        check_true(bytesEq(last(0x41)->bytes, {0x8D}), "temperature_sensor_external_offset");
        chip.set_temperature_sensor(false);
        check_true(bytesEq(last(0x41)->bytes, {0x00}), "temperature_sensor_internal");

        uint8_t lut[60] = {0};
        check_true(chip.write_lut(0, lut, 44) && last(0x20)->bytes.size() == 44, "lut_c_length_as_given");
        check_true(chip.write_lut(4, lut, 42) && last(0x24)->bytes.size() == 42, "lut_bb_command_0x24");
        check_true(bytesEq(last(0x00)->bytes, {0x3F}), "lut_sets_reg_bit");
        check_true(!chip.write_lut(5, lut, 42) && !chip.write_lut(0, nullptr, 4), "lut_rejects_bad_args");
        chip.send_command(0x05);
        check_true(last(0x05) != nullptr, "send_command_raw");
    }
    // ---- Full: partial window -----------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m;
        Full chip(m, fb, 16, 8);
        size_t from = m.events().size();
        bool ok = chip.show_region(3, 2, 6, 3);
        check_true(ok, "show_region_true");
        check_true(trace(m, from) == "C91 C90 C10 D2 D2 D2 C13 D2 D2 D2 C12 W0 C50 C02 W0 C92",
                   "show_region_sequence");
        const Event* ptl = findCommand(m, 0x90, from);
        check_true(ptl != nullptr && bytesEq(ptl->bytes, {0x00, 0x0F, 0x00, 0x02, 0x00, 0x04, 0x00}),
                   "show_region_ptl_bytes_widened_to_8_pixels");
        check_true(!chip.show_region(0, 0, 0, 5), "show_region_empty_false");
        check_true(!chip.show_region(40, 0, 4, 4), "show_region_outside_false");
    }
    // ---- Full: reads ----------------------------------------------------------------------------------
    {
        uint8_t fb[16];
        DisplayConnectionMock m(DisplayWireMode::kHalfDuplexPin);
        Full chip(m, fb, 16, 8);
        uint8_t st = 0;
        const uint8_t status[] = {0x13};
        m.queueRead(status, 1);
        size_t from = m.events().size();
        check_true(chip.read_status(&st) && st == 0x13, "read_status");
        check_true(trace(m, from) == "C71 r1", "read_status_sequence");

        int8_t temp = 0;
        const uint8_t tsc[] = {0xE7, 0x00};
        m.queueRead(tsc, 2);
        check_true(chip.read_temperature(&temp) && temp == -25, "read_temperature_signed");

        uint8_t rev = 0;
        const uint8_t revb[] = {0x5A};
        m.queueRead(revb, 1);
        check_true(chip.read_revision(&rev) && rev == 0x5A, "read_revision_one_byte");

        float v = 0;
        const uint8_t vv[] = {0x12};
        m.queueRead(vv, 1);
        check_true(chip.read_vcom(&v) && v < -0.99f && v > -1.01f, "read_vcom_volts");

        bool low = true;
        const uint8_t lp1[] = {0x01};
        m.queueRead(lp1, 1);
        check_true(chip.read_low_power(&low) && !low, "read_low_power_normal");
        const uint8_t lp0[] = {0x00};
        m.queueRead(lp0, 1);
        check_true(chip.read_low_power(&low) && low, "read_low_power_low");

        uint8_t otp[2] = {0, 0};
        const uint8_t rot[] = {0xFF, 0xA5, 0x12};
        m.queueRead(rot, 3);
        check_true(chip.read_otp(otp, 2) && otp[0] == 0xA5 && otp[1] == 0x12, "read_otp_drops_dummy");

        DisplayConnectionMock simplex;
        Full s(simplex, fb, 16, 8);
        check_true(!s.read_status(&st), "read_fails_in_simplex");
    }
    // ---- drawing ---------------------------------------------------------------------------------------
    {
        uint8_t fb[5 * 10];
        DisplayConnectionMock m;
        Full chip(m, fb, 40, 10);
        chip.draw_line(0, 0, 9, 9, Full::BLACK);
        check_true(chip.get_pixel(0, 0) == Full::BLACK && chip.get_pixel(5, 5) == Full::BLACK
                   && chip.get_pixel(9, 9) == Full::BLACK && chip.get_pixel(5, 4) == Full::WHITE, "line_diagonal");
        chip.clear();
        chip.draw_rect(1, 1, 5, 4, Full::BLACK);
        check_true(chip.get_pixel(1, 1) == Full::BLACK && chip.get_pixel(5, 4) == Full::BLACK
                   && chip.get_pixel(3, 2) == Full::WHITE, "rect_outline");
        chip.draw_rect(10, 1, 3, 3, Full::BLACK, true);
        check_true(chip.get_pixel(11, 2) == Full::BLACK && chip.get_pixel(12, 3) == Full::BLACK
                   && chip.get_pixel(13, 3) == Full::WHITE && chip.get_pixel(9, 1) == Full::WHITE, "rect_filled");
        chip.clear();
        const uint8_t bmp[2] = {0xA0, 0x40};   // row0: 1010..., row1: .1..
        chip.blit(4, 2, bmp, 4, 2, Full::BLACK);
        check_true(chip.get_pixel(4, 2) == Full::BLACK && chip.get_pixel(5, 2) == Full::WHITE
                   && chip.get_pixel(6, 2) == Full::BLACK && chip.get_pixel(5, 3) == Full::BLACK
                   && chip.get_pixel(4, 3) == Full::WHITE, "blit_msb_first_set_bits_only");
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
