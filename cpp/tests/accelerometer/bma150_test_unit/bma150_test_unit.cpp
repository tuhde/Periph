#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "BMA150.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_CHIP_ID   = 0x00;
static const uint8_t REG_RANGE_BW  = 0x14;
static const uint8_t REG_TEMP      = 0x08;
static const uint8_t REG_CTRL      = 0x0A;
static const uint8_t REG_INT_CTRL  = 0x0B;
static const uint8_t REG_LG_THRES  = 0x0C;
static const uint8_t REG_LG_DUR    = 0x0D;
static const uint8_t REG_HG_THRES  = 0x0E;
static const uint8_t REG_HG_DUR    = 0x0F;
static const uint8_t REG_ANY_MOTION_THRES = 0x10;
static const uint8_t REG_HYST_DUR  = 0x11;
static const uint8_t REG_CUSTOMER_1 = 0x12;
static const uint8_t REG_VERSION   = 0x01;
static const uint8_t REG_STATUS    = 0x09;
static const uint8_t REG_CONFIG    = 0x15;
static const uint8_t REG_ACC_X_LSB = 0x02;

int main() {
    I2CConnectionMock mock;
    mock.setRegister(REG_CHIP_ID, {0x02});
    mock.setRegister(REG_RANGE_BW, {0x00});
    // raw_x = 0x200 -> signed -512 -> -2 g; raw_y = 0; raw_z = 0x100 -> 1 g.
    mock.setRegister(REG_ACC_X_LSB, {0x00, 0x80});
    mock.setRegister(0x04, {0x00, 0x00});
    mock.setRegister(0x06, {0x00, 0x40});

    BMA150Minimal accel(mock);
    check_true(true, "construct_minimal");

    // Init reads CHIP_ID then writes RANGE_BW = (0x00 & 0xE0) | 0x00 | 0x02 = 0x02.
    bool rb_ok = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == REG_RANGE_BW && w[1] == 0x02) { rb_ok = true; }
    }
    check_true(rb_ok, "init_writes_range_bw_0x02");

    // read(): raw_x=0x200 -> signed -512 -> -2 g; raw_y=0 -> 0 g; raw_z=0x100 -> 1 g.
    float x, y, z;
    accel.read(x, y, z);
    check_true(std::fabs(x - (-2.0f)) < 1e-6f, "read_x_minus_2g");
    check_true(std::fabs(y - 0.0f) < 1e-6f, "read_y_zero_g");
    check_true(std::fabs(z - 1.0f) < 1e-6f, "read_z_plus_1g");

    // Full driver
    I2CConnectionMock mock2;
    mock2.setRegister(REG_CHIP_ID, {0x02});
    mock2.setRegister(REG_RANGE_BW, {0x00});
    mock2.setRegister(REG_ACC_X_LSB, {0x00, 0x80});
    mock2.setRegister(0x04, {0x00, 0x00});
    mock2.setRegister(0x06, {0x00, 0x40});

    BMA150Full accel_full(mock2);
    check_true(true, "construct_full");

    // set_range(4): RANGE_BW = (0x00 & 0xE0) | 0x08 | (0x02 & 0x07) = 0x0A.
    accel_full.set_range(4);
    rb_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_RANGE_BW && w[1] == 0x0A) { rb_ok = true; }
    }
    check_true(rb_ok, "set_range_4g");

    // set_bandwidth(190): RANGE_BW = (0x0A & 0xF8) | 0x03 = 0x0B.
    accel_full.set_bandwidth(190);
    rb_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_RANGE_BW && w[1] == 0x0B) { rb_ok = true; }
    }
    check_true(rb_ok, "set_bandwidth_190hz");

    // read_raw(): same data as the read() test, signed 10-bit.
    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    check_true(rx == -512, "read_raw_x");
    check_true(ry == 0, "read_raw_y");
    check_true(rz == 256, "read_raw_z");

    // set_low_g(0.4, 40): with range=4 the formula is round(0.4 * 255 / 4) = 26.
    mock2.setRegister(REG_HYST_DUR, {0x00});
    mock2.setRegister(REG_INT_CTRL, {0x00});
    accel_full.set_low_g(0.4f, 40);
    bool thres_ok = false, dur_ok = false, ic_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_LG_THRES && w[1] == 26) thres_ok = true;
        if (w.size() == 2 && w[0] == REG_LG_DUR   && w[1] == 40) dur_ok = true;
        if (w.size() == 2 && w[0] == REG_INT_CTRL && (w[1] & 0x01)) ic_ok = true;
    }
    check_true(thres_ok, "set_low_g_threshold");
    check_true(dur_ok, "set_low_g_duration");
    check_true(ic_ok, "set_low_g_enables_int");

    // set_high_g(4.0, 2): with range=4 the formula is round(4.0 * 255 / 4) = 255.
    mock2.setRegister(REG_INT_CTRL, {0x00});
    accel_full.set_high_g(4.0f, 2);
    thres_ok = false; dur_ok = false; ic_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_HG_THRES && w[1] == 255) thres_ok = true;
        if (w.size() == 2 && w[0] == REG_HG_DUR   && w[1] == 2)   dur_ok = true;
        if (w.size() == 2 && w[0] == REG_INT_CTRL && (w[1] & 0x02)) ic_ok = true;
    }
    check_true(thres_ok, "set_high_g_threshold_clamped");
    check_true(dur_ok, "set_high_g_duration");
    check_true(ic_ok, "set_high_g_enables_int");

    // set_any_motion(0.5, 3): with range=4 -> scale=0.5, code=64.
    accel_full.set_any_motion(0.5f, 3);
    bool am_ok = false, hd_ok = false, cfg_ok = false, ic_am_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_ANY_MOTION_THRES && w[1] == 64) am_ok = true;
        if (w.size() == 2 && w[0] == REG_HYST_DUR && (w[1] & 0xC0) == 0x40) hd_ok = true;
        if (w.size() == 2 && w[0] == REG_CONFIG && (w[1] & 0x40)) cfg_ok = true;
        if (w.size() == 2 && w[0] == REG_INT_CTRL && (w[1] & 0x40)) ic_am_ok = true;
    }
    check_true(am_ok, "set_any_motion_threshold");
    check_true(hd_ok, "set_any_motion_dur_3samples");
    check_true(cfg_ok, "set_any_motion_enables_adv_int");
    check_true(ic_am_ok, "set_any_motion_enables_int");

    // set_latch
    accel_full.set_latch(true);
    bool latch_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG && (w[1] & 0x10)) latch_ok = true;
    }
    check_true(latch_ok, "set_latch_true");

    accel_full.set_latch(false);
    latch_ok = true;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG) latch_ok = !(w[1] & 0x10);  // last write wins
    }
    check_true(latch_ok, "set_latch_false");

    // clear_interrupt: writes CTRL | 0x40.
    mock2.setRegister(REG_CTRL, {0x00});
    accel_full.clear_interrupt();
    bool clr_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CTRL && (w[1] & 0x40)) clr_ok = true;
    }
    check_true(clr_ok, "clear_interrupt_writes_reset");

    // soft_reset: writes CTRL | 0x02, then restores RANGE_BW with current range.
    mock2.setRegister(REG_CTRL, {0x00});
    mock2.setRegister(REG_RANGE_BW, {0x00});
    accel_full.soft_reset();
    bool sr_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CTRL && (w[1] & 0x02)) sr_ok = true;
    }
    check_true(sr_ok, "soft_reset_writes_soft_reset_bit");

    // set_wake_up(True, 80): CONFIG = (CONFIG & 0xF9) | 0x02 | 0x01.
    mock2.setRegister(REG_CONFIG, {0x00});
    accel_full.set_wake_up(true, 80);
    bool wu_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG && (w[1] & 0x03) == 0x03 && (w[1] & 0x06) == 0x02) wu_ok = true;
    }
    check_true(wu_ok, "set_wake_up_80ms");

    accel_full.set_wake_up(false);
    bool wu_off_ok = true;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG) wu_off_ok = !(w[1] & 0x01);  // last write wins
    }
    check_true(wu_off_ok, "set_wake_up_false");

    // sleep / wake
    mock2.setRegister(REG_CTRL, {0x00});
    accel_full.sleep();
    bool sleep_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CTRL && (w[1] & 0x01)) sleep_ok = true;
    }
    check_true(sleep_ok, "sleep_writes_sleep_bit");

    accel_full.wake();
    bool wake_ok = true;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CTRL) wake_ok = !(w[1] & 0x01);  // last write wins
    }
    check_true(wake_ok, "wake_clears_sleep_bit");

    // self_test
    mock2.setRegister(REG_CTRL, {0x00});
    mock2.setRegister(REG_STATUS, {0x80});
    bool st = accel_full.self_test();
    check_true(st == true, "self_test_returns_true_on_st_result");

    // read_version
    mock2.setRegister(REG_VERSION, {0xAB});
    uint8_t al, ml;
    accel_full.read_version(al, ml);
    check_true(al == 0xA, "read_version_al");
    check_true(ml == 0xB, "read_version_ml");

    // read_temperature: raw 0x40 -> 64 * 0.5 - 30 = 2
    mock2.setRegister(REG_TEMP, {0x40});
    float temp = accel_full.read_temperature();
    check_true(std::fabs(temp - 2.0f) < 1e-6f, "read_temperature");

    // read/write customer
    mock2.setRegister(REG_CUSTOMER_1, {0xA5});
    check_true(accel_full.read_customer(0) == 0xA5, "read_customer_0");
    accel_full.write_customer(1, 0x5A);
    bool cust_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == 0x13 && w[1] == 0x5A) cust_ok = true;
    }
    check_true(cust_ok, "write_customer_1");

    // set_shadow
    mock2.setRegister(REG_CONFIG, {0x00});
    accel_full.set_shadow(true);
    bool sh_ok = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG && (w[1] & 0x08)) sh_ok = true;
    }
    check_true(sh_ok, "set_shadow_true");

    accel_full.set_shadow(false);
    sh_ok = true;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 2 && w[0] == REG_CONFIG) sh_ok = !(w[1] & 0x08);  // last write wins
    }
    check_true(sh_ok, "set_shadow_false");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
