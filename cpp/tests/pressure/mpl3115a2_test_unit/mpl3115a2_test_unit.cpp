#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "Mpl3115a2.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b) { return fabsf(a - b) < 1e-3f; }

// RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip.
class Mpl3115Mock : public I2CConnectionMock {
protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (len == 2 && data[0] == 0x26) setRegister(0x26, { (uint8_t)(data[1] & ~0x06) });
    }
};

static int lastWriteTo(Mpl3115Mock& c, uint8_t reg, size_t idx = 1) {
    int v = -1;
    for (const auto& w : c.writes()) if (w.size() > idx && w[0] == reg) v = w[idx];
    return v;
}

int main() {
    Mpl3115Mock c;
    c.setRegister(0x0C, {0xC4});
    c.setRegister(0x06, {0x0E});
    c.setRegister(0x01, {0x62, 0xF3, 0x80, 0x19, 0x80});   // 101326.0 Pa, 25.5 C

    MPL3115A2Full s(c);
    check_true(c.writes().empty(), "constructor_no_bus_access");
    check_true(s.init(), "init");
    check_true(lastWriteTo(c, 0x13) == 0x07, "init_pt_data_cfg");
    check_true(lastWriteTo(c, 0x26) == 0x38, "init_ctrl1_default");

    float p = 0, t = 0;
    check_true(s.read(p, t), "read_ok");
    check_true(near(p, 101326.0f), "read_pressure");
    check_true(near(t, 25.5f), "read_temperature");
    check_true(lastWriteTo(c, 0x26) == 0x3A, "read_sets_ost");

    c.setRegister(0x04, {0xE7, 0x00});
    check_true(s.read_temperature(t) && near(t, -25.0f), "negative_temperature");
    c.setRegister(0x04, {0x19, 0x80});

    c.setRegister(0x01, {0x00, 0x7B, 0x30});
    float alt = 0;
    check_true(s.read_altitude(alt) && near(alt, 123.1875f), "altitude_value");
    check_true(lastWriteTo(c, 0x26) == 0xBA, "altitude_sets_alt_bit");
    c.setRegister(0x01, {0xFF, 0x9C, 0x00});
    check_true(s.read_altitude(alt) && near(alt, -100.0f), "altitude_negative");
    c.setRegister(0x01, {0x62, 0xF3, 0x80});
    check_true(s.set_mode(false) && s.read_pressure(p) && near(p, 101326.0f), "back_to_barometer");

    check_true(s.set_oversample(16) && s.get_oversample() == 16, "oversample");
    check_true(lastWriteTo(c, 0x26) == 0x20, "oversample_ctrl1");
    check_true(!s.set_oversample(3), "oversample_invalid");
    s.set_oversample(128);

    check_true(s.set_sea_level_pressure(101326.0f), "sea_level");
    check_true(lastWriteTo(c, 0x14, 1) == 0xC5 && lastWriteTo(c, 0x14, 2) == 0xE7, "bar_in");

    check_true(s.set_offsets(-8.0f, 1.0f, -3), "offsets");
    check_true(lastWriteTo(c, 0x2B) == 0xFE, "off_p");
    check_true(lastWriteTo(c, 0x2C) == 16, "off_t");
    check_true(lastWriteTo(c, 0x2D) == 0xFD, "off_h");
    check_true(!s.set_offsets(1000.0f), "offset_range");

    check_true(s.set_target(100000.0f, 200.0f, -5, 3), "target");
    check_true(lastWriteTo(c, 0x16, 1) == 0xC3 && lastWriteTo(c, 0x16, 2) == 0x50, "p_tgt");
    check_true(lastWriteTo(c, 0x18) == 0xFB, "t_tgt");
    check_true(lastWriteTo(c, 0x19, 1) == 0x00 && lastWriteTo(c, 0x19, 2) == 0x64, "p_wnd");
    check_true(lastWriteTo(c, 0x1B) == 3, "t_wnd");

    check_true(s.start_continuous(2), "continuous_start");
    check_true((lastWriteTo(c, 0x27) & 0x0F) == 2, "continuous_st");
    check_true(lastWriteTo(c, 0x26) == 0x39, "continuous_sbyb");
    check_true(!s.start_continuous(16), "continuous_range");
    check_true(s.data_ready(), "data_ready");
    float v = 0;
    check_true(s.read_continuous(v, t) && near(v, 101326.0f) && near(t, 25.5f), "read_continuous");
    s.stop_continuous();
    check_true(lastWriteTo(c, 0x26) == 0x38, "continuous_stop");

    c.setRegister(0x07, {0x00, 0x00, 0x40, 0x00, 0x10});
    float dv = 0, dt = 0;
    check_true(s.read_delta(dv, dt) && near(dv, 1.0f) && near(dt, 0.0625f), "delta");

    c.setRegister(0x1C, {0x62, 0xF3, 0x80, 0x19, 0x80});
    float pmin, pmax, tmin, tmax;
    check_true(s.read_min_max(pmin, pmax, tmin, tmax) && near(pmin, 101326.0f), "min_max");
    check_true(s.clear_min_max() && lastWriteTo(c, 0x1C, 3) == 0, "clear_min_max");

    check_true(s.fifo_setup(1, 16) && lastWriteTo(c, 0x0F) == 0x50, "fifo_setup");
    check_true(!s.fifo_setup(2), "fifo_switch_rejected");
    c.setRegister(0x0D, {0xC2});
    bool ov, wm; uint8_t cnt;
    check_true(s.fifo_status(ov, wm, cnt) && ov && wm && cnt == 2, "fifo_status");
    c.setRegister(0x0E, {0x62, 0xF3, 0x80, 0x19, 0x80});
    float fv[4], ft[4];
    check_true(s.fifo_read(fv, ft, 4) == 2 && near(fv[0], 101326.0f), "fifo_read");
    s.fifo_setup(0);

    check_true(s.set_interrupt_pins(true, true) && lastWriteTo(c, 0x28) == 0x30, "int_pins");
    check_true(s.enable_interrupt(MPL3115A2Full::SOURCE_TTH, 1), "int_enable");
    check_true((lastWriteTo(c, 0x29) & 0x04) != 0, "int_enable_reg");
    check_true((lastWriteTo(c, 0x2A) & 0x04) != 0, "int_route_int1");
    s.enable_interrupt(MPL3115A2Full::SOURCE_DRDY, 2);
    check_true((lastWriteTo(c, 0x2A) & 0x80) == 0, "int_route_int2");
    s.disable_interrupt(MPL3115A2Full::SOURCE_TTH);
    check_true((lastWriteTo(c, 0x29) & 0x04) == 0, "int_disable");
    c.setRegister(0x12, {0x84});
    check_true(s.poll_interrupt() == 0x84, "poll_interrupt");
    check_true(!s.enable_interrupt(1, 3), "int_pin_invalid");

    check_true(s.who_am_i() == 0xC4, "who_am_i");
    c.setRegister(0x11, {0x01});
    check_true(s.is_active(), "is_active");
    check_true(s.reset() && lastWriteTo(c, 0x26) == 0x38, "reset");

    Mpl3115Mock bad;
    bad.setRegister(0x0C, {0x00});
    MPL3115A2Minimal b(bad);
    check_true(!b.init(), "init_bad_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
