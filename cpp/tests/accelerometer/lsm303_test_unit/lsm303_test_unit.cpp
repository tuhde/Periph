#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include <vector>
#include <map>
#include "I2CConnectionMock.h"
#include "LSM303.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_CTRL1_A = 0x20;
static const uint8_t REG_CTRL3_A = 0x22;
static const uint8_t REG_CTRL4_A = 0x23;
static const uint8_t REG_CTRL5_A = 0x24;
static const uint8_t REG_CTRL6_A = 0x25;
static const uint8_t REG_STATUS_A = 0x27;
static const uint8_t REG_FIFO_CTRL_A = 0x2E;
static const uint8_t REG_FIFO_SRC_A = 0x2F;
static const uint8_t REG_INT1_CFG_A = 0x30;
static const uint8_t REG_INT1_SRC_A = 0x31;
static const uint8_t REG_INT1_THS_A = 0x32;
static const uint8_t REG_INT1_DUR_A = 0x33;
static const uint8_t REG_INT2_CFG_A = 0x34;
static const uint8_t REG_INT2_SRC_A = 0x35;
static const uint8_t REG_INT2_THS_A = 0x36;
static const uint8_t REG_CLICK_CFG_A = 0x38;
static const uint8_t REG_CLICK_SRC_A = 0x39;
static const uint8_t REG_CLICK_THS_A = 0x3A;
static const uint8_t REG_TIME_LIMIT_A = 0x3B;
static const uint8_t REG_TIME_LAT_A = 0x3C;
static const uint8_t REG_TIME_WIN_A = 0x3D;
// The accelerometer burst-read sub-address sends reg|0x80 = 0xA8; the mock's
// register map is keyed by the byte sent on the wire, so the output block
// lives at 0xA8..0xAD.
static const uint8_t ACC_OUT = 0xA8;

static const uint8_t REG_CRA_M = 0x00;
static const uint8_t REG_CRB_M = 0x01;
static const uint8_t REG_MR_M = 0x02;
static const uint8_t REG_OUT_M = 0x03;
static const uint8_t REG_SR_M = 0x09;
static const uint8_t REG_IRA_M = 0x0A;
static const uint8_t REG_TEMP_M = 0x31;

// Fake InputPin for edge delivery (mirrors the FakePin in vl53l1x_test_unit).
class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = kFalling;
    bool onEdge(Handler h, uint8_t t = kFalling) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (h == handler) handler = nullptr; }
    bool isHigh() override { return true; }
};

// I2CConnectionMock variant that can carry an intPin (RegisterConnection
// fixes the INT pin at construction, so the plain mock cannot serve the
// on_interrupt edge path). Register map, write log and write_read semantics
// match I2CConnectionMock's: address-only phases are logged, 2-byte writes
// are register writes.
class LsmMock : public RegisterConnection {
public:
    std::map<uint16_t, uint8_t> regs;
    std::vector<std::vector<uint8_t>> log;
    FakePin pin;

    explicit LsmMock(bool withPin = false) : RegisterConnection(withPin ? &pin : nullptr) {}

    void set(uint16_t reg, std::initializer_list<uint8_t> values) {
        uint16_t offset = 0;
        for (uint8_t v : values) regs[reg + offset++] = v;
    }

    // Value of the LAST 2-byte write to reg (1-byte addr + 1-byte data), 0xFF if none.
    uint8_t lastWrite(uint16_t reg) {
        for (auto it = log.rbegin(); it != log.rend(); ++it) {
            if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
        }
        return 0xFF;
    }

    // Number of 2-byte data writes to reg.
    int writeCount(uint16_t reg) {
        int n = 0;
        for (const auto& w : log) if (w.size() == 2 && w[0] == reg) n++;
        return n;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        if (len > 1) {
            uint16_t reg = data[0];
            for (size_t i = 1; i < len; i++) regs[(uint16_t)(reg + i - 1)] = data[i];
        }
    }

    void _read(uint8_t* buf, size_t len) override {
        for (size_t i = 0; i < len; i++) buf[i] = 0;
    }

    void _write_read(const uint8_t* data, size_t data_len,
                     uint8_t* buf, size_t buf_len) override {
        (void)data_len;
        log.emplace_back(data, data + data_len);
        uint16_t reg = data[0];
        for (size_t i = 0; i < buf_len; i++) {
            auto it = regs.find(static_cast<uint16_t>(reg + i));
            buf[i] = it != regs.end() ? it->second : 0;
        }
    }
};

