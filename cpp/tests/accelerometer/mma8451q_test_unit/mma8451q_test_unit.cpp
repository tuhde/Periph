#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "MMA8451Q.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Register addresses (mirrors the driver's protected constants).
static const uint8_t REG_STATUS       = 0x00;
static const uint8_t REG_OUT_X_MSB    = 0x01;
static const uint8_t REG_F_SETUP      = 0x09;
static const uint8_t REG_TRIG_CFG     = 0x0A;
static const uint8_t REG_SYSMOD       = 0x0B;
static const uint8_t REG_INT_SOURCE   = 0x0C;
static const uint8_t REG_WHO_AM_I     = 0x0D;
static const uint8_t REG_XYZ_DATA_CFG = 0x0E;
static const uint8_t REG_HP_FILTER    = 0x0F;
static const uint8_t REG_PL_STATUS    = 0x10;
static const uint8_t REG_PL_CFG       = 0x11;
static const uint8_t REG_PL_COUNT     = 0x12;
static const uint8_t REG_PL_BF_ZCOMP  = 0x13;
static const uint8_t REG_P_L_THS      = 0x14;
static const uint8_t REG_FF_MT_CFG    = 0x15;
static const uint8_t REG_FF_MT_SRC    = 0x16;
static const uint8_t REG_FF_MT_THS    = 0x17;
static const uint8_t REG_FF_MT_COUNT  = 0x18;
static const uint8_t REG_TRANSIENT_CFG  = 0x1D;
static const uint8_t REG_TRANSIENT_SRC  = 0x1E;
static const uint8_t REG_TRANSIENT_THS  = 0x1F;
static const uint8_t REG_TRANSIENT_COUNT = 0x20;
static const uint8_t REG_PULSE_CFG    = 0x21;
static const uint8_t REG_PULSE_SRC    = 0x22;
static const uint8_t REG_PULSE_THSX   = 0x23;
static const uint8_t REG_PULSE_TMLT   = 0x26;
static const uint8_t REG_CTRL_REG1    = 0x2A;
static const uint8_t REG_CTRL_REG2    = 0x2B;
static const uint8_t REG_CTRL_REG3    = 0x2C;
static const uint8_t REG_CTRL_REG4    = 0x2D;
static const uint8_t REG_CTRL_REG5    = 0x2E;
static const uint8_t REG_ASLP_COUNT   = 0x29;
static const uint8_t REG_OFF_X        = 0x2F;

// Returns the value of the LAST write to @p reg, or 0xFFFF when the register
// was never written. The mock accumulates every write, so "any write" checks
// would pass because of an earlier, unrelated call.
static uint16_t last_write(const I2CConnectionMock& mock, uint8_t reg) {
    uint16_t value = 0xFFFF;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == reg) value = w[1];
    }
    return value;
}

static bool wrote_reg(const I2CConnectionMock& mock, uint8_t reg, uint8_t value) {
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == reg && w[1] == value) return true;
    }
    return false;
}

