// Unit test for the UC8159 driver against DisplayConnectionMock — no hardware.
#include <stdio.h>
#include <stdint.h>
#include <string>
#include <vector>
#include "DisplayConnectionMock.h"
#include "UC8159.h"

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

static bool bytesEq(const std::vector<uint8_t>& got, std::initializer_list<uint8_t> want) {
    if (got.size() != want.size()) return false;
    size_t i = 0;
    for (uint8_t b : want) if (got[i++] != b) return false;
    return true;
}

typedef UC8159Minimal<DisplayConnectionMock> Mini;
typedef UC8159Full<DisplayConnectionMock> Full;

static uint8_t fbuf[100801];

// All data bytes sent after event index `from`, concatenated.
static std::vector<uint8_t> dataFrom(const DisplayConnectionMock& m, size_t from) {
    std::vector<uint8_t> out;
    const std::vector<Event>& ev = m.events();
    for (size_t i = from; i < ev.size(); i++)
        if (ev[i].kind == Event::kData) out.insert(out.end(), ev[i].bytes.begin(), ev[i].bytes.end());
    return out;
}

static size_t countKind(const DisplayConnectionMock& m, size_t from, Event::Kind k) {
    size_t n = 0;
    for (size_t i = from; i < m.events().size(); i++) if (m.events()[i].kind == k) n++;
    return n;
}

