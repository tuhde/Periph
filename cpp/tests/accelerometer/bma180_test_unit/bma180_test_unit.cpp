#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "BMA180.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static uint8_t last_write_to(const std::vector<std::vector<uint8_t>>& writes, uint8_t reg) {
    for (auto it = writes.rbegin(); it != writes.rend(); ++it) {
        if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
    }
    return 0xFF;
}

// Register map (mirror of BMA180.h's protected constants).
static const uint8_t REG_CHIP_ID        = 0x00;
static const uint8_t REG_VERSION        = 0x01;
static const uint8_t REG_ACC_X_LSB      = 0x02;
static const uint8_t REG_ACC_Y_LSB      = 0x04;
static const uint8_t REG_ACC_Z_LSB      = 0x06;
static const uint8_t REG_TEMP           = 0x08;
static const uint8_t REG_STATUS_REG3    = 0x0B;
static const uint8_t REG_CTRL_REG0      = 0x0D;
static const uint8_t REG_RESET          = 0x10;
static const uint8_t REG_BW_TCS         = 0x20;
static const uint8_t REG_CTRL_REG3      = 0x21;
static const uint8_t REG_CTRL_REG4      = 0x22;
static const uint8_t REG_HIGH_LOW_INFO  = 0x25;
static const uint8_t REG_LOW_DUR        = 0x26;
static const uint8_t REG_HIGH_DUR       = 0x27;
static const uint8_t REG_LOW_TH         = 0x29;
static const uint8_t REG_HIGH_TH        = 0x2A;
static const uint8_t REG_SLOPE_TH       = 0x2B;
static const uint8_t REG_CD1            = 0x2C;
static const uint8_t REG_CD2            = 0x2D;
static const uint8_t REG_TCO_X          = 0x2E;
static const uint8_t REG_TCO_Y          = 0x2F;
static const uint8_t REG_TCO_Z          = 0x30;
static const uint8_t REG_GAIN_T         = 0x31;
static const uint8_t REG_GAIN_Y         = 0x33;
static const uint8_t REG_GAIN_Z         = 0x34;
static const uint8_t REG_OFFSET_LSB1    = 0x35;
static const uint8_t REG_OFFSET_T       = 0x37;
static const uint8_t REG_SLOPE_TAPSENS  = 0x24;