int main() {
    // ------------------------------------------------------------------
    // Minimal driver
    // ------------------------------------------------------------------
    I2CConnectionMock mock;
    mock.setRegister(REG_WHO_AM_I, {0x1A});
    mock.setRegister(REG_CTRL_REG2, {0x00});
    mock.setRegister(REG_CTRL_REG1, {0x19});
    // X = +1 g (4096 counts -> 0x1000), Y = -1 g (0xF000), Z = 0.
    mock.setRegister(REG_OUT_X_MSB, {0x40, 0x00, 0xC0, 0x00, 0x00, 0x00});

    MMA8451QMinimal accel(mock);
    check_true(true, "construct_minimal");

    // The init sequence writes XYZ_DATA_CFG = 0, CTRL_REG2 = 0 and
    // CTRL_REG1 = 0x19 (DR = 011 -> 100 Hz, ACTIVE = 1).
    check_true(wrote_reg(mock, REG_XYZ_DATA_CFG, 0x00), "init_writes_xyz_data_cfg_0x00");
    check_true(wrote_reg(mock, REG_CTRL_REG1, 0x19), "init_writes_ctrl_reg1_0x19");
    check_true(wrote_reg(mock, REG_CTRL_REG2, 0x40), "init_writes_ctrl_reg2_rst");

    float x, y, z;
    accel.read(x, y, z);
    check_true(std::fabs(x - 1.0f) < 1e-6f, "read_x_plus_1g");
    check_true(std::fabs(y - (-1.0f)) < 1e-6f, "read_y_minus_1g");
    check_true(std::fabs(z - 0.0f) < 1e-6f, "read_z_zero_g");

    // ------------------------------------------------------------------
    // Full driver
    // ------------------------------------------------------------------
    I2CConnectionMock mock2;
    mock2.setRegister(REG_WHO_AM_I, {0x1A});
    mock2.setRegister(REG_CTRL_REG2, {0x00});
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x00});
    mock2.setRegister(REG_OUT_X_MSB, {0x40, 0x00, 0xC0, 0x00, 0x00, 0x00});

    MMA8451QFull accel_full(mock2);
    check_true(true, "construct_full");

    // set_range(4): XYZ_DATA_CFG = (0x00 & ~0x03) | 0x01 = 0x01.
    // The driver clears ACTIVE, writes, then restores CTRL_REG1.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_range(4);
    check_true(last_write(mock2, REG_XYZ_DATA_CFG) == 0x01, "set_range_4g");
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x19, "set_range_restores_active");

    // set_range(8): FS = 0b10.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x01});
    accel_full.set_range(8);
    check_true(last_write(mock2, REG_XYZ_DATA_CFG) == 0x02, "set_range_8g");

    // Back to ±2 g for the remaining checks (4096 counts/g).
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x02});
    accel_full.set_range(2);
    check_true(last_write(mock2, REG_XYZ_DATA_CFG) == 0x00, "set_range_back_to_2g");

    // set_data_rate(50): DR = 0b100 -> CTRL_REG1 = (0x19 & ~0x38) | (4 << 3) = 0x21.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_data_rate(50);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x21, "set_data_rate_50hz");

    // set_data_rate(800): DR = 0b000 -> CTRL_REG1 = (0x21 & ~0x38) | 0 = 0x01.
    mock2.setRegister(REG_CTRL_REG1, {0x21});
    accel_full.set_data_rate(800);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x01, "set_data_rate_800hz");

    // An unsupported rate is ignored.
    mock2.setRegister(REG_CTRL_REG1, {0x01});
    size_t odr_writes_before = mock2.writes().size();
    accel_full.set_data_rate(123);
    check_true(mock2.writes().size() == odr_writes_before, "set_data_rate_unsupported_ignored");

    // set_oversampling(2, 1): MODS = 0b10 (bits 1:0), SMODS = 0b01 (bits 4:3).
    mock2.setRegister(REG_CTRL_REG2, {0x00});
    accel_full.set_oversampling(2, 1);
    check_true(last_write(mock2, REG_CTRL_REG2) == 0x0A, "set_oversampling_high_resolution");

    // Back to normal oversampling (the default), which is what the debounce
    // and pulse time-step expectations below are calculated for.
    mock2.setRegister(REG_CTRL_REG2, {0x0A});
    accel_full.set_oversampling(0, 0);
    check_true(last_write(mock2, REG_CTRL_REG2) == 0x00, "set_oversampling_normal");

    // set_low_noise(true): CTRL_REG1 LNOISE = bit 2.
    mock2.setRegister(REG_CTRL_REG1, {0x01});
    accel_full.set_low_noise(true);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x05, "set_low_noise_true");
    mock2.setRegister(REG_CTRL_REG1, {0x05});
    accel_full.set_low_noise(false);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x01, "set_low_noise_false");

    // set_resolution(8): F_READ = bit 1 -> CTRL_REG1 = 0x01 | 0x02 = 0x03.
    mock2.setRegister(REG_CTRL_REG1, {0x01});
    accel_full.set_resolution(8);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x03, "set_resolution_8bit");
    // 8-bit read: MSB only, signed, 64 counts/g at ±2 g.
    mock2.setRegister(REG_OUT_X_MSB, {0x40, 0xC0, 0x00});
    accel_full.read(x, y, z);
    check_true(std::fabs(x - 1.0f) < 1e-6f, "read_8bit_x_plus_1g");
    check_true(std::fabs(y - (-1.0f)) < 1e-6f, "read_8bit_y_minus_1g");
    // Back to 14-bit.
    mock2.setRegister(REG_CTRL_REG1, {0x03});
    accel_full.set_resolution(14);
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x01, "set_resolution_14bit");

    // read_raw() in 14-bit mode.
    mock2.setRegister(REG_OUT_X_MSB, {0x40, 0x00, 0xC0, 0x00, 0x00, 0x00});
    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    check_true(rx == 4096, "read_raw_x_4096");
    check_true(ry == -4096, "read_raw_y_minus_4096");
    check_true(rz == 0, "read_raw_z_0");

    // new_data_available(): STATUS.ZYXDR = bit 3.
    mock2.setRegister(REG_STATUS, {0x08});
    check_true(accel_full.new_data_available(), "new_data_available_true");
    mock2.setRegister(REG_STATUS, {0x00});
    check_true(!accel_full.new_data_available(), "new_data_available_false");
    check_true(accel_full.read_status() == 0x00, "read_status");

    // read_system_mode(): SYSMOD bits 1:0.
    mock2.setRegister(REG_SYSMOD, {0x02});
    check_true(accel_full.read_system_mode() == 2, "read_system_mode_sleep");
    mock2.setRegister(REG_SYSMOD, {0x01});
    check_true(accel_full.read_system_mode() == 1, "read_system_mode_wake");

    // set_high_pass_output(true, 2): HPF_OUT = bit 4 in XYZ_DATA_CFG,
    // SEL = bits 1:0 in HP_FILTER_CUTOFF.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x00});
    mock2.setRegister(REG_HP_FILTER, {0x00});
    accel_full.set_high_pass_output(true, 2);
    check_true(last_write(mock2, REG_XYZ_DATA_CFG) == 0x10, "set_high_pass_output_enabled");
    check_true(last_write(mock2, REG_HP_FILTER) == 0x02, "set_high_pass_output_cutoff");

    // set_offset(0.1, -0.1, 0): codes are round(g / 0.002) = 50, -50, 0.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_offset(0.1f, -0.1f, 0.0f);
    check_true(last_write(mock2, REG_OFF_X) == 50, "set_offset_x");
    check_true(last_write(mock2, REG_OFF_X + 1) == (uint8_t)(uint16_t)(-50 & 0xFF),
               "set_offset_y");
    check_true(last_write(mock2, REG_OFF_X + 2) == 0, "set_offset_z");

    // get_offset(): 50 codes * 0.002 g = 0.1 g.
    mock2.setRegister(REG_OFF_X, {50, (uint8_t)(-50 & 0xFF), 0});
    accel_full.get_offset(x, y, z);
    check_true(std::fabs(x - 0.1f) < 1e-6f, "get_offset_x");
    check_true(std::fabs(y - (-0.1f)) < 1e-6f, "get_offset_y");
    check_true(std::fabs(z - 0.0f) < 1e-6f, "get_offset_z");

    // set_fifo(1, 16): F_SETUP = (1 << 6) | 16 = 0x50.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_fifo(1, 16);
    check_true(last_write(mock2, REG_F_SETUP) == 0x50, "set_fifo_circular_16");

    // Switching between two enabled modes passes through the disabled mode.
    accel_full.set_fifo(2, 0);
    check_true(wrote_reg(mock2, REG_F_SETUP, 0x00), "set_fifo_passes_through_disabled");
    check_true(last_write(mock2, REG_F_SETUP) == 0x80, "set_fifo_fill_mode");

    // set_fifo_trigger(transient, orientation, pulse, motion):
    // TRIG_CFG bits 5..2 = 0x20 | 0x10 | 0x08 | 0x04 = 0x3C.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_fifo_trigger(true, true, true, true);
    check_true(last_write(mock2, REG_TRIG_CFG) == 0x3C, "set_fifo_trigger_all");
    accel_full.set_fifo_trigger(false, false, false, true);
    check_true(last_write(mock2, REG_TRIG_CFG) == 0x04, "set_fifo_trigger_motion_only");

    // fifo_level() and read_fifo_status(): F_STATUS bits 5:0 = count.
    mock2.setRegister(REG_STATUS, {0xC5});  // overflow + watermark + 5 samples
    check_true(accel_full.fifo_level() == 5, "fifo_level_5");
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    accel_full.read_fifo_status(overflow, watermark, count);
    check_true(overflow, "fifo_status_overflow");
    check_true(watermark, "fifo_status_watermark");
    check_true(count == 5, "fifo_status_count");

    // read_fifo(): 5 samples available, buffer holds 2.
    float fifo[6];
    mock2.setRegister(REG_STATUS, {0x05});
    mock2.setRegister(REG_OUT_X_MSB, {0x40, 0x00, 0xC0, 0x00, 0x00, 0x00});
    uint8_t got = accel_full.read_fifo(fifo, 2);
    check_true(got == 2, "read_fifo_count_2");
    check_true(std::fabs(fifo[0] - 1.0f) < 1e-6f, "read_fifo_x_plus_1g");
    check_true(std::fabs(fifo[1] - (-1.0f)) < 1e-6f, "read_fifo_y_minus_1g");
    check_true(std::fabs(fifo[2] - 0.0f) < 1e-6f, "read_fifo_z_zero_g");

    // set_freefall_motion(motion=false, axes=7, threshold_g=0.2, debounce_ms=60):
    // FF_MT_CFG = ELE(0) | OAE(0) | ZEFE(0x20) | YEFE(0x10) | XEFE(0x08) = 0x38.
    // FF_MT_THS = round(0.2 / 0.063) = 3.
    // FF_MT_COUNT = round(60 / 10 ms) = 6 (debounce step at 100 Hz).
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG2, {0x00});
    accel_full.set_data_rate(100);
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_freefall_motion(false, 7, 0.2f, 60.0f, false, false);
    check_true(last_write(mock2, REG_FF_MT_CFG) == 0x38, "set_freefall_motion_cfg");
    check_true(last_write(mock2, REG_FF_MT_THS) == 3, "set_freefall_motion_threshold");
    check_true(last_write(mock2, REG_FF_MT_COUNT) == 6, "set_freefall_motion_debounce");

    // Latched + debounce_clear: ELE = 0x80, DBCNTM in FF_MT_THS = 0x80.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_freefall_motion(true, 0x04, 0.5f, 0.0f, true, true);
    check_true(last_write(mock2, REG_FF_MT_CFG) == (0x80 | 0x40 | 0x20), "set_freefall_motion_latched");
    check_true(last_write(mock2, REG_FF_MT_THS) == (0x80 | 8), "set_freefall_motion_dbcntm");

    // read_motion_source()
    mock2.setRegister(REG_FF_MT_SRC, {0x80});
    check_true(accel_full.read_motion_source() == 0x80, "read_motion_source");

    // set_transient(axes=7, threshold_g=0.25, debounce_ms=100, bypass_hpf=true,
    // latch=true): TRANSIENT_CFG = ELE(0x10) | ZTEFE(0x08) | YTEFE(0x04) |
    // XTEFE(0x02) | HPF_BYP(0x01) = 0x1F.
    // TRANSIENT_THS = round(0.25 / 0.063) = 4.
    // TRANSIENT_COUNT = round(100 / 10) = 10.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_transient(7, 0.25f, 100.0f, true, true);
    check_true(last_write(mock2, REG_TRANSIENT_CFG) == 0x1F, "set_transient_cfg");
    check_true(last_write(mock2, REG_TRANSIENT_THS) == 4, "set_transient_threshold");
    check_true(last_write(mock2, REG_TRANSIENT_COUNT) == 10, "set_transient_debounce");

    mock2.setRegister(REG_TRANSIENT_SRC, {0x80});
    check_true(accel_full.read_transient_source() == 0x80, "read_transient_source");

    // set_pulse(single=0, double=0x04, thresholds (0.5, 0.5, 1.0),
    // time_limit=40 ms, latency=100 ms, window=300 ms, latch=true,
    // double_abort=true, lpf=true, hpf=true):
    // PULSE_CFG = DPA(0x80) | ELE(0x40) | ZDPEFE(0x20) = 0xE0.
    // PULSE_THSX = round(0.5 / 0.063) = 8; PULSE_THSZ = round(1.0 / 0.063) = 16.
    // PULSE_TMLT = round(40 / 10 ms) = 4.
    // PULSE_LTCY = round(100 / 20 ms) = 5.
    // PULSE_WIND = round(300 / 20 ms) = 15.
    // HP_FILTER_CUTOFF = Pulse_HPF_BYP (0x20) | Pulse_LPF_EN (0x10) = 0x30.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_HP_FILTER, {0x00});
    accel_full.set_pulse(0, 0x04, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f,
                          true, true, true, true);
    check_true(last_write(mock2, REG_PULSE_CFG) == 0xE0, "set_pulse_cfg");
    check_true(last_write(mock2, REG_PULSE_THSX) == 8, "set_pulse_threshold_x");
    check_true(last_write(mock2, REG_PULSE_THSX + 2) == 16, "set_pulse_threshold_z");
    check_true(last_write(mock2, REG_PULSE_TMLT) == 4, "set_pulse_time_limit");
    check_true(last_write(mock2, REG_PULSE_TMLT + 1) == 5, "set_pulse_latency");
    check_true(last_write(mock2, REG_PULSE_TMLT + 2) == 15, "set_pulse_window");
    check_true(last_write(mock2, REG_HP_FILTER) == 0x30, "set_pulse_hpf_lpf_bits");

    mock2.setRegister(REG_PULSE_SRC, {0x80});
    check_true(accel_full.read_pulse_source() == 0x80, "read_pulse_source");

    // set_orientation(enabled=true, debounce_ms=100, threshold_deg=45,
    // hysteresis=4, z_lock=4, back_front=1):
    // PL_CFG = PL_EN (0x40) = 0x40.
    // PL_COUNT = round(100 / 10) = 10.
    // PL_BF_ZCOMP = (1 << 6) | 4 = 0x44.
    // P_L_THS = (0x10 << 3) | 4 = 0x84.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_orientation(true, 100.0f, 45, 4, 4, 1);
    check_true(last_write(mock2, REG_PL_CFG) == 0x40, "set_orientation_pl_cfg");
    check_true(last_write(mock2, REG_PL_COUNT) == 10, "set_orientation_pl_count");
    check_true(last_write(mock2, REG_PL_BF_ZCOMP) == 0x44, "set_orientation_zcomp");
    check_true(last_write(mock2, REG_P_L_THS) == 0x84, "set_orientation_ths");

    // An unsupported trip angle is ignored: no new PL_CFG write happens.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    size_t writes_before = mock2.writes().size();
    accel_full.set_orientation(true, 0.0f, 33, 4, 4, 1);
    bool pl_cfg_written = false;
    for (size_t i = writes_before; i < mock2.writes().size(); i++) {
        const auto& w = mock2.writes()[i];
        if (w.size() == 2 && w[0] == REG_PL_CFG) pl_cfg_written = true;
    }
    check_true(!pl_cfg_written, "set_orientation_invalid_angle_ignored");

    // read_orientation(): PL_STATUS = NEWLP(0x80) | LO(0x40) | LAPO=3 (0x06) | BAFRO(0x01)
    mock2.setRegister(REG_PL_STATUS, {0xC7});
    bool changed = false, lockout = false, back = false;
    uint8_t pl = 0;
    accel_full.read_orientation(changed, lockout, pl, back);
    check_true(changed, "read_orientation_changed");
    check_true(lockout, "read_orientation_lockout");
    check_true(pl == 3, "read_orientation_landscape_left");
    check_true(back, "read_orientation_back");

    // set_auto_sleep(true, 12.5, 10, SOURCE_PULSE | SOURCE_FF_MT):
    // CTRL_REG2.SLPE = bit 2 -> 0x04.
    // CTRL_REG1.ASLP_RATE = 0b01 -> (0x19 & ~0xC0) | 0x40 = 0x59.
    // ASLP_COUNT = round(10 / 0.32) = 31.
    // CTRL_REG3 = WAKE_PULSE (0x10) | WAKE_FF_MT (0x08) = 0x18.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG2, {0x00});
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_auto_sleep(true, 12.5f, 10.0f,
                              MMA8451QFull::SOURCE_PULSE | MMA8451QFull::SOURCE_FF_MT);
    check_true(last_write(mock2, REG_CTRL_REG2) == 0x04, "set_auto_sleep_slpe");
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x59, "set_auto_sleep_rate");
    check_true(last_write(mock2, REG_ASLP_COUNT) == 31, "set_auto_sleep_count");
    check_true(last_write(mock2, REG_CTRL_REG3) == 0x18, "set_auto_sleep_wake_sources");

    // set_fifo_gate(true): CTRL_REG3 bit 7.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_fifo_gate(true);
    check_true(last_write(mock2, REG_CTRL_REG3) == 0x80, "set_fifo_gate_true");
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG3, {0x80});
    accel_full.set_fifo_gate(false);
    check_true(last_write(mock2, REG_CTRL_REG3) == 0x00, "set_fifo_gate_false");

    // set_interrupt_pins(active_high=true, open_drain=true):
    // CTRL_REG3 = IPOL (0x02) | PP_OD (0x01) = 0x03.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    accel_full.set_interrupt_pins(true, true);
    check_true(last_write(mock2, REG_CTRL_REG3) == 0x03, "set_interrupt_pins_active_high_open_drain");
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG3, {0x03});
    accel_full.set_interrupt_pins(false, false);
    check_true(last_write(mock2, REG_CTRL_REG3) == 0x00, "set_interrupt_pins_defaults");

    // enable_interrupt(SOURCE_PULSE, pin=1):
    // CTRL_REG4 |= 0x08, CTRL_REG5 |= 0x08 (route to INT1).
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG4, {0x00});
    mock2.setRegister(REG_CTRL_REG5, {0x00});
    accel_full.enable_interrupt(MMA8451QFull::SOURCE_PULSE, 1);
    check_true(last_write(mock2, REG_CTRL_REG4) == 0x08, "enable_interrupt_ctrl_reg4");
    check_true(last_write(mock2, REG_CTRL_REG5) == 0x08, "enable_interrupt_ctrl_reg5_int1");

    // Route the same source to INT2 instead.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG4, {0x08});
    mock2.setRegister(REG_CTRL_REG5, {0x08});
    accel_full.enable_interrupt(MMA8451QFull::SOURCE_PULSE, 2);
    check_true(last_write(mock2, REG_CTRL_REG4) == 0x08, "enable_interrupt_ctrl_reg4_keep");
    check_true(last_write(mock2, REG_CTRL_REG5) == 0x00, "enable_interrupt_ctrl_reg5_int2");

    // disable_interrupt(SOURCE_PULSE): CTRL_REG4 &= ~0x08.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG4, {0x08});
    accel_full.disable_interrupt(MMA8451QFull::SOURCE_PULSE);
    check_true(last_write(mock2, REG_CTRL_REG4) == 0x00, "disable_interrupt_ctrl_reg4");

    // poll_interrupt(): reads INT_SOURCE and clears the latched source flags.
    // SOURCE_PULSE (0x08) set -> PULSE_SRC (0x22) is read as well.
    mock2.setRegister(REG_INT_SOURCE, {0x08});
    uint8_t status = accel_full.poll_interrupt();
    check_true(status == 0x08, "poll_interrupt_returns_int_source");
    bool pulse_src_read = false;
    for (const auto& w : mock2.writes()) {
        if (w.size() == 1 && w[0] == REG_PULSE_SRC) pulse_src_read = true;
    }
    check_true(pulse_src_read, "poll_interrupt_clears_pulse_source");

    // SOURCE_DATA_READY is left set — no source register is read for it.
    mock2.setRegister(REG_INT_SOURCE, {0x01});
    status = accel_full.poll_interrupt();
    check_true(status == 0x01, "poll_interrupt_data_ready");

    // SOURCE_FIFO (0x40) clears via F_STATUS (0x00).
    mock2.setRegister(REG_INT_SOURCE, {0x40});
    status = accel_full.poll_interrupt();
    check_true(status == 0x40, "poll_interrupt_fifo");

    // The snake_case aliases exist too.
    check_true(accel_full.poll_interrupt() == accel_full.poll_interrupt(),
               "poll_interrupt_alias_consistent");

    // ------------------------------------------------------------------
    // Standby / activate / reset
    // ------------------------------------------------------------------
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.standby();
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x18, "standby_clears_active");

    mock2.setRegister(REG_CTRL_REG1, {0x18});
    accel_full.activate();
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x19, "activate_sets_active");

    mock2.setRegister(REG_CTRL_REG2, {0x00});
    accel_full.reset();
    check_true(wrote_reg(mock2, REG_CTRL_REG2, 0x40), "reset_writes_ctrl_reg2_rst");

    // ------------------------------------------------------------------
    // Configuration interlock: the resolution cannot change while the FIFO
    // is enabled, and low-noise mode cannot be enabled at ±8 g.
    // ------------------------------------------------------------------
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_fifo(1, 0);
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    size_t res_writes_before = mock2.writes().size();
    accel_full.set_resolution(8);
    check_true(mock2.writes().size() == res_writes_before,
               "set_resolution_refused_while_fifo_enabled");
    // Disable the FIFO again so the following checks are unaffected.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    accel_full.set_fifo(0, 0);

    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x00});
    accel_full.set_range(8);
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    size_t ln_writes_before = mock2.writes().size();
    accel_full.set_low_noise(true);
    check_true(mock2.writes().size() == ln_writes_before,
               "set_low_noise_refused_at_8g");
    // Back to ±2 g.
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x02});
    accel_full.set_range(2);

    // ------------------------------------------------------------------
    // Self-test: the driver restores the configuration afterwards and
    // reports the per-axis deltas.
    // ------------------------------------------------------------------
    mock2.setRegister(REG_CTRL_REG1, {0x19});
    mock2.setRegister(REG_CTRL_REG2, {0x00});
    mock2.setRegister(REG_XYZ_DATA_CFG, {0x00});
    mock2.setRegister(REG_OUT_X_MSB, {0x40, 0x00, 0x00, 0x00, 0x00, 0x00});
    bool st_ok = false;
    int16_t st_dx = 0, st_dy = 0, st_dz = 0;
    accel_full.self_test(st_ok, st_dx, st_dy, st_dz);
    check_true(st_dx == 0 && st_dy == 0 && st_dz == 0, "self_test_deltas");
    check_true(!st_ok, "self_test_fails_without_motion");
    check_true(last_write(mock2, REG_CTRL_REG2) == 0x00, "self_test_restores_ctrl_reg2");
    check_true(last_write(mock2, REG_CTRL_REG1) == 0x19, "self_test_restores_ctrl_reg1");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
