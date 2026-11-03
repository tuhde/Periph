#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "LIS3DH.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_WHO_AM_I    = 0x0F;
static const uint8_t REG_OUT_X_L     = 0x28;
static const uint8_t REG_OUT_ADC1_L  = 0x08;
static const uint8_t REG_OUT_ADC2_L  = 0x0A;
static const uint8_t REG_OUT_ADC3_H  = 0x0D;
static const uint8_t REG_CTRL_REG1   = 0x20;
static const uint8_t REG_CTRL_REG2   = 0x21;
static const uint8_t REG_CTRL_REG3   = 0x22;
static const uint8_t REG_CTRL_REG4   = 0x23;
static const uint8_t REG_CTRL_REG5   = 0x24;
static const uint8_t REG_CTRL_REG6   = 0x25;
static const uint8_t REG_REFERENCE   = 0x26;
static const uint8_t REG_STATUS      = 0x27;
static const uint8_t REG_STATUS_AUX  = 0x07;
static const uint8_t REG_FIFO_CTRL   = 0x2E;
static const uint8_t REG_FIFO_SRC    = 0x2F;
static const uint8_t REG_INT1_CFG    = 0x30;
static const uint8_t REG_INT1_SRC    = 0x31;
static const uint8_t REG_INT1_THS    = 0x32;
static const uint8_t REG_INT1_DUR    = 0x33;
static const uint8_t REG_INT2_CFG    = 0x34;
static const uint8_t REG_INT2_SRC    = 0x35;
static const uint8_t REG_CLICK_CFG   = 0x38;
static const uint8_t REG_CLICK_SRC   = 0x39;
static const uint8_t REG_CLICK_THS   = 0x3A;
static const uint8_t REG_TIME_LIMIT  = 0x3B;
static const uint8_t REG_TIME_LATENCY = 0x3C;
static const uint8_t REG_TIME_WINDOW = 0x3D;
static const uint8_t REG_ACT_THS     = 0x3E;
static const uint8_t REG_ACT_DUR     = 0x3F;
static const uint8_t REG_TEMP_CFG    = 0x1F;
static const uint8_t REG_CTRL_REG0   = 0x1E;

// Last write to `reg` in the mock's write log (register writes are [reg, value]).
static bool last_write(const I2CConnectionMock& mock, uint8_t reg, uint8_t& out) {
    bool found = false;
    out = 0;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == reg) { out = w[1]; found = true; }
    }
    return found;
}

static void fresh_mock(I2CConnectionMock& m) {
    m.setRegister(REG_WHO_AM_I, {0x33});
    // x = 0x3E80 -> +16000 -> 1000 counts (1.000 g at 1 mg/LSB),
    // y = 0x0000 -> 0 g, z = 0xE0C0 -> -8000 -> -500 counts (-0.500 g).
    m.setRegister(REG_OUT_X_L | 0x80, {0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0});
}