int main() {
    // ---- construction ---------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini chip(m, fbuf, sizeof(fbuf));
        check_true(chip.valid(), "init_valid");
        check_true(trace(m) == "R W0 C00 C01 C03 C06 C30 C41 C50 C60 C61 CE3", "init_sequence_vendor");
        check_true(bytesEq(m.events()[2].bytes, {0xEF, 0x08}), "init_psr_ef_08");
        check_true(bytesEq(m.events()[3].bytes, {0x37, 0x00, 0x23, 0x23}), "init_pwr");
        check_true(bytesEq(m.events()[4].bytes, {0x00}), "init_pfs");
        check_true(bytesEq(m.events()[5].bytes, {0xC7, 0xC7, 0x1D}), "init_btst");
        check_true(bytesEq(m.events()[6].bytes, {0x3C}), "init_pll_50hz");
        check_true(bytesEq(m.events()[7].bytes, {0x00}), "init_tse_internal");
        check_true(bytesEq(m.events()[8].bytes, {0x37}), "init_cdi");
        check_true(bytesEq(m.events()[9].bytes, {0x22}), "init_tcon");
        check_true(bytesEq(m.events()[10].bytes, {0x02, 0x58, 0x01, 0xC0}), "init_tres_600x448");
        check_true(bytesEq(m.events()[11].bytes, {0xAA}), "init_pws");
        check_true(m.events()[1].timeoutMs == 60000 && !m.events()[1].busyActiveHigh, "init_busy_wait_active_low");
        check_true(chip.width() == 600 && chip.height() == 448, "geometry");
        check_true(Mini::buffer_size() == 100801, "buffer_size");
    }
    // ---- invalid ---------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini nobuf(m, nullptr, 0);
        check_true(!nobuf.valid(), "null_buffer_invalid");
        Mini small(m, fbuf, 1000);
        check_true(!small.valid(), "small_buffer_invalid");
        check_true(!small.show() && !small.sleep(), "invalid_show_sleep_false");
        DisplayConnectionMock bad;
        bad.setReady(false);
        Mini dead(bad, fbuf, sizeof(fbuf));
        check_true(!dead.valid(), "init_busy_timeout_invalid");
    }
    // ---- 3 bpp framebuffer ---------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fbuf, sizeof(fbuf));
        check_true(chip.get_pixel(0, 0) == Full::WHITE && chip.get_pixel(599, 447) == Full::WHITE, "white_after_init");
        for (uint8_t c = 0; c < 8; c++) {
            chip.set_pixel(7 + c, 3, c);
        }
        bool ok = true;
        for (uint8_t c = 0; c < 8; c++) ok = ok && chip.get_pixel(7 + c, 3) == c;
        check_true(ok, "all_eight_colours_roundtrip");
        check_true(chip.get_pixel(6, 3) == Full::WHITE && chip.get_pixel(15, 3) == Full::WHITE,
                   "neighbours_untouched");
        chip.set_pixel(599, 447, Full::ORANGE);
        check_true(chip.get_pixel(599, 447) == Full::ORANGE && chip.get_pixel(598, 447) == Full::WHITE,
                   "last_pixel");
        chip.set_pixel(-1, 0, Full::RED); chip.set_pixel(600, 0, Full::RED);
        chip.set_pixel(0, 448, Full::RED);
        check_true(chip.get_pixel(0, 0) == Full::WHITE && chip.get_pixel(-5, 9) == Full::BLACK,
                   "out_of_range_ignored");
        chip.clear(Full::GREEN);
        check_true(chip.get_pixel(0, 0) == Full::GREEN && chip.get_pixel(333, 222) == Full::GREEN &&
                   chip.get_pixel(599, 447) == Full::GREEN, "clear_green");
        chip.clear();
        check_true(chip.get_pixel(1, 1) == Full::WHITE, "clear_default_white");
    }
    // ---- show --------------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini chip(m, fbuf, sizeof(fbuf));
        chip.clear(Mini::WHITE);
        chip.set_pixel(0, 0, Mini::BLACK);
        chip.set_pixel(1, 0, Mini::ORANGE);
        chip.set_pixel(599, 447, Mini::RED);
        size_t from = m.events().size();
        bool ok = chip.show();
        check_true(ok, "show_true");
        const std::vector<Event>& ev = m.events();
        check_true(ev[from].kind == Event::kCommand && ev[from].cmd == 0x10, "show_starts_with_dtm1");
        std::vector<uint8_t> data = dataFrom(m, from);
        check_true(data.size() == 134400, "frame_is_134400_bytes");
        check_true(data[0] == 0x06 && data[1] == 0x11, "first_bytes_black_orange_then_white");
        check_true(data[134399] == 0x14, "last_byte_white_red");
        check_true(data[2] == 0x11 && data[134398] == 0x11, "white_is_0x11");
        // after the frame: PON W DRF W POF W
        std::string t = trace(m, from);
        size_t pos = t.rfind("C04");
        check_true(pos != std::string::npos && t.substr(pos) == "C04 W0 C12 W0 C02 W0", "show_tail_pon_drf_pof");
        check_true(countKind(m, from, Event::kCommand) == 4, "four_commands_dtm1_pon_drf_pof");
    }
    // ---- timeout ---------------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Mini chip(m, fbuf, sizeof(fbuf));
        m.setReady(false);
        check_true(!chip.show(), "show_false_on_timeout");
        check_true(!chip.valid(), "timeout_marks_invalid");
    }
    // ---- sleep / wake ------------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fbuf, sizeof(fbuf));
        size_t from = m.events().size();
        check_true(chip.sleep(), "sleep_true");
        check_true(trace(m, from) == "C07" && bytesEq(m.events()[from].bytes, {0xA5}), "sleep_dslp_a5");
        from = m.events().size();
        check_true(chip.show(), "show_after_sleep");
        check_true(trace(m, from).rfind("R W0 C00", 0) == 0, "show_after_sleep_wakes_first");
        chip.sleep();
        from = m.events().size();
        check_true(chip.wake(), "wake_true");
        check_true(trace(m, from).find("R W0 C00 C01 C03 C06") == 0, "wake_resets_and_reinitialises");
    }
    // ---- Full: settings ---------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fbuf, sizeof(fbuf));
        auto last = [&](uint8_t cmd) {
            const Event* r = nullptr;
            for (size_t i = 0; i < m.events().size(); i++)
                if (m.events()[i].kind == Event::kCommand && m.events()[i].cmd == cmd) r = &m.events()[i];
            return r;
        };
        chip.set_flip(true, false);
        check_true(bytesEq(last(0x00)->bytes, {0xEB, 0x08}), "flip_horizontal_clears_shl");
        chip.set_flip(false, true);
        check_true(bytesEq(last(0x00)->bytes, {0xE7, 0x08}), "flip_vertical_clears_ud");
        chip.set_flip(false, false);
        check_true(bytesEq(last(0x00)->bytes, {0xEF, 0x08}), "flip_none_restores");
        chip.set_frame_rate(0x3A);
        check_true(bytesEq(last(0x30)->bytes, {0x3A}), "frame_rate_code");
        chip.set_interval(7);
        check_true(bytesEq(last(0x50)->bytes, {0x37}), "interval_default_is_0x37");
        chip.set_interval(3, Full::BLACK, 0);
        check_true(bytesEq(last(0x50)->bytes, {0x03}), "interval_custom");
        chip.set_temperature_sensor(true, -3);
        check_true(bytesEq(last(0x41)->bytes, {0x8D}), "temperature_sensor_external_offset");
        chip.set_vcom_dc(-0.10f);
        check_true(bytesEq(last(0x82)->bytes, {0x02}), "vcom_dc_minus_0_10");
        chip.set_vcom_dc(-2.0f);
        check_true(bytesEq(last(0x82)->bytes, {0x28}), "vcom_dc_minus_2_0");
        chip.set_vcom_dc(-9.0f);
        check_true(bytesEq(last(0x82)->bytes, {0x50}), "vcom_dc_clamps_high");
        chip.send_command(0x13, nullptr, 0);
        check_true(last(0x13) != nullptr, "send_command_raw");
    }
    // ---- Full: reads -------------------------------------------------------------------------------
    {
        uint8_t st = 0;
        DisplayConnectionMock m(DisplayWireMode::kHalfDuplexPin);
        Full chip(m, fbuf, sizeof(fbuf));
        const uint8_t status[] = {0x0F};
        m.queueRead(status, 1);
        size_t from = m.events().size();
        check_true(chip.read_status(&st) && st == 0x0F, "read_status");
        check_true(trace(m, from) == "C71 r1", "read_status_sequence");
        int8_t temp = 0;
        const uint8_t tsc[] = {0xE7, 0x00};
        m.queueRead(tsc, 2);
        check_true(chip.read_temperature(&temp) && temp == -25, "read_temperature_signed");
        bool low = true;
        const uint8_t lp[] = {0x01};
        m.queueRead(lp, 1);
        check_true(chip.read_low_power(&low) && !low, "read_low_power_normal");
        float v = 0;
        const uint8_t vv[] = {0x0C};
        m.queueRead(vv, 1);
        check_true(chip.read_vcom(&v) && v < -0.59f && v > -0.61f, "read_vcom_volts");
        DisplayConnectionMock simplex;
        Full s(simplex, fbuf, sizeof(fbuf));
        check_true(!s.read_status(&st), "read_fails_in_simplex");
    }
    // ---- drawing ---------------------------------------------------------------------------------------
    {
        DisplayConnectionMock m;
        Full chip(m, fbuf, sizeof(fbuf));
        chip.draw_line(0, 0, 9, 9, Full::RED);
        check_true(chip.get_pixel(0, 0) == Full::RED && chip.get_pixel(5, 5) == Full::RED &&
                   chip.get_pixel(9, 9) == Full::RED && chip.get_pixel(5, 4) == Full::WHITE, "line_diagonal");
        chip.clear();
        chip.draw_rect(1, 1, 5, 4, Full::BLUE);
        check_true(chip.get_pixel(1, 1) == Full::BLUE && chip.get_pixel(5, 4) == Full::BLUE &&
                   chip.get_pixel(3, 2) == Full::WHITE, "rect_outline");
        chip.draw_rect(10, 1, 3, 3, Full::YELLOW, true);
        check_true(chip.get_pixel(11, 2) == Full::YELLOW && chip.get_pixel(12, 3) == Full::YELLOW &&
                   chip.get_pixel(13, 3) == Full::WHITE, "rect_filled");
        chip.clear();
        const uint8_t bmp[2] = {0xA0, 0x40};
        chip.blit(4, 2, bmp, 4, 2, Full::GREEN);
        check_true(chip.get_pixel(4, 2) == Full::GREEN && chip.get_pixel(5, 2) == Full::WHITE &&
                   chip.get_pixel(6, 2) == Full::GREEN && chip.get_pixel(5, 3) == Full::GREEN, "blit");
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed ? 1 : 0;
}
