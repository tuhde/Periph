#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "LSM303.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

// Register addresses (the sub-addresses the driver must use).
static const uint8_t A_CTRL1 = 0x20, A_CTRL2 = 0x21, A_CTRL3 = 0x22, A_CTRL4 = 0x23;
static const uint8_t A_CTRL5 = 0x24, A_CTRL6 = 0x25, A_REFERENCE = 0x26, A_STATUS = 0x27;
static const uint8_t A_FIFO_CTRL = 0x2E, A_FIFO_SRC = 0x2F;
static const uint8_t A_INT1_CFG = 0x30, A_INT1_SOURCE = 0x31, A_INT1_THS = 0x32, A_INT1_DUR = 0x33;
static const uint8_t A_INT2_CFG = 0x34, A_INT2_SOURCE = 0x35, A_INT2_THS = 0x36, A_INT2_DUR = 0x37;
static const uint8_t A_CLICK_CFG = 0x38, A_CLICK_SRC = 0x39, A_CLICK_THS = 0x3A;
static const uint8_t A_TLIMIT = 0x3B, A_TLATENCY = 0x3C, A_TWINDOW = 0x3D;
static const uint8_t A_OUT_X_L_BURST = 0xA8;  // 0x28 | 0x80 (auto-increment)
static const uint8_t A_OUT_X_L = 0x28;

static const uint8_t M_CRA = 0x00, M_CRB = 0x01, M_MR = 0x02, M_OUT_X_H = 0x03;
static const uint8_t M_SR = 0x09, M_IRA = 0x0A, M_TEMP_H = 0x31;

// Index of the first write at or after `from` to `reg` with exactly two bytes (reg, value),
// or SIZE_MAX. Only the portion of the log produced by the call under test is searched.
static size_t findWrite(const I2CConnectionMock& m, size_t from, uint8_t reg, int value = -1) {
    const auto& w = m.writes();
    for (size_t i = from; i < w.size(); i++) {
        if (w[i].size() == 2 && w[i][0] == reg && (value < 0 || w[i][1] == value)) return i;
    }
    return SIZE_MAX;
}

// Value of the last two-byte write to `reg` at or after `from`; -1 if none.
static int lastValue(const I2CConnectionMock& m, size_t from, uint8_t reg) {
    const auto& w = m.writes();
    int out = -1;
    for (size_t i = from; i < w.size(); i++) {
        if (w[i].size() == 2 && w[i][0] == reg) out = w[i][1];
    }
    return out;
}

// True if a register read with sub-address `sub` (a one-byte address write) happened at or after `from`.
static bool sawSubAddress(const I2CConnectionMock& m, size_t from, uint8_t sub) {
    const auto& w = m.writes();
    for (size_t i = from; i < w.size(); i++) {
        if (w[i].size() == 1 && w[i][0] == sub) return true;
    }
    return false;
}

// Count of two-byte writes at or after `from` (configuration writes only).
static size_t configWrites(const I2CConnectionMock& m, size_t from) {
    const auto& w = m.writes();
    size_t n = 0;
    for (size_t i = from; i < w.size(); i++) if (w[i].size() == 2) n++;
    return n;
}

// Stub InputPin: records the one registered handler so the test can fire it.
class FakeInputPin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = 0;
    bool onEdge(Handler h, uint8_t t = kFalling) override {
        handler = h;
        trigger = t;
        return true;
    }
    void offEdge(Handler h) override {
        if (handler == h) handler = nullptr;
    }
    bool isHigh() override { return false; }
};

static uint8_t gLastStatus = 0;
static int gCallbacks = 0;
static void onStatus(uint8_t status) {
    gLastStatus = status;
    gCallbacks++;
}