int main() {
    // --- Minimal: init sequence -------------------------------------------

    {
        I2CConnectionMock mock;
        fresh_mock(mock);
        LIS3DHMinimal accel(mock);
        check_true(true, "construct_minimal");

        // Step 3: control registers cleared (the chip has no soft reset).
        uint8_t v;
        check_true(last_write(mock, REG_CTRL_REG2, v) && v == 0x00, "init_clears_ctrl_reg2");
        check_true(last_write(mock, REG_CTRL_REG3, v) && v == 0x00, "init_clears_ctrl_reg3");
        check_true(last_write(mock, REG_CTRL_REG5, v) && v == 0x00, "init_clears_ctrl_reg5");
        check_true(last_write(mock, REG_CTRL_REG6, v) && v == 0x00, "init_clears_ctrl_reg6");
        check_true(last_write(mock, REG_FIFO_CTRL, v) && v == 0x00, "init_clears_fifo_ctrl");
        check_true(last_write(mock, REG_TEMP_CFG, v) && v == 0x00, "init_clears_temp_cfg");

        // Steps 4/5: CTRL_REG4 = 0x88 (BDU, ±2 g, HR), CTRL_REG1 = 0x57 (100 Hz, axes on).
        check_true(last_write(mock, REG_CTRL_REG4, v) && v == 0x88, "init_ctrl_reg4_0x88");
        check_true(last_write(mock, REG_CTRL_REG1, v) && v == 0x57, "init_ctrl_reg1_0x57");

        // read(): x = 1.0 g, y = 0.0 g, z = -0.5 g.
        float x, y, z;
        accel.read(x, y, z);
        check_true(fabsf(x - 1.0f) < 1e-6f, "read_x_1g");
        check_true(fabsf(y - 0.0f) < 1e-6f, "read_y_zero_g");
        check_true(fabsf(z - (-0.5f)) < 1e-6f, "read_z_minus_half_g");
    }

    // --- Register addressing: auto-increment bit on the burst ---------------

    {
        // The mock records read() calls with the sub-address; the 6-byte
        // burst must go out as 0xA8 (OUT_X_L | 0x80).
        class RecordingConn : public I2CConnectionMock {
        public:
            std::vector<std::pair<uint32_t, size_t>> reg_reads;
        protected:
            void _write_read(const uint8_t* data, size_t data_len,
                             uint8_t* buf, size_t buf_len) override {
                uint32_t reg = 0;
                for (size_t i = 0; i < data_len; i++) reg = (reg << 8) | data[i];
                reg_reads.push_back(std::make_pair(reg, buf_len));
                I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
            }
        };

        RecordingConn mock;
        fresh_mock(mock);
        LIS3DHMinimal accel(mock);
        float x, y, z;
        accel.read(x, y, z);

        bool who_ok = false, burst_ok = false;
        for (const auto& r : mock.reg_reads) {
            if (r.first == REG_WHO_AM_I && r.second == 1) who_ok = true;
            if (r.first == (uint32_t)(REG_OUT_X_L | 0x80) && r.second == 6) burst_ok = true;
        }
        check_true(who_ok, "who_am_i_single_byte_read");
        check_true(burst_ok, "burst_read_sets_auto_increment_bit");
    }

    // --- Full driver ----------------------------------------------------------

    I2CConnectionMock mock2;
    fresh_mock(mock2);
    LIS3DHFull full(mock2);
    check_true(true, "construct_full");

    // set_range: FS field of CTRL_REG4 (bits 5:4) — 0x88 (±2) -> 0x98 (±4)
    // -> 0xB8 (±16) -> 0x88 (±2).
    uint8_t v;
    full.set_range(4);
    check_true(last_write(mock2, REG_CTRL_REG4, v) && v == 0x98, "set_range_4g");
    full.set_range(16);
    check_true(last_write(mock2, REG_CTRL_REG4, v) && v == 0xB8, "set_range_16g");
    full.set_range(2);
    check_true(last_write(mock2, REG_CTRL_REG4, v) && v == 0x88, "set_range_2g");

    // set_mode: LPen (CTRL_REG1 bit 3) and HR (CTRL_REG4 bit 3).
    full.set_mode(1);  // 12-bit HR -> 10-bit normal
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0x08) == 0x00, "set_mode_normal_clears_lpen");
    check_true(last_write(mock2, REG_CTRL_REG4, v) && (v & 0x08) == 0x00, "set_mode_normal_clears_hr");
    full.set_mode(2);  // -> 12-bit HR
    check_true(last_write(mock2, REG_CTRL_REG4, v) && (v & 0x08) == 0x08, "set_mode_hr_sets_hr");
    full.set_mode(0);  // -> 8-bit low-power
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0x08) == 0x08, "set_mode_lp_sets_lpen");
    full.set_mode(2);

    // set_data_rate: ODR field (bits 7:4). 400 Hz -> code 0x07.
    full.set_data_rate(400);
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0xF0) == 0x70, "set_data_rate_400hz");
    full.set_data_rate(100);
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0xF0) == 0x50, "set_data_rate_100hz");

    // set_axes: Xen/Yen/Zen = CTRL_REG1 bits 2:0.
    full.set_axes(false, true, true);
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0x07) == 0x06, "set_axes_x_off");
    full.set_axes(true, true, true);
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0x07) == 0x07, "set_axes_all_on");

    // power_down: ODR = 0000, register contents retained.
    full.power_down();
    check_true(last_write(mock2, REG_CTRL_REG1, v) && (v & 0xF0) == 0x00, "power_down_clears_odr");
    full.set_data_rate(100);

    // read_raw: signed counts at the current resolution (12-bit here).
    int16_t rx, ry, rz;
    full.read_raw(rx, ry, rz);
    check_true(rx == 1000 && ry == 0 && rz == -500, "read_raw_counts");

    // new_data_available / read_status.
    mock2.setRegister(REG_STATUS, {0x08});
    check_true(full.new_data_available(), "new_data_available_set");
    mock2.setRegister(REG_STATUS, {0x00});
    check_true(!full.new_data_available(), "new_data_available_clear");
    check_true(full.read_status() == 0x00, "read_status");

    // read_temperature_delta: 8-bit two's complement of OUT_ADC3_H.
    mock2.setRegister(REG_OUT_ADC3_H, {0xFE});  // -2
    mock2.setRegister(REG_TEMP_CFG, {0x00});
    check_true(full.read_temperature_delta() == -2, "read_temperature_delta_minus2");
    check_true(last_write(mock2, REG_TEMP_CFG, v) && v == 0xC0, "temperature_enables_adc_and_temp");

    // read_adc: 10-bit, derived mapping 1.200 V ± 400 mV over ±512 counts.
    mock2.setRegister(REG_OUT_ADC1_L | 0x80, {0x00, 0x00});
    check_true(fabsf(full.read_adc(1) - 1.200f) < 1e-3f, "read_adc_channel_1");
    mock2.setRegister(REG_OUT_ADC2_L | 0x80, {0x00, 0x40});  // 256 counts -> +200 mV
    check_true(fabsf(full.read_adc(2) - 1.400f) < 1e-3f, "read_adc_channel_2");

    // enable_adc.
    mock2.setRegister(REG_TEMP_CFG, {0x00});
    full.enable_adc(true);
    check_true(last_write(mock2, REG_TEMP_CFG, v) && (v & 0x80) == 0x80, "enable_adc_sets_bit");
    full.enable_adc(false);
    check_true(last_write(mock2, REG_TEMP_CFG, v) && (v & 0x80) == 0x00, "enable_adc_clears_bit");

    // set_high_pass: HPM bits 7:6, HPCF bits 5:4, FDS/HPCLICK/HP_IA2/HP_IA1.
    full.set_high_pass(1, 2, true, true, false, true);
    check_true(last_write(mock2, REG_CTRL_REG2, v) && v == 0x6D, "set_high_pass_all");
    full.set_high_pass(0, 0);
    check_true(last_write(mock2, REG_CTRL_REG2, v) && v == 0x00, "set_high_pass_defaults");

    // reset_high_pass reads REFERENCE.
    mock2.setRegister(REG_REFERENCE, {0x5A});
    check_true(full.reset_high_pass() == 0x5A, "reset_high_pass_reads_reference");

    // set_reference.
    full.set_reference(0x40);
    check_true(last_write(mock2, REG_REFERENCE, v) && v == 0x40, "set_reference");

    // --- FIFO -----------------------------------------------------------------

    // set_fifo: FM bits 7:6, TR bit 5, FTH bits 4:0; FIFO_EN in CTRL_REG5.
    full.set_fifo(2, 16);
    check_true(last_write(mock2, REG_FIFO_CTRL, v) && v == 0x90, "set_fifo_stream_mode");
    check_true(last_write(mock2, REG_CTRL_REG5, v) && (v & 0x40) == 0x40, "set_fifo_enables_fifo_en");
    full.set_fifo(0);
    check_true(last_write(mock2, REG_FIFO_CTRL, v) && v == 0x00, "set_fifo_bypass");
    check_true(last_write(mock2, REG_CTRL_REG5, v) && (v & 0x40) == 0x00, "set_fifo_bypass_clears_fifo_en");

    // fifo_level / read_fifo_status.
    mock2.setRegister(REG_FIFO_SRC, {0x80 | 0x40 | 0x10});
    check_true(full.fifo_level() == 0x10, "fifo_level");
    bool wm = false, ov = false, empty = true;
    uint8_t lvl = 0;
    full.read_fifo_status(wm, ov, empty, lvl);
    check_true(wm && ov && !empty && lvl == 0x10, "read_fifo_status");

    // read_fifo: one 6-byte burst per sample set, never more than FSS.
    mock2.setRegister(REG_FIFO_SRC, {0x01});
    mock2.setRegister(REG_OUT_X_L | 0x80, {0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0});
    float xb[4], yb[4], zb[4];
    uint8_t n = full.read_fifo(xb, yb, zb, 4);
    check_true(n == 1, "read_fifo_one_sample");
    check_true(fabsf(xb[0] - 1.0f) < 1e-6f && fabsf(zb[0] + 0.5f) < 1e-6f, "read_fifo_values");

    // --- Interrupt generators ---------------------------------------------------

    // set_motion_interrupt: INTx_CFG = AOI | events, INTx_THS, INTx_DURATION.
    // At ±2 g the threshold LSB is 16 mg; 0.5 g -> 31 counts. 30 ms at
    // 100 Hz -> 3.
    full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    check_true(last_write(mock2, REG_INT1_CFG, v) && v == 0xBF, "motion_int_cfg");
    check_true(last_write(mock2, REG_INT1_THS, v) && v == 31, "motion_int_threshold");
    check_true(last_write(mock2, REG_INT1_DUR, v) && v == 3, "motion_int_duration");
    full.set_motion_interrupt(2, 0x00, 0.5f, 0, false);
    check_true(last_write(mock2, REG_INT2_CFG, v) && v == 0x00, "motion_int2_cfg");

    // set_free_fall: INTx_CFG = 0x95 (AOI + all axes low).
    full.set_free_fall(1, 0.35f, 30);
    check_true(last_write(mock2, REG_INT1_CFG, v) && v == 0x95, "free_fall_cfg");

    // set_orientation: 6D movement = AOI|6D = 0x40, position = 0xC0; 4D
    // sets D4D_INTx in CTRL_REG5 and clears the Z zones (0x0F).
    full.set_orientation(1, false, false);
    check_true(last_write(mock2, REG_INT1_CFG, v) && v == 0x7F, "orientation_6d_movement");
    full.set_orientation(2, true, true);
    check_true(last_write(mock2, REG_INT2_CFG, v) && v == 0xCF, "orientation_6d_position_4d");
    check_true(last_write(mock2, REG_CTRL_REG5, v) && (v & 0x01) == 0x01, "orientation_4d_bit");
    full.set_orientation(1, false, false);

    // set_latch: LIR_INT1 = CTRL_REG5 bit 3, LIR_INT2 = bit 1.
    full.set_latch(1, true);
    check_true(last_write(mock2, REG_CTRL_REG5, v) && (v & 0x08) == 0x08, "latch_int1");
    full.set_latch(1, false);
    check_true(last_write(mock2, REG_CTRL_REG5, v) && (v & 0x08) == 0x00, "unlatch_int1");

    // read_interrupt_source returns INTx_SRC.
    mock2.setRegister(REG_INT1_SRC, {0x40});
    check_true(full.read_interrupt_source(1) == 0x40, "read_interrupt_source");
    mock2.setRegister(REG_INT2_SRC, {0x20});
    check_true(full.read_interrupt_source(2) == 0x20, "read_interrupt_source2");

    // --- Click detection --------------------------------------------------------

    // single = Z (4) -> ZS bit 4; double = X (1) -> XD bit 1;
    // threshold 0.5 g at ±2 g -> 31; LIR_Click set.
    full.set_click(0, 0, 0.5f, 10, 20, 50, true);
    check_true(last_write(mock2, REG_CLICK_CFG, v) && v == 0x00, "click_cfg_no_axes");
    full.set_click(4, 1, 0.5f, 10, 20, 50, true);
    check_true(last_write(mock2, REG_CLICK_CFG, v) && v == 0x12, "click_cfg_z_single_x_double");
    check_true(last_write(mock2, REG_CLICK_THS, v) && v == (0x80 | 31), "click_threshold_latch");
    check_true(last_write(mock2, REG_TIME_LIMIT, v) && v == 1, "click_time_limit");
    check_true(last_write(mock2, REG_TIME_LATENCY, v) && v == 2, "click_latency");
    check_true(last_write(mock2, REG_TIME_WINDOW, v) && v == 5, "click_window");

    mock2.setRegister(REG_CLICK_SRC, {0x40});
    check_true(full.read_click_source() == 0x40, "read_click_source");

    // --- Sleep-to-wake activity ---------------------------------------------------

    // ACT_THS same scale as INTx_THS; ACT_DUR = (ms*ODR/1000 - 1) / 8.
    full.set_activity(0.5f, 100);
    check_true(last_write(mock2, REG_ACT_THS, v) && v == 31, "activity_threshold");
    check_true(last_write(mock2, REG_ACT_DUR, v) && v == 1, "activity_duration_rounds_to_one");
    full.set_activity(0.5f, 801);
    check_true(last_write(mock2, REG_ACT_DUR, v) && v == 10, "activity_duration_formula");

    // set_interrupt_polarity: INT_POLARITY = CTRL_REG6 bit 1.
    full.set_interrupt_polarity(true);
    check_true(last_write(mock2, REG_CTRL_REG6, v) && (v & 0x02) == 0x02, "polarity_active_low");
    full.set_interrupt_polarity(false);
    check_true(last_write(mock2, REG_CTRL_REG6, v) && (v & 0x02) == 0x00, "polarity_active_high");

    // enable/disable_interrupt routing.
    mock2.setRegister(REG_CTRL_REG3, {0x00});
    mock2.setRegister(REG_CTRL_REG6, {0x00});
    full.enable_interrupt(LIS3DHFull::SOURCE_DATA_READY);
    check_true(last_write(mock2, REG_CTRL_REG3, v) && (v & 0x10) == 0x10, "enable_data_ready_int1");
    full.enable_interrupt(LIS3DHFull::SOURCE_IA1, 2);
    check_true(last_write(mock2, REG_CTRL_REG6, v) && (v & 0x40) == 0x40, "enable_ia1_int2");
    full.enable_interrupt(LIS3DHFull::SOURCE_ACTIVITY);
    check_true(last_write(mock2, REG_CTRL_REG6, v) && (v & 0x08) == 0x08, "enable_activity_int2_only");
    full.disable_interrupt(LIS3DHFull::SOURCE_DATA_READY);
    check_true(last_write(mock2, REG_CTRL_REG3, v) && (v & 0x10) == 0x00, "disable_data_ready");
    full.disable_interrupt(LIS3DHFull::SOURCE_IA1, 2);
    check_true(last_write(mock2, REG_CTRL_REG6, v) && (v & 0x40) == 0x00, "disable_ia1_int2");

    // poll_interrupt: every enabled source's flag ORed into the status int.
    mock2.setRegister(REG_STATUS, {0x08});
    mock2.setRegister(REG_STATUS_AUX, {0x08});
    mock2.setRegister(REG_FIFO_SRC, {0xC0});
    mock2.setRegister(REG_INT1_SRC, {0x40});
    mock2.setRegister(REG_INT2_SRC, {0x40});
    mock2.setRegister(REG_CLICK_SRC, {0x40});
    full.enable_interrupt(LIS3DHFull::SOURCE_DATA_READY);
    full.enable_interrupt(LIS3DHFull::SOURCE_AUX_DATA_READY);
    full.enable_interrupt(LIS3DHFull::SOURCE_WATERMARK);
    full.enable_interrupt(LIS3DHFull::SOURCE_OVERRUN);
    full.enable_interrupt(LIS3DHFull::SOURCE_IA1, 1);
    full.enable_interrupt(LIS3DHFull::SOURCE_IA2, 1);
    full.enable_interrupt(LIS3DHFull::SOURCE_CLICK, 1);
    check_true(full.poll_interrupt() == 0x7F, "poll_interrupt_all_sources");
    full.disable_interrupt(LIS3DHFull::SOURCE_DATA_READY);
    full.disable_interrupt(LIS3DHFull::SOURCE_AUX_DATA_READY);
    full.disable_interrupt(LIS3DHFull::SOURCE_WATERMARK);
    full.disable_interrupt(LIS3DHFull::SOURCE_OVERRUN);
    full.disable_interrupt(LIS3DHFull::SOURCE_IA1, 1);
    full.disable_interrupt(LIS3DHFull::SOURCE_IA2, 1);
    full.disable_interrupt(LIS3DHFull::SOURCE_CLICK, 1);
    full.disable_interrupt(LIS3DHFull::SOURCE_IA1, 2);
    full.disable_interrupt(LIS3DHFull::SOURCE_ACTIVITY);
    check_true(full.poll_interrupt() == 0, "poll_interrupt_nothing_enabled");

    // set_sdo_pullup: CTRL_REG0 keeps bit 4 set, toggles bit 7 only.
    full.set_sdo_pullup(false);
    check_true(last_write(mock2, REG_CTRL_REG0, v) && v == 0x90, "sdo_pullup_disconnect");
    full.set_sdo_pullup(true);
    check_true(last_write(mock2, REG_CTRL_REG0, v) && v == 0x10, "sdo_pullup_connect");

    // reboot: BOOT bit, then the Initialization Sequence runs again.
    mock2.setRegister(REG_CTRL_REG5, {0x00});
    full.reboot();
    {
        bool boot_seen = false;
        for (const auto& w : mock2.writes()) {
            if (w.size() == 2 && w[0] == REG_CTRL_REG5 && (w[1] & 0x80)) boot_seen = true;
        }
        check_true(boot_seen, "reboot_sets_boot_bit");
    }
    check_true(last_write(mock2, REG_CTRL_REG4, v) && v == 0x88, "reboot_reruns_init_ctrl4");
    check_true(last_write(mock2, REG_CTRL_REG1, v) && v == 0x57, "reboot_reruns_init_ctrl1");

    // --- Self-test ---------------------------------------------------------------

    // Loads the post-self-test output data once the ST bits are set in
    // CTRL_REG4: the per-axis deltas stay inside the 17..360 LSb window.
    class SelfTestMock : public I2CConnectionMock {
    protected:
        void _write(const uint8_t* data, size_t len) override {
            if (len == 2 && data[0] == REG_CTRL_REG4 && (data[1] & 0x06)) {
                setRegister(REG_OUT_X_L | 0x80,
                            {0x80, 0x0C,    // X: 100 -> 200 (delta +100)
                             0x60, 0xEA,    // Y: -200 -> -350 (delta -150)
                             0xC0, 0x12});  // Z: 50 -> 300 (delta +250)
            }
            I2CConnectionMock::_write(data, len);
        }
    };

    SelfTestMock st_mock;
    fresh_mock(st_mock);
    st_mock.setRegister(REG_OUT_X_L | 0x80, {0x40, 0x06, 0x80, 0xF3, 0x20, 0x03});
    LIS3DHFull st_chip(st_mock);
    check_true(st_chip.self_test(1), "self_test_passes");
    check_true(last_write(st_mock, REG_CTRL_REG1, v) && v == 0x57, "self_test_restores_ctrl_reg1");
    check_true(last_write(st_mock, REG_CTRL_REG4, v) && v == 0x88, "self_test_restores_ctrl_reg4");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