static void prefill(LsmMock& mock) {
    // Magnetometer identity "H43".
    mock.set(REG_IRA_M, { 0x48, 0x34, 0x33, 0x33 });
    // Accelerometer sample block (burst sub-address 0xA8): X=0x2000, Y=0x0000,
    // Z=0x4000 — 8192 >> 4 = 512 counts (HR) → 0.512 g / 0 / 1.024 g.
    mock.set(ACC_OUT, { 0x00, 0x20, 0x00, 0x00, 0x00, 0x40 });
    // Magnetometer sample block: X=100, Z=200, Y=50 counts (high byte first).
    mock.set(REG_OUT_M, { 0x00, 0x64, 0x00, 0xC8, 0x00, 0x32 });
}

// Interrupt callbacks (plain function pointers, captureless).
static uint16_t g_status_pin1 = 0;
static uint16_t g_status_pin2 = 0xFFFF;
static void lsm303_cb1(uint16_t status) { g_status_pin1 = status; }
static void lsm303_cb2(uint16_t status) { g_status_pin2 = status; }

int main() {
    // --- Minimal: init register values ---
    LsmMock accel, mag;
    prefill(accel);
    prefill(mag);
    LSM303Minimal sensor(accel, mag);
    check_true(true, "construct_minimal");

    check_true(accel.lastWrite(REG_CTRL1_A) == 0x57, "init_ctrl1_0x57");
    check_true(accel.lastWrite(REG_CTRL4_A) == 0x88, "init_ctrl4_0x88");
    check_true(accel.lastWrite(REG_FIFO_CTRL_A) == 0x00, "init_fifo_ctrl_0x00");
    check_true(accel.lastWrite(0x21) == 0x00 && accel.lastWrite(0x22) == 0x00 &&
               accel.lastWrite(REG_CTRL5_A) == 0x00 && accel.lastWrite(REG_CTRL6_A) == 0x00,
               "init_ctrl2356_zeroed");
    check_true(mag.lastWrite(REG_CRA_M) == 0x10, "init_cra_0x10_15hz");
    check_true(mag.lastWrite(REG_CRB_M) == 0x20, "init_crb_0x20_1p3gauss");
    check_true(mag.lastWrite(REG_MR_M) == 0x00, "init_mr_0x00_continuous");

    // --- Minimal: read_acceleration / read_magnetic ---
    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);
    check_true(std::fabs(ax - 0.512f) < 1e-3f, "read_acceleration_x_0p512g");
    check_true(std::fabs(ay - 0.0f) < 1e-6f, "read_acceleration_y_zero");
    check_true(std::fabs(az - 1.024f) < 1e-3f, "read_acceleration_z_1p024g");

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);
    check_true(std::fabs(mx - 100.0f / 1100.0f * 100.0f) < 1e-3f, "read_magnetic_x_100ct");
    check_true(std::fabs(my - 50.0f / 1100.0f * 100.0f) < 1e-3f, "read_magnetic_y_reordered");
    check_true(std::fabs(mz - 200.0f / 980.0f * 100.0f) < 1e-3f, "read_magnetic_z_zgain");

    // --- Full driver ---
    LsmMock accel2, mag2;
    prefill(accel2);
    prefill(mag2);
    LSM303Full full(accel2, mag2);
    check_true(true, "construct_full");

    // --- set_range: RMW CTRL4 FS bits, cached sensitivity ---
    full.set_range(8);
    check_true(accel2.lastWrite(REG_CTRL4_A) == 0xA8, "set_range_8g");
    // 0x2000 >> 4 = 512 counts at 4 mg/LSB (HR, ±8 g) = 2.048 g.
    full.read_acceleration(ax, ay, az);
    check_true(std::fabs(ax - 2.048f) < 1e-3f, "set_range_updates_sensitivity");
    full.set_range(3);  // invalid — rejected without a bus write
    check_true(accel2.writeCount(REG_CTRL4_A) == 2, "set_range_invalid_rejected");

    // --- set_data_rate: ODR codes and mode validation ---
    full.set_data_rate(50);
    check_true(accel2.lastWrite(REG_CTRL1_A) == 0x47, "set_data_rate_50hz");
    int ctrl1_writes = accel2.writeCount(REG_CTRL1_A);
    full.set_data_rate(5376);  // low-power only — normal mode rejects
    check_true(accel2.writeCount(REG_CTRL1_A) == ctrl1_writes, "set_data_rate_5376_rejected");
    full.set_data_rate(1620);  // low-power only
    check_true(accel2.writeCount(REG_CTRL1_A) == ctrl1_writes, "set_data_rate_1620_normal_rejected");
    full.set_data_rate(7);     // unknown rate
    check_true(accel2.writeCount(REG_CTRL1_A) == ctrl1_writes, "set_data_rate_unknown_rejected");
    full.set_mode(0);          // low-power
    check_true((accel2.lastWrite(REG_CTRL1_A) & 0x08) != 0, "set_mode_0_lpen");
    check_true((accel2.lastWrite(REG_CTRL4_A) & 0x08) == 0, "set_mode_0_hr_cleared");
    full.set_data_rate(1620);
    check_true(accel2.lastWrite(REG_CTRL1_A) == 0x8F, "set_data_rate_1620_lowpower");
    full.set_data_rate(1344);  // normal / high-resolution only — LP rejects
    check_true(accel2.lastWrite(REG_CTRL1_A) == 0x8F, "set_data_rate_1344_lowpower_rejected");

    // --- mode-dependent shift and sensitivity ---
    // Still low-power: 8-bit shift, 16 mg/LSB at ±2 g.
    full.set_range(2);
    int16_t rx16, ry16, rz16;
    full.read_acceleration_raw(rx16, ry16, rz16);
    check_true(rx16 == (8192 >> 8) && rz16 == (16384 >> 8), "read_acceleration_raw_lowpower");
    full.read_acceleration(ax, ay, az);
    check_true(std::fabs(ax - 0.512f) < 1e-3f, "read_acceleration_lowpower_16mg");
    full.set_mode(2);  // back to high-resolution
    check_true((accel2.lastWrite(REG_CTRL4_A) & 0x08) != 0, "set_mode_2_hr");
    full.read_acceleration_raw(rx16, ry16, rz16);
    check_true(rx16 == (8192 >> 4) && rz16 == (16384 >> 4), "read_acceleration_raw_hr");

    // --- set_axes / power_down ---
    full.set_axes(false, true, false);
    check_true((accel2.lastWrite(REG_CTRL1_A) & 0x07) == 0x02, "set_axes_y_only");
    full.set_axes();
    check_true((accel2.lastWrite(REG_CTRL1_A) & 0x07) == 0x07, "set_axes_all_on");
    full.power_down();
    check_true((accel2.lastWrite(REG_CTRL1_A) & 0xF0) == 0x00, "power_down_odr_zero");
    full.set_data_rate(100);

    // --- status / data-ready ---
    accel2.set(REG_STATUS_A, { 0x08 });
    check_true(full.new_data_available(), "new_data_available_zyxda");
    check_true(full.read_status() == 0x08, "read_status");
    accel2.set(REG_STATUS_A, { 0x00 });
    check_true(!full.new_data_available(), "new_data_available_clear");
    check_true(full.read_status() == 0x00, "read_status_zero");

    // --- high-pass ---
    full.set_high_pass(1, 2, true, false, true, false);
    check_true(accel2.lastWrite(0x21) == 0x6A, "set_high_pass_ctrl2");  // HPM=01|HPCF=2|FDS|HPIS2
    full.set_reference(0x55);
    check_true(accel2.lastWrite(0x26) == 0x55, "set_reference");
    accel2.set(REG_STATUS_A, { 0x00 });
    accel2.set(0x26, { 0x77 });
    check_true(full.reset_high_pass() == 0x77, "reset_high_pass_reads_reference");

    // --- FIFO ---
    full.set_fifo(2, 16);
    check_true(accel2.lastWrite(REG_FIFO_CTRL_A) == 0x90, "set_fifo_stream_16");
    check_true((accel2.lastWrite(REG_CTRL5_A) & 0x40) != 0, "set_fifo_enables_fifo_en");
    full.set_fifo(0);
    check_true(accel2.lastWrite(REG_FIFO_CTRL_A) == 0x00, "set_fifo_bypass_ctrl");
    check_true((accel2.lastWrite(REG_CTRL5_A) & 0x40) == 0, "set_fifo_bypass_clears_fifo_en");
    full.set_fifo(2, 16);
    // FIFO_SRC: OVRN=1, EMPTY=0, FSS=4.
    accel2.set(REG_FIFO_SRC_A, { 0x44 });
    check_true(full.fifo_level() == 4, "fifo_level_fss");
    bool wtm, ovrn, empty;
    uint8_t level;
    full.read_fifo_status(wtm, ovrn, empty, level);
    check_true(!wtm && ovrn && !empty && level == 4, "read_fifo_status_flags");
    accel2.set(REG_FIFO_SRC_A, { 0xB5 });  // WTM=1, EMPTY=1, level 0x15
    full.read_fifo_status(wtm, ovrn, empty, level);
    check_true(wtm && !ovrn && empty && level == 0x15, "read_fifo_status_wtm_empty");
    accel2.set(REG_FIFO_SRC_A, { 0x44 });
    float fx[8], fy[8], fz[8];
    uint8_t popped = full.read_fifo(fx, fy, fz, 8);
    check_true(popped == 4, "read_fifo_pops_fss");
    check_true(std::fabs(fx[0] - 0.512f) < 1e-3f && std::fabs(fz[0] - 1.024f) < 1e-3f,
               "read_fifo_values_scaled");  // 512 * 1 mg and 1024 * 1 mg at ±2 g HR
    popped = full.read_fifo(fx, fy, fz, 2);
    check_true(popped == 2, "read_fifo_caps_max_samples");

    // --- free-fall: CFG=0x95, threshold/duration encoding at ±16 g, 100 Hz ---
    full.set_range(16);
    full.set_free_fall(1, 0.35f, 30);
    check_true(accel2.lastWrite(REG_INT1_CFG_A) == 0x95, "set_free_fall_cfg_0x95");
    check_true(accel2.lastWrite(REG_INT1_THS_A) == 3, "set_free_fall_ths_16g");  // round(0.35*128/16)=3
    check_true(accel2.lastWrite(REG_INT1_DUR_A) == 3, "set_free_fall_duration"); // round(30ms*0.1)=3
    full.set_free_fall(2, 2.0f, 0);
    check_true(accel2.lastWrite(REG_INT2_CFG_A) == 0x95, "set_free_fall_gen2_cfg");
    check_true(accel2.lastWrite(REG_INT2_THS_A) == 16, "set_free_fall_gen2_ths"); // round(2*128/16)

    // --- motion interrupt encoding ---
    full.set_motion_interrupt(1, 0x15, 1.0f, 15, true);
    check_true(accel2.lastWrite(REG_INT1_CFG_A) == 0x95, "set_motion_interrupt_and_mask");
    check_true(accel2.lastWrite(REG_INT1_THS_A) == 8, "set_motion_interrupt_ths");
    check_true(accel2.lastWrite(REG_INT1_DUR_A) == 2, "set_motion_interrupt_dur_15ms");
    int cfg_writes = accel2.writeCount(REG_INT1_CFG_A);
    full.set_motion_interrupt(3, 0x01, 1.0f);  // invalid generator — rejected
    check_true(accel2.writeCount(REG_INT1_CFG_A) == cfg_writes,
               "set_motion_interrupt_invalid_gen");

    // --- click: axis-mask spreading ---
    full.set_click(1, 2, 0.5f, 10, 20, 50);
    // single=1 (X) → XS bit1 = 0x02; double=2 (Y) → YD bit2 = 0x04; CFG = 0x06.
    check_true(accel2.lastWrite(REG_CLICK_CFG_A) == 0x06, "set_click_axis_spread");
    check_true(accel2.lastWrite(REG_CLICK_THS_A) == 4, "set_click_ths");      // round(0.5*128/16)
    check_true(accel2.lastWrite(REG_TIME_LIMIT_A) == 1, "set_click_time_limit");
    check_true(accel2.lastWrite(REG_TIME_LAT_A) == 2, "set_click_time_latency");
    check_true(accel2.lastWrite(REG_TIME_WIN_A) == 5, "set_click_time_window");
    full.set_click(0, 0, 0.5f, 10, 20, 50);
    check_true(accel2.lastWrite(REG_CLICK_CFG_A) == 0x00, "set_click_off");
    full.set_click(4, 0, 0.5f, 10, 20, 50);  // Z single → ZS bit5 = 0x20
    check_true(accel2.lastWrite(REG_CLICK_CFG_A) == 0x20, "set_click_z_single");
    full.set_click(8, 0, 0.5f, 10, 20, 50);  // invalid mask — rejected
    check_true(accel2.lastWrite(REG_CLICK_CFG_A) == 0x20, "set_click_invalid_mask_rejected");

    // --- 6D orientation + latch + polarity ---
    full.set_orientation(1, false, false);
    check_true(accel2.lastWrite(REG_INT1_CFG_A) == 0x7F, "set_orientation_movement_6d");
    full.set_orientation(1, true, false);
    check_true(accel2.lastWrite(REG_INT1_CFG_A) == 0xFF, "set_orientation_position_6d");
    full.set_orientation(2, false, true);
    check_true(accel2.lastWrite(REG_INT2_CFG_A) == 0x7F, "set_orientation_gen2");
    check_true((accel2.lastWrite(REG_CTRL5_A) & 0x01) != 0, "set_orientation_4d_int2");
    full.set_latch(1, true);
    check_true((accel2.lastWrite(REG_CTRL5_A) & 0x08) != 0, "set_latch_int1");
    full.set_latch(1, false);
    check_true((accel2.lastWrite(REG_CTRL5_A) & 0x08) == 0, "set_latch_int1_clear");
    full.set_interrupt_polarity(true);
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x02) != 0, "set_interrupt_polarity_active_low");
    full.set_interrupt_polarity(false);
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x02) == 0, "set_interrupt_polarity_clear");

    // --- per-generator source reads ---
    accel2.set(REG_INT1_SRC_A, { 0x40 });
    check_true(full.read_interrupt_source(1) == 0x40, "read_interrupt_source_gen1");
    accel2.set(REG_INT2_SRC_A, { 0x02 });
    check_true(full.read_interrupt_source(2) == 0x02, "read_interrupt_source_gen2");
    accel2.set(REG_CLICK_SRC_A, { 0x40 });
    check_true(full.read_click_source() == 0x40, "read_click_source");

    // --- INT1/INT2 routing ---
    full.enable_interrupt(LSM303Full::SOURCE_IA1, 1);
    check_true((accel2.lastWrite(REG_CTRL3_A) & 0x40) != 0, "enable_ia1_pin1_ctrl3");
    full.enable_interrupt(LSM303Full::SOURCE_CLICK, 2);
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x80) != 0, "enable_click_pin2_ctrl6");
    full.enable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);
    check_true((accel2.lastWrite(REG_CTRL3_A) & 0x10) != 0, "enable_data_ready_drdy1");
    full.enable_interrupt(LSM303Full::SOURCE_WATERMARK, 1);
    check_true((accel2.lastWrite(REG_CTRL3_A) & 0x04) != 0, "enable_watermark");
    full.enable_interrupt(LSM303Full::SOURCE_OVERRUN, 1);
    check_true((accel2.lastWrite(REG_CTRL3_A) & 0x01) != 0, "enable_overrun");
    full.enable_interrupt(LSM303Full::SOURCE_IA2, 2);
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x20) != 0, "enable_ia2_pin2");
    full.enable_interrupt(LSM303Full::SOURCE_MAG_DATA_READY, 1);  // never routable
    check_true((accel2.lastWrite(REG_CTRL3_A) & 0x80) == 0, "enable_mag_drdy_rejected");
    full.enable_interrupt(LSM303Full::SOURCE_BOOT, 1);  // INT2-only — rejected
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x10) == 0, "enable_boot_pin1_rejected");
    full.enable_interrupt(LSM303Full::SOURCE_BOOT, 2);
    check_true((accel2.lastWrite(REG_CTRL6_A) & 0x10) != 0, "enable_boot_pin2");

    // --- poll_interrupt combined status ---
    // Enabled: DATA_READY, WATERMARK, OVERRUN, IA1, IA2, CLICK, BOOT.
    accel2.set(REG_STATUS_A, { 0x08 });     // ZYXDA
    accel2.set(REG_FIFO_SRC_A, { 0xC0 });   // WTM + OVRN
    accel2.set(REG_INT1_SRC_A, { 0x40 });   // IA
    accel2.set(REG_INT2_SRC_A, { 0x40 });   // IA
    accel2.set(REG_CLICK_SRC_A, { 0x40 });  // IA
    mag2.set(REG_SR_M, { 0x01 });           // DRDY
    uint16_t status = full.poll_interrupt();
    check_true(status == (LSM303Full::SOURCE_DATA_READY | LSM303Full::SOURCE_WATERMARK |
                          LSM303Full::SOURCE_OVERRUN | LSM303Full::SOURCE_IA1 |
                          LSM303Full::SOURCE_IA2 | LSM303Full::SOURCE_CLICK |
                          LSM303Full::SOURCE_MAG_DATA_READY),
               "poll_interrupt_combined");

    // Disable everything → poll reads only the magnetometer SR (always reported).
    full.disable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);
    full.disable_interrupt(LSM303Full::SOURCE_WATERMARK, 1);
    full.disable_interrupt(LSM303Full::SOURCE_OVERRUN, 1);
    full.disable_interrupt(LSM303Full::SOURCE_IA1, 1);
    full.disable_interrupt(LSM303Full::SOURCE_IA2, 2);
    full.disable_interrupt(LSM303Full::SOURCE_CLICK, 2);
    full.disable_interrupt(LSM303Full::SOURCE_BOOT, 2);
    check_true(accel2.lastWrite(REG_CTRL3_A) == 0x00, "disable_interrupts_ctrl3_clear");
    check_true(accel2.lastWrite(REG_CTRL6_A) == 0x00, "disable_interrupts_ctrl6_clear");
    accel2.set(REG_STATUS_A, { 0x08 });
    accel2.set(REG_INT1_SRC_A, { 0x40 });
    mag2.set(REG_SR_M, { 0x00 });
    status = full.poll_interrupt();
    check_true(status == 0, "poll_interrupt_all_disabled_zero");
    mag2.set(REG_SR_M, { 0x01 });
    status = full.poll_interrupt();
    check_true(status == LSM303Full::SOURCE_MAG_DATA_READY, "poll_interrupt_mag_drdy_always");

    // --- on_interrupt / off_interrupt edge path ---
    // No int pin wired → callback is simply not armed, no crash.
    full.on_interrupt(lsm303_cb1, 1);
    check_true(accel2.pin.handler == nullptr, "on_interrupt_no_edge_without_int_pin");
    full.off_interrupt(1);

    // Rebuild with an intPin on the accelerometer connection.
    LsmMock accel3(true), mag3(true);
    prefill(accel3);
    prefill(mag3);
    LSM303Full full3(accel3, mag3);
    full3.enable_interrupt(LSM303Full::SOURCE_IA1, 1);
    accel3.set(REG_INT1_SRC_A, { 0x40 });
    mag3.set(REG_SR_M, { 0x01 });

    static uint16_t last_status = 0;
    (void)last_status;
    full3.on_interrupt(lsm303_cb1, 1);
    check_true(accel3.pin.handler != nullptr, "on_interrupt_arms_edge");
    check_true(accel3.pin.trigger == InputPin::kFalling, "on_interrupt_uses_falling");
    accel3.pin.handler();
    check_true(g_status_pin1 == (LSM303Full::SOURCE_IA1 | LSM303Full::SOURCE_MAG_DATA_READY),
               "on_interrupt_dispatches_poll_status");
    // Second subscription on INT2: callback stored, shared edge still armed once.
    full3.on_interrupt(lsm303_cb2, 2);
    accel3.pin.handler();
    check_true(g_status_pin1 == g_status_pin2 &&
               g_status_pin2 == (LSM303Full::SOURCE_IA1 | LSM303Full::SOURCE_MAG_DATA_READY),
               "on_interrupt_both_pins_share_edge");
    full3.off_interrupt(1);
    full3.off_interrupt(2);
    check_true(accel3.pin.handler == nullptr, "off_interrupt_dismounts_edge");

    // --- magnetometer configuration ---
    full.set_mag_range(810);
    check_true(mag2.lastWrite(REG_CRB_M) == 0xE0, "set_mag_range_810_crb_0xE0");
    full.set_mag_range(999);  // invalid — rejected
    check_true(mag2.lastWrite(REG_CRB_M) == 0xE0, "set_mag_range_invalid_rejected");
    full.set_mag_range(470);
    check_true(mag2.lastWrite(REG_CRB_M) == 0xA0, "set_mag_range_470_crb_0xA0");
    // Mag raw X: 100 counts; at ±470 µT the X/Y gain is 400 LSB/gauss
    // → 100 / 400 * 100 = 25 µT.
    full.read_magnetic(mx, my, mz);
    check_true(std::fabs(mx - 25.0f) < 1e-3f, "set_mag_range_updates_gains");

    full.set_mag_data_rate(220);
    // CRA after init is 0x10 (no temp bit) → (0x10 & 0x80) | (7 << 2) = 0x1C.
    check_true(mag2.lastWrite(REG_CRA_M) == 0x1C, "set_mag_data_rate_220");
    full.set_mag_data_rate(3.7f);  // invalid rate — rejected
    check_true(mag2.lastWrite(REG_CRA_M) == 0x1C, "set_mag_data_rate_invalid_rejected");
    full.set_mag_data_rate(0.75f);  // DO=0 → 0x00
    check_true(mag2.lastWrite(REG_CRA_M) == 0x00, "set_mag_data_rate_0p75");
    full.set_mag_data_rate(15);
    full.set_mag_mode(1);
    check_true(mag2.lastWrite(REG_MR_M) == 0x01, "set_mag_mode_single");
    full.set_mag_mode(2);
    check_true(mag2.lastWrite(REG_MR_M) == 0x02, "set_mag_mode_sleep");
    full.set_mag_mode(3);  // invalid — rejected
    check_true(mag2.lastWrite(REG_MR_M) == 0x02, "set_mag_mode_invalid_rejected");

    // --- read_magnetic_raw reordering + signs ---
    // XH/XL = -4096; ZH/ZL = 1; YH/YL = 2047.
    mag2.set(REG_OUT_M, { 0xF0, 0x00, 0x00, 0x01, 0x07, 0xFF });
    int16_t rmx, rmy, rmz;
    full.read_magnetic_raw(rmx, rmy, rmz);
    check_true(rmx == -4096, "read_magnetic_raw_x_signed");
    check_true(rmy == 2047, "read_magnetic_raw_y_reordered");
    check_true(rmz == 1, "read_magnetic_raw_z");

    // --- mag status / identity ---
    mag2.set(REG_SR_M, { 0x03 });
    check_true(full.mag_data_ready(), "mag_data_ready");
    bool drdy, lock;
    full.read_mag_status(drdy, lock);
    check_true(drdy && lock, "read_mag_status_drdy_lock");
    uint8_t ida, idb, idc;
    full.read_mag_identity(ida, idb, idc);
    check_true(ida == 0x48 && idb == 0x34 && idc == 0x33, "read_mag_identity_h43");

    // --- temperature ---
    full.enable_temperature(true);
    check_true((mag2.lastWrite(REG_CRA_M) & 0x80) != 0, "enable_temperature");
    full.enable_temperature(false);
    check_true((mag2.lastWrite(REG_CRA_M) & 0x80) == 0, "disable_temperature");
    // raw16 0x0180 = 384; >> 4 = 24; /8 = 3.0 °C relative delta. First use
    // also enables TEMP_EN via read-modify-write (CRA was 0x10 → 0x90).
    mag2.set(REG_TEMP_M, { 0x01, 0x80 });
    float temp = full.read_temperature_delta();
    check_true(std::fabs(temp - 3.0f) < 1e-3f, "read_temperature_delta_3c");
    check_true(mag2.lastWrite(REG_CRA_M) == 0x90, "read_temperature_delta_enables_temp_en");

    // --- heading ---
    // Flat Z at 1 g keeps roll/pitch at 0 → heading = atan2(my, mx).
    accel2.set(ACC_OUT, { 0x00, 0x00, 0x00, 0x00, 0x00, 0x40 });
    mag2.set(REG_OUT_M, { 0x00, 0x64, 0x00, 0xC8, 0x00, 0x32 });  // X=100, Z=200, Y=50
    float hdg = full.heading(true);
    check_true(hdg >= 0.0f && hdg < 360.0f, "heading_in_range");
    check_true(std::fabs(hdg - 26.565f) < 0.5f, "heading_value_26p565");
    float hdg_flat = full.heading(false);
    check_true(std::fabs(hdg_flat - 26.565f) < 0.5f, "heading_flat_matches");

    // --- reboot: CTRL5 | 0x80 (CTRL5 currently 0x41: FIFO_EN + 4D_INT2) ---
    full.reboot();
    check_true(accel2.lastWrite(REG_CTRL5_A) == 0xC1, "reboot_sets_boot_bit");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