int main() {
    I2CConnectionMock mock;
    mock.setRegister(REG_CHIP_ID, {0x03});
    mock.setRegister(REG_CTRL_REG0, {0x00});
    mock.setRegister(REG_OFFSET_LSB1, {0x00});
    mock.setRegister(REG_BW_TCS, {0x00});
    // raw_x = 0x200 (MSB=0x08), raw_y = -0x200 (MSB=0xF8), raw_z = 0.
    mock.setRegister(REG_ACC_X_LSB, {0x00, 0x08});
    mock.setRegister(REG_ACC_Y_LSB, {0x00, 0xF8});
    mock.setRegister(REG_ACC_Z_LSB, {0x00, 0x00});

    BMA180Minimal accel(mock);
    check_true(true, "construct_minimal");

    // Init writes CTRL_REG0 |= 0x10, OFFSET_LSB1 = 0x04, BW_TCS = 0x40.
    bool ctrl0_ok = false, olsb1_ok = false, bw_ok = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == REG_CTRL_REG0 && (w[1] & 0x10)) ctrl0_ok = true;
        if (w.size() == 2 && w[0] == REG_OFFSET_LSB1 && w[1] == 0x04) olsb1_ok = true;
        if (w.size() == 2 && w[0] == REG_BW_TCS && w[1] == 0x40) bw_ok = true;
    }
    check_true(ctrl0_ok, "init_sets_ee_w");
    check_true(olsb1_ok, "init_sets_range_2g");
    check_true(bw_ok, "init_sets_bw_150hz");

    // read(): raw_x = 0x200 -> +512 -> 0.125 g; raw_y = -512 -> -0.125 g; raw_z = 0 -> 0 g.
    float x, y, z;
    accel.read(x, y, z);
    check_true(std::fabs(x - 0.125f) < 1e-6f, "read_x_plus_0_125g");
    check_true(std::fabs(y - (-0.125f)) < 1e-6f, "read_y_minus_0_125g");
    check_true(std::fabs(z - 0.0f) < 1e-6f, "read_z_zero_g");

    // --- Full driver ---
    I2CConnectionMock mock2;
    mock2.setRegister(REG_CHIP_ID, {0x03});
    mock2.setRegister(REG_CTRL_REG0, {0x00});
    mock2.setRegister(REG_OFFSET_LSB1, {0x00});
    mock2.setRegister(REG_BW_TCS, {0x00});
    mock2.setRegister(REG_ACC_X_LSB, {0x00, 0x08});
    mock2.setRegister(REG_ACC_Y_LSB, {0x00, 0x00});
    mock2.setRegister(REG_ACC_Z_LSB, {0x00, 0x04});

    BMA180Full accel_full(mock2);
    check_true(true, "construct_full");

    // set_range(8): OFFSET_LSB1 = (0x04 & ~0x0E) | 0x0A = 0x0A.
    accel_full.set_range(8);
    check_true(last_write_to(mock2.writes(), REG_OFFSET_LSB1) == 0x0A,
               "set_range_8g");

    // set_bandwidth(40): BW_TCS = (0x40 & 0x0F) | 0x20 = 0x20.
    accel_full.set_bandwidth(40);
    check_true(last_write_to(mock2.writes(), REG_BW_TCS) == 0x20,
               "set_bandwidth_40hz");

    // set_filter_mode(1): BW_TCS = (last & 0x0F) | 0x80 = 0x80.
    accel_full.set_filter_mode(1);
    check_true((last_write_to(mock2.writes(), REG_BW_TCS) & 0xF0) == 0x80,
               "set_filter_mode_high_pass");

    // set_mode(2): TCO_Z = (0x00 & ~0x03) | 0x02 = 0x02.
    mock2.setRegister(REG_TCO_Z, {0x00});
    accel_full.set_mode(2);
    check_true(last_write_to(mock2.writes(), REG_TCO_Z) == 0x02, "set_mode_2");

    // set_resolution(12): OFFSET_T |= 0x01.
    mock2.setRegister(REG_OFFSET_T, {0x00});
    accel_full.set_resolution(12);
    check_true(last_write_to(mock2.writes(), REG_OFFSET_T) == 0x01,
               "set_resolution_12bit");

    // set_resolution(14): OFFSET_T &= ~0x01 -> 0x00.
    accel_full.set_resolution(14);
    check_true(last_write_to(mock2.writes(), REG_OFFSET_T) == 0x00,
               "set_resolution_14bit");

    // read_raw(): raw_x = +512, raw_y = 0, raw_z = +256.
    mock2.setRegister(REG_ACC_X_LSB, {0x00, 0x08});
    mock2.setRegister(REG_ACC_Y_LSB, {0x00, 0x00});
    mock2.setRegister(REG_ACC_Z_LSB, {0x00, 0x04});
    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    check_true(rx == 512, "read_raw_x");
    check_true(ry == 0,   "read_raw_y");
    check_true(rz == 256, "read_raw_z");

    // read_temperature(): raw = 0x02 -> 25.0; raw = 0x82 -> -39.0.
    mock2.setRegister(REG_TEMP, {0x02});
    check_true(std::fabs(accel_full.read_temperature() - 25.0f) < 1e-6f,
               "read_temperature_25C");
    mock2.setRegister(REG_TEMP, {0x82});
    check_true(std::fabs(accel_full.read_temperature() - (-39.0f)) < 1e-6f,
               "read_temperature_neg");

    // new_data_available: all bits set -> true.
    mock2.setRegister(REG_ACC_X_LSB, {0x01});
    mock2.setRegister(REG_ACC_Y_LSB, {0x01});
    mock2.setRegister(REG_ACC_Z_LSB, {0x01});
    check_true(accel_full.new_data_available(), "new_data_available_true");
    mock2.setRegister(REG_ACC_X_LSB, {0x00});
    check_true(!accel_full.new_data_available(), "new_data_available_false");

    // set_shadow: GAIN_Y bit 0.
    mock2.setRegister(REG_GAIN_Y, {0x00});
    accel_full.set_shadow(false);
    check_true((last_write_to(mock2.writes(), REG_GAIN_Y) & 0x01),
               "set_shadow_false_sets_bit");
    accel_full.set_shadow(true);
    check_true(!(last_write_to(mock2.writes(), REG_GAIN_Y) & 0x01),
               "set_shadow_true_clears_bit");

    // set_sample_skip: OFFSET_LSB1 bit 0.
    mock2.setRegister(REG_OFFSET_LSB1, {0x00});
    accel_full.set_sample_skip(true);
    check_true((last_write_to(mock2.writes(), REG_OFFSET_LSB1) & 0x01),
               "set_sample_skip_true");
    accel_full.set_sample_skip(false);
    check_true(!(last_write_to(mock2.writes(), REG_OFFSET_LSB1) & 0x01),
               "set_sample_skip_false");

    // set_low_g: range=8 -> code = round(0.3/8*255) = 10. dur 40 / 2.085 ~= 19.
    mock2.setRegister(REG_HIGH_LOW_INFO, {0x00});
    mock2.setRegister(REG_LOW_DUR, {0x01});
    accel_full.set_low_g(0.3f, 40, 0.05f, 0x07, 0, true);
    check_true(last_write_to(mock2.writes(), REG_LOW_TH) == 10,
               "set_low_g_threshold");
    check_true((last_write_to(mock2.writes(), REG_LOW_DUR) & 0x01) == 0x01,
               "set_low_g_dur_preserves_bit0");
    check_true((last_write_to(mock2.writes(), REG_HIGH_LOW_INFO) & 0x0E) == 0x0E,
               "set_low_g_axes");
    check_true((last_write_to(mock2.writes(), REG_HIGH_LOW_INFO) & 0x01),
               "set_low_g_low_filt_bit");

    // set_high_g: range=8 -> code = round(1.8/8*255) = 57.
    mock2.setRegister(REG_HIGH_LOW_INFO, {0x00});
    mock2.setRegister(REG_HIGH_DUR, {0x00});
    accel_full.set_high_g(1.8f, 20, 0.0f, 0x07, 0, true);
    check_true(last_write_to(mock2.writes(), REG_HIGH_TH) == 57,
               "set_high_g_threshold");
    check_true((last_write_to(mock2.writes(), REG_HIGH_LOW_INFO) & 0xE0) == 0xE0,
               "set_high_g_axes");
    check_true((last_write_to(mock2.writes(), REG_HIGH_LOW_INFO) & 0x10),
               "set_high_g_high_filt_bit");

    // set_slope: range=8 -> code = round(0.3 / (0.0156 * 8 / 2)) = 5; samples=3 -> TCO_X = 0x01.
    mock2.setRegister(REG_TCO_X, {0x00});
    mock2.setRegister(REG_SLOPE_TAPSENS, {0x00});
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_slope(0.3f, 3, 0x07, true);
    check_true(last_write_to(mock2.writes(), REG_SLOPE_TH) == 5,
               "set_slope_threshold");
    check_true((last_write_to(mock2.writes(), REG_TCO_X) & 0x03) == 0x01,
               "set_slope_dur_3");
    check_true((last_write_to(mock2.writes(), REG_SLOPE_TAPSENS) & 0xE0) == 0xE0,
               "set_slope_axes");
    {
        uint8_t cr3 = last_write_to(mock2.writes(), REG_CTRL_REG3);
        check_true((cr3 & 0x44) == 0x44 && !(cr3 & 0x80), "set_slope_ctrl_reg3");
    }

    // set_alert(true): slope_alert + adv_int set; slope_int cleared.
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_alert(true);
    {
        uint8_t cr3 = last_write_to(mock2.writes(), REG_CTRL_REG3);
        check_true((cr3 & 0x84) == 0x84 && !(cr3 & 0x40), "set_alert_ctrl_reg3");
    }

    // set_tap: window=250 -> GAIN_T bits 2:0 = 0x04.
    mock2.setRegister(REG_GAIN_T, {0x00});
    accel_full.set_tap(0.5f, 250, 0x07, true);
    check_true((last_write_to(mock2.writes(), REG_GAIN_T) & 0x07) == 0x04,
               "set_tap_dur_250ms");

    // set_latch(true): CTRL_REG3 |= 0x01.
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_latch(true);
    check_true((last_write_to(mock2.writes(), REG_CTRL_REG3) & 0x01),
               "set_latch_true");

    // clear_interrupt: CTRL_REG0 |= 0x40.
    mock2.setRegister(REG_CTRL_REG0, {0x00});
    accel_full.clear_interrupt();
    check_true((last_write_to(mock2.writes(), REG_CTRL_REG0) & 0x40),
               "clear_interrupt_sets_reset_int");

    // set_wake_up(true, 80): TCO_Y bits 1:0 = 0x01; GAIN_Z bit 0 set.
    mock2.setRegister(REG_TCO_Y, {0x00});
    mock2.setRegister(REG_GAIN_Z, {0x00});
    accel_full.set_wake_up(true, 80);
    check_true((last_write_to(mock2.writes(), REG_TCO_Y) & 0x03) == 0x01,
               "set_wake_up_80ms_dur");
    check_true((last_write_to(mock2.writes(), REG_GAIN_Z) & 0x01),
               "set_wake_up_sets_bit");

    // sleep / wake: CTRL_REG0 bit 1.
    mock2.setRegister(REG_CTRL_REG0, {0x00});
    accel_full.sleep();
    check_true((last_write_to(mock2.writes(), REG_CTRL_REG0) & 0x02),
               "sleep_sets_bit");
    accel_full.wake();
    check_true(!(last_write_to(mock2.writes(), REG_CTRL_REG0) & 0x02),
               "wake_clears_bit");

    // soft_reset: writes RESET 0xB6.
    mock2.setRegister(REG_CHIP_ID, {0x03});
    accel_full.soft_reset();
    check_true(last_write_to(mock2.writes(), REG_RESET) == 0xB6,
               "soft_reset_writes_0xB6");

    // read_version: 0xAB -> (0xA, 0xB).
    mock2.setRegister(REG_VERSION, {0xAB});
    uint8_t al_v, ml_v;
    accel_full.read_version(al_v, ml_v);
    check_true(al_v == 0xA, "read_version_al");
    check_true(ml_v == 0xB, "read_version_ml");

    // read_customer / write_customer.
    mock2.setRegister(REG_CD1, {0xA5});
    check_true(accel_full.read_customer(0) == 0xA5, "read_customer_0");
    accel_full.write_customer(1, 0x5A);
    check_true(last_write_to(mock2.writes(), REG_CD2) == 0x5A,
               "write_customer_1");

    // poll_interrupt: STATUS_REG3 byte.
    mock2.setRegister(REG_STATUS_REG3, {0x80});
    check_true(accel_full.poll_interrupt() == 0x80, "poll_interrupt");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}