int main() {
    // ---------------------------------------------------------------- identity
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Minimal chip(accel, mag);
        check_true(chip.valid(), "identity_match_valid");
    }
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x00});  // wrong IRC
        LSM303Minimal chip(accel, mag);
        check_true(!chip.valid(), "identity_mismatch_invalid");
        check_true(configWrites(accel, 0) == 0, "identity_mismatch_no_accel_writes");
        check_true(configWrites(mag, 0) == 0, "identity_mismatch_no_mag_writes");
    }

    // ---------------------------------------------------------- init sequence
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Minimal chip(accel, mag);
        check_true(lastValue(accel, 0, A_CTRL2) == 0x00, "init_ctrl2_zero");
        check_true(lastValue(accel, 0, A_CTRL3) == 0x00, "init_ctrl3_zero");
        check_true(lastValue(accel, 0, A_CTRL5) == 0x00, "init_ctrl5_zero");
        check_true(lastValue(accel, 0, A_CTRL6) == 0x00, "init_ctrl6_zero");
        check_true(lastValue(accel, 0, A_FIFO_CTRL) == 0x00, "init_fifo_bypass");
        check_true(lastValue(accel, 0, A_CTRL4) == 0x88, "init_ctrl4_0x88");
        check_true(lastValue(accel, 0, A_CTRL1) == 0x57, "init_ctrl1_0x57");
        check_true(findWrite(accel, 0, A_CTRL4) < findWrite(accel, 0, A_CTRL1), "init_ctrl4_before_ctrl1");
        check_true(lastValue(mag, 0, M_CRA) == 0x10, "init_cra_0x10");
        check_true(lastValue(mag, 0, M_CRB) == 0x20, "init_crb_0x20");
        check_true(lastValue(mag, 0, M_MR) == 0x00, "init_mr_continuous");
        check_true(findWrite(mag, 0, M_MR) > findWrite(mag, 0, M_CRA), "init_mr_after_cra");
        check_true(sawSubAddress(mag, 0, M_IRA), "init_reads_identity");
    }

    // ------------------------------------------- multi-byte sub-address (0x80)
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Minimal chip(accel, mag);
        size_t n = accel.writes().size();
        float x, y, z;
        chip.readAcceleration(x, y, z);
        check_true(sawSubAddress(accel, n, A_OUT_X_L_BURST), "burst_uses_sub_0xA8");
        check_true(!sawSubAddress(accel, n, A_OUT_X_L), "burst_never_plain_0x28");

        size_t s = accel.writes().size();
        LSM303Full full(accel, mag);
        uint8_t st = full.readStatus();
        (void)st;
        check_true(sawSubAddress(accel, s, A_STATUS), "single_read_plain_0x27");
        check_true(!sawSubAddress(accel, s, A_STATUS | 0x80), "single_read_no_autoincrement");
    }

    // ----------------------------------------------- accelerometer conversion
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        // X = +16 counts after shift (left-justified 0x0010), Y = -16 (0xFFF0), Z = 256 counts (0x1000).
        accel.setRegister(A_OUT_X_L_BURST, {0x10, 0x00, 0xF0, 0xFF, 0x00, 0x10});
        LSM303Minimal chip(accel, mag);
        float x, y, z;
        chip.readAcceleration(x, y, z);
        check_true(near(x, 0.001f, 1e-6f), "accel_x_one_lsb_1mg");
        check_true(near(y, -0.001f, 1e-6f), "accel_y_negative_sign_extend");
        check_true(near(z, 0.256f, 1e-6f), "accel_z_256mg");

        I2CConnectionMock accel2, mag2;
        mag2.setRegister(M_IRA, {0x48, 0x34, 0x33});
        accel2.setRegister(A_OUT_X_L_BURST, {0x00, 0x80, 0x00, 0x00, 0x00, 0x00});
        LSM303Minimal chip2(accel2, mag2);
        chip2.readAcceleration(x, y, z);
        check_true(near(x, -2.048f, 1e-6f), "accel_full_negative_minus_2g");
    }

    // -------------------------------------- magnetometer reorder X, Z, Y + sign
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        // Wire order X (03,04), Z (05,06), Y (07,08), high byte first.
        // X = 0x0100 = 256, Z = 0x0200 = 512, Y = 0x0300 = 768. Gain 1100 XY, 980 Z (±1.3 gauss).
        mag.setRegister(M_OUT_X_H, {0x01, 0x00, 0x02, 0x00, 0x03, 0x00});
        LSM303Minimal chip(accel, mag);
        float x, y, z;
        chip.readMagnetic(x, y, z);
        check_true(near(x, 256.0f / 1100.0f * 100.0f, 1e-3f), "mag_x_from_first_pair");
        check_true(near(y, 768.0f / 1100.0f * 100.0f, 1e-3f), "mag_y_from_third_pair");
        check_true(near(z, 512.0f / 980.0f * 100.0f, 1e-3f), "mag_z_from_second_pair");

        I2CConnectionMock accel2, mag2;
        mag2.setRegister(M_IRA, {0x48, 0x34, 0x33});
        mag2.setRegister(M_OUT_X_H, {0xFF, 0xFE, 0x00, 0x00, 0x00, 0x00});  // X = -2 counts
        LSM303Minimal chip2(accel2, mag2);
        chip2.readMagnetic(x, y, z);
        check_true(near(x, -2.0f / 1100.0f * 100.0f, 1e-3f), "mag_x_negative_signed");

        // Raw read reorders to X, Y, Z.
        LSM303Full full(accel, mag);
        int16_t rx, ry, rz;
        full.readMagneticRaw(rx, ry, rz);
        check_true(rx == 256 && ry == 768 && rz == 512, "mag_raw_reordered_xyz");
    }

    // ---------------------------------------------------------- bit fields
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Full chip(accel, mag);

        size_t n = accel.writes().size();
        check_true(chip.setRange(8), "set_range_8_accepted");
        check_true(lastValue(accel, n, A_CTRL4) == 0xA8, "set_range_8_fs_bits_0x30");

        n = accel.writes().size();
        check_true(!chip.setRange(3), "set_range_3_rejected");
        check_true(configWrites(accel, n) == 0, "set_range_3_no_write");

        n = accel.writes().size();
        check_true(chip.setDataRate(400), "set_data_rate_400_accepted");
        check_true(lastValue(accel, n, A_CTRL1) == 0x77, "set_data_rate_400_odr_0111");

        n = accel.writes().size();
        check_true(!chip.setDataRate(1620), "set_data_rate_1620_rejected_hr");
        check_true(configWrites(accel, n) == 0, "set_data_rate_1620_no_write");

        // ODR is 400 Hz (code 7) here, so entering low-power keeps the rate legal.
        // CTRL1 was 0x77 → LPen set → 0x7F. CTRL4 was 0xA8 (FS=±8 g) → HR cleared → 0xA0.
        n = accel.writes().size();
        check_true(chip.setMode(0), "set_mode_low_power_accepted");
        check_true(lastValue(accel, n, A_CTRL1) == 0x7F, "set_mode_lp_sets_lpen");
        check_true(lastValue(accel, n, A_CTRL4) == 0xA0, "set_mode_lp_clears_hr");

        // In low-power mode 1344 Hz is illegal.
        n = accel.writes().size();
        check_true(!chip.setDataRate(1344), "rate_1344_rejected_in_low_power");
        check_true(configWrites(accel, n) == 0, "rate_1344_reject_no_write");

        // Low-power ±2 g: 16 mg/LSB, shift 8. Raw 0x4000 → 64 counts → 1.024 g.
        I2CConnectionMock accel_lp, mag_lp;
        mag_lp.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Full lp(accel_lp, mag_lp);
        check_true(lp.setMode(0), "lp_mode_on_fresh_chip");
        check_true(lp.setRange(2), "lp_range_2");
        accel_lp.setRegister(A_OUT_X_L_BURST, {0x00, 0x40, 0x00, 0x00, 0x00, 0x00});
        float x, y, z;
        lp.readAcceleration(x, y, z);
        check_true(near(x, 1.024f, 1e-4f), "lp_2g_16mg_per_lsb_shift8");
    }
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Full chip(accel, mag);

        size_t n = accel.writes().size();
        chip.setAxes(true, false, true);
        check_true(lastValue(accel, n, A_CTRL1) == 0x55, "set_axes_xz_only");

        n = accel.writes().size();
        chip.powerDown();
        // CTRL1 was 0x55 (X and Z enabled); ODR cleared, axis bits kept.
        check_true(lastValue(accel, n, A_CTRL1) == 0x05, "power_down_odr_zero_keeps_axes");
        // Time-based interrupt codes depend on ODR, and powerDown() set it to 0, so restore 100 Hz.
        check_true(chip.setDataRate(100), "rate_restored_100hz_after_power_down");

        n = accel.writes().size();
        chip.setHighPass(1, 2, true, true, false, true);
        check_true(lastValue(accel, n, A_CTRL2) == 0x6D, "high_pass_register_packing");

        n = accel.writes().size();
        check_true(chip.setFifo(1, 16, true), "set_fifo_stream_accepted");
        check_true(lastValue(accel, n, A_FIFO_CTRL) == 0x70, "set_fifo_mode_wm_tr");
        check_true(lastValue(accel, n, A_CTRL5) == 0x40, "set_fifo_sets_fifo_en");

        n = accel.writes().size();
        check_true(!chip.setFifo(4, 0, false), "set_fifo_mode_4_rejected");
        check_true(configWrites(accel, n) == 0, "set_fifo_reject_no_write");

        accel.setRegister(A_FIFO_SRC, {0xC3});  // WTM=1, OVRN=1, EMPTY=0, FSS=3
        bool wm, ov, em;
        uint8_t lvl;
        chip.readFifoStatus(wm, ov, em, lvl);
        check_true(wm && ov && !em && lvl == 3, "fifo_status_decode");
        check_true(chip.fifoLevel() == 3, "fifo_level_3");

        accel.setRegister(A_FIFO_SRC, {0x02});
        accel.setRegister(A_OUT_X_L_BURST, {0x10, 0x00, 0x20, 0x00, 0x30, 0x00});
        float xs[5], ys[5], zs[5];
        uint8_t got = chip.readFifo(xs, ys, zs, 5);
        check_true(got == 2, "read_fifo_limited_to_fss");
        check_true(near(xs[0], 0.001f, 1e-6f) && near(zs[1], 0.003f, 1e-6f), "read_fifo_values_in_g");

        n = accel.writes().size();
        chip.setFreeFall(1, 0.35f, 30.0f);
        check_true(lastValue(accel, n, A_INT1_CFG) == 0x95, "free_fall_cfg_0x95");
        check_true(lastValue(accel, n, A_INT1_THS) == 22, "free_fall_threshold_code_22");
        check_true(lastValue(accel, n, A_INT1_DUR) == 3, "free_fall_duration_code_3");

        n = accel.writes().size();
        check_true(chip.setMotionInterrupt(2, 0x3F, 1.0f, 100.0f, false), "motion_int2_accepted");
        check_true(lastValue(accel, n, A_INT2_CFG) == 0x3F, "motion_int2_cfg_or_mode");
        check_true(lastValue(accel, n, A_INT2_THS) == 64, "motion_int2_threshold_64");
        check_true(lastValue(accel, n, A_INT2_DUR) == 10, "motion_int2_duration_10");
        check_true(!chip.setMotionInterrupt(3, 0x01, 1.0f), "motion_gen3_rejected");

        // CTRL5 already holds FIFO_EN (0x40) from setFifo above; the RMW keeps it.
        n = accel.writes().size();
        chip.setOrientation(1, false, true);
        check_true(lastValue(accel, n, A_INT1_CFG) == 0x7F, "orientation_6d_movement_cfg");
        check_true(lastValue(accel, n, A_CTRL5) == 0x44, "orientation_4d_sets_d4d_int1_keeps_fifo_en");

        n = accel.writes().size();
        chip.setLatch(1, true);
        check_true(lastValue(accel, n, A_CTRL5) == 0x4C, "latch_int1_lir_bit3");

        n = accel.writes().size();
        chip.setClick(0x05, 0x02, 0.5f, 10.0f, 20.0f, 50.0f);
        check_true(lastValue(accel, n, A_CLICK_CFG) == 0x19, "click_single_xz_double_y_cfg");
        check_true(lastValue(accel, n, A_CLICK_THS) == 32, "click_threshold_32");
        check_true(lastValue(accel, n, A_TLIMIT) == 1, "click_time_limit_1");
        check_true(lastValue(accel, n, A_TLATENCY) == 2, "click_latency_2");
        check_true(lastValue(accel, n, A_TWINDOW) == 5, "click_window_5");

        n = accel.writes().size();
        chip.setInterruptPolarity(true);
        check_true(lastValue(accel, n, A_CTRL6) == 0x02, "polarity_active_low");

        // CTRL6 is read-modify-write, so the polarity bit stays set in each result.
        n = accel.writes().size();
        check_true(chip.enableInterrupt(LSM303Source::DATA_READY, 2), "enable_data_ready_forced_int1");
        check_true(lastValue(accel, n, A_CTRL3) == 0x10, "data_ready_routed_ctrl3_bit4");
        check_true(chip.enableInterrupt(LSM303Source::IA1, 2), "enable_ia1_int2");
        check_true(lastValue(accel, n, A_CTRL6) == 0x42, "ia1_int2_ctrl6_bit6_keeps_polarity");
        check_true(chip.enableInterrupt(LSM303Source::BOOT, 1), "boot_forced_int2");
        check_true(lastValue(accel, n, A_CTRL6) == 0x52, "boot_ctrl6_bit4");
        check_true(!chip.enableInterrupt(LSM303Source::MAG_DATA_READY, 1), "mag_drdy_not_routable");

        n = accel.writes().size();
        check_true(chip.disableInterrupt(LSM303Source::DATA_READY, 1), "disable_data_ready");
        check_true(lastValue(accel, n, A_CTRL3) == 0x00, "disable_data_ready_clears_bit");

        // Click source read clears; reading INT1 source uses plain sub-address.
        size_t s = accel.writes().size();
        (void)chip.readClickSource();
        check_true(sawSubAddress(accel, s, A_CLICK_SRC), "click_source_plain_sub");
        s = accel.writes().size();
        (void)chip.readInterruptSource(1);
        check_true(sawSubAddress(accel, s, A_INT1_SOURCE), "int1_source_plain_sub");
    }

    // ------------------------------------------------------------ magnetometer
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Full chip(accel, mag);

        size_t n = mag.writes().size();
        check_true(chip.setMagRange(810), "mag_range_810_accepted");
        check_true(lastValue(mag, n, M_CRB) == 0xE0, "mag_range_810_gn_111");
        check_true(!chip.setMagRange(123), "mag_range_123_rejected");

        n = mag.writes().size();
        check_true(chip.setMagDataRate(220.0f), "mag_rate_220_accepted");
        check_true(lastValue(mag, n, M_CRA) == 0x1C, "mag_rate_220_do_111");
        check_true(!chip.setMagDataRate(2.0f), "mag_rate_2_rejected");

        n = mag.writes().size();
        check_true(chip.setMagMode(2), "mag_mode_sleep");
        check_true(lastValue(mag, n, M_MR) == 2, "mag_mode_sleep_value");
        check_true(!chip.setMagMode(3), "mag_mode_3_rejected");

        // CRA was 0x1C (220 Hz); TEMP_EN is set and the rate field is kept.
        n = mag.writes().size();
        chip.enableTemperature(true);
        check_true(lastValue(mag, n, M_CRA) == 0x9C, "temperature_enable_keeps_rate");

        mag.setRegister(M_SR, {0x03});
        bool drdy, lock;
        chip.readMagStatus(drdy, lock);
        check_true(drdy && lock, "mag_status_drdy_lock");
        mag.setRegister(M_SR, {0x00});
        check_true(!chip.magDataReady(), "mag_data_not_ready");

        uint8_t id[3] = {0, 0, 0};
        chip.readMagIdentity(id);
        check_true(id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33, "mag_identity_bytes");
    }
    {
        // Gain after set_mag_range(810): 230 LSB/gauss → raw 230 is 100 µT.
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        mag.setRegister(M_OUT_X_H, {0x00, 0xE6, 0x00, 0x00, 0x00, 0x00});
        LSM303Full chip(accel, mag);
        chip.setMagRange(810);
        float x, y, z;
        chip.readMagnetic(x, y, z);
        check_true(near(x, 100.0f, 1e-3f), "mag_gain_810_raw230_is_100uT");

        // Temperature: 0x0180 >> 4 = 24 counts → 24/8 = 3.0 °C (relative).
        mag.setRegister(M_TEMP_H, {0x01, 0x80});
        check_true(near(chip.readTemperatureDelta(), 3.0f, 1e-4f), "temperature_delta_3c");
        mag.setRegister(M_TEMP_H, {0xFF, 0xF0});
        check_true(near(chip.readTemperatureDelta(), -0.125f, 1e-4f), "temperature_negative_delta");
    }

    // ----------------------------------------------------------------- heading
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        // X = +100 µT (raw 1100), Z = 0, Y = -100 µT (raw -1100).  atan2(-100, 100) = -45° → 315°.
        mag.setRegister(M_OUT_X_H, {0x04, 0x4C, 0x00, 0x00, 0xFB, 0xB4});
        // Flat: Z = +1 g → 16000 raw (0x3E80) left-justified.
        accel.setRegister(A_OUT_X_L_BURST, {0x00, 0x00, 0x00, 0x00, 0x80, 0x3E});
        LSM303Full chip(accel, mag);
        check_true(near(chip.heading(false), 315.0f, 0.01f), "heading_flat_315");
        check_true(near(chip.heading(true), 315.0f, 0.01f), "heading_tilt_level_315");

        // Mag X = 0, Y = +100 µT → atan2(100, 0) = 90°.
        I2CConnectionMock accel2, mag2;
        mag2.setRegister(M_IRA, {0x48, 0x34, 0x33});
        mag2.setRegister(M_OUT_X_H, {0x00, 0x00, 0x00, 0x00, 0x04, 0x4C});
        LSM303Full chip2(accel2, mag2);
        check_true(near(chip2.heading(false), 90.0f, 0.01f), "heading_flat_90");
    }

    // -------------------------------------------------------------- interrupts
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        mag.setRegister(M_SR, {0x01});                  // DRDY on
        accel.setRegister(A_STATUS, {0x08});            // ZYXDA on
        LSM303Full chip(accel, mag);
        chip.enableInterrupt(LSM303Source::DATA_READY, 1);
        uint8_t st = chip.pollInterrupt();
        check_true(st == (LSM303Source::DATA_READY | LSM303Source::MAG_DATA_READY), "poll_status_data_ready_and_mag");

        // Not routed: watermark never reported even if the FIFO flag is set.
        I2CConnectionMock accel2, mag2;
        mag2.setRegister(M_IRA, {0x48, 0x34, 0x33});
        accel2.setRegister(A_FIFO_SRC, {0x80});
        LSM303Full chip2(accel2, mag2);
        check_true((chip2.pollInterrupt() & LSM303Source::WATERMARK) == 0, "poll_skips_unrouted_watermark");

        // Callback path with a fake pin.
        I2CConnectionMock accel3, mag3;
        mag3.setRegister(M_IRA, {0x48, 0x34, 0x33});
        accel3.setRegister(A_STATUS, {0x08});
        LSM303Full chip3(accel3, mag3);
        chip3.enableInterrupt(LSM303Source::DATA_READY, 1);
        FakeInputPin pin;
        gCallbacks = 0;
        gLastStatus = 0;
        check_true(chip3.onInterrupt(onStatus, 1, &pin), "on_interrupt_registers_on_fake_pin");
        check_true(pin.handler != nullptr, "on_interrupt_handler_stored");
        check_true(pin.trigger == InputPin::kRising, "on_interrupt_active_high_rising");
        if (pin.handler) pin.handler();
        check_true(gCallbacks == 1 && (gLastStatus & LSM303Source::DATA_READY), "interrupt_fires_callback_with_status");
        chip3.offInterrupt(1);
        check_true(pin.handler == nullptr, "off_interrupt_removes_handler");

        // No INT pin available for pin 1 (mock has none) and pin 2 without an explicit pin.
        check_true(!chip3.onInterrupt(onStatus, 1), "on_interrupt_no_pin_returns_false");
        check_true(!chip3.onInterrupt(onStatus, 2), "on_interrupt_pin2_needs_explicit_pin");
        check_true(!chip3.onInterrupt(nullptr, 1, &pin), "on_interrupt_null_callback_rejected");
    }

    // ----------------------------------------------------------------- reboot
    {
        I2CConnectionMock accel, mag;
        mag.setRegister(M_IRA, {0x48, 0x34, 0x33});
        LSM303Full chip(accel, mag);
        size_t n = accel.writes().size();
        chip.reboot();
        check_true(findWrite(accel, n, A_CTRL5, 0x80) != SIZE_MAX, "reboot_sets_boot_bit");
        check_true(lastValue(accel, n, A_CTRL1) == 0x57, "reboot_reruns_init");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
