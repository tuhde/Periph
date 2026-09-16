#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "L3G4200D.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class L3G4200DTestAccess : public L3G4200DFull {
public:
    using L3G4200DFull::L3G4200DFull;
    using L3G4200DFull::REG_WHO_AM_I;
    using L3G4200DFull::REG_CTRL_REG1;
    using L3G4200DFull::REG_CTRL_REG2;
    using L3G4200DFull::REG_CTRL_REG3;
    using L3G4200DFull::REG_CTRL_REG4;
    using L3G4200DFull::REG_CTRL_REG5;
    using L3G4200DFull::REG_OUT_TEMP;
    using L3G4200DFull::REG_STATUS;
    using L3G4200DFull::REG_OUT_X_L;
    using L3G4200DFull::REG_OUT_X_H;
    using L3G4200DFull::REG_OUT_Y_L;
    using L3G4200DFull::REG_OUT_Y_H;
    using L3G4200DFull::REG_OUT_Z_L;
    using L3G4200DFull::REG_OUT_Z_H;
    using L3G4200DFull::REG_FIFO_CTRL;
    using L3G4200DFull::REG_FIFO_SRC;
    using L3G4200DFull::REG_INT1_CFG;
    using L3G4200DFull::REG_INT1_SRC;
    using L3G4200DFull::REG_INT1_THS_XH;
    using L3G4200DFull::REG_INT1_THS_XL;
    using L3G4200DFull::REG_INT1_THS_YH;
    using L3G4200DFull::REG_INT1_THS_YL;
    using L3G4200DFull::REG_INT1_THS_ZH;
    using L3G4200DFull::REG_INT1_THS_ZL;
    using L3G4200DFull::REG_INT1_DURATION;
};

int main() {
    I2CConnectionMock connection;
    connection.setRegister(L3G4200DTestAccess::REG_WHO_AM_I, {0xD3});

    L3G4200DTestAccess sensor(connection);
    check_true(true, "init");

    // --- Init writes: CTRL_REG4 = 0x80, CTRL_REG1 = 0x0F ---
    bool sawCtrl4Default = false, sawCtrl1Default = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG4 && w[1] == 0x80) sawCtrl4Default = true;
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && w[1] == 0x0F) sawCtrl1Default = true;
    }
    check_true(sawCtrl4Default, "init_writes_ctrl_reg4_default");
    check_true(sawCtrl1Default, "init_writes_ctrl_reg1_default");

    // --- angular_rate: raw X=+16, Y=0, Z=-16 (LE bytes) at ±250 dps ---
    connection.setRegister(L3G4200DTestAccess::REG_OUT_X_L | 0x80,
                            {0x10, 0x00,    // X = +16
                             0x00, 0x00,    // Y = 0
                             0xF0, 0xFF});  // Z = -16 (0xFFF0 sign-extended)
    float x, y, z;
    sensor.angular_rate(x, y, z);
    float k = 3.141592653589793f / 180.0f;
    float expected_x = 16.0f * 0.00875f * k;
    float expected_z = -16.0f * 0.00875f * k;
    check_true(fabsf(x - expected_x) < 1e-6f, "angular_rate_x");
    check_true(fabsf(y - 0.0f) < 1e-6f, "angular_rate_y");
    check_true(fabsf(z - expected_z) < 1e-6f, "angular_rate_z");

    // --- who_am_i ---
    connection.setRegister(L3G4200DTestAccess::REG_WHO_AM_I, {0xD3});
    check_true(sensor.who_am_i() == 0xD3, "who_am_i");

    // --- status / data_ready ---
    connection.setRegister(L3G4200DTestAccess::REG_STATUS, {0x08});
    check_true(sensor.status() == 0x08, "status");
    check_true(sensor.data_ready() == true, "data_ready");

    // --- temperature (signed 8-bit) ---
    connection.setRegister(L3G4200DTestAccess::REG_OUT_TEMP, {0x80});
    check_true(sensor.temperature() == -128, "temperature_signed");

    // --- configure(odr=1, bw=0, full_scale=500) -> CTRL_REG1=(1<<6)|0x0F, CTRL_REG4=(1<<4)|0x80 ---
    sensor.configure(1, 0, 500);
    bool sawConfigCtrl1 = false, sawConfigCtrl4 = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && w[1] == ((1 << 6) | 0x0F)) sawConfigCtrl1 = true;
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG4 && w[1] == ((1 << 4) | 0x80)) sawConfigCtrl4 = true;
    }
    check_true(sawConfigCtrl1, "configure_ctrl_reg1_200Hz");
    check_true(sawConfigCtrl4, "configure_ctrl_reg4_500dps");

    // --- set_full_scale(2000) -> FS=10 -> CTRL_REG4 = (2 << 4) | 0x80 ---
    connection.setRegister(L3G4200DTestAccess::REG_CTRL_REG4, {0x90});
    sensor.set_full_scale(2000);
    bool sawFs2000 = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG4 && w[1] == ((2 << 4) | 0x80)) sawFs2000 = true;
    }
    check_true(sawFs2000, "set_full_scale_2000");

    // --- power_down ---
    connection.setRegister(L3G4200DTestAccess::REG_CTRL_REG1, {0x4F});
    sensor.power_down();
    bool sawPdClear = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && (w[1] & 0x08) == 0) sawPdClear = true;
    }
    check_true(sawPdClear, "power_down_clears_pd");

    // --- wake_up ---
    sensor.wake_up();
    bool sawWakeSet = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && (w[1] & 0x08)) sawWakeSet = true;
    }
    check_true(sawWakeSet, "wake_up_sets_pd");

    // --- sleep ---
    sensor.sleep();
    bool sawSleep = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && w[1] == 0x08) sawSleep = true;
    }
    check_true(sawSleep, "sleep_only_pd");

    // --- enable_axes(x=False, y=True, z=False) ---
    connection.setRegister(L3G4200DTestAccess::REG_CTRL_REG1, {0x08});
    sensor.enable_axes(false, true, false);
    bool sawYOnly = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG1 && (w[1] & 0x07) == 0x02) sawYOnly = true;
    }
    check_true(sawYOnly, "enable_axes_y_only");

    // --- enable_fifo(mode=2, watermark=10) ---
    connection.setRegister(L3G4200DTestAccess::REG_CTRL_REG5, {0x00});
    sensor.enable_fifo(2, 10);
    bool sawFifoEn = false, sawFifoCtrl = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG5 && (w[1] & 0x40)) sawFifoEn = true;
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_FIFO_CTRL && w[1] == ((2 << 5) | 10)) sawFifoCtrl = true;
    }
    check_true(sawFifoEn, "enable_fifo_sets_fifo_en");
    check_true(sawFifoCtrl, "enable_fifo_mode_watermark");

    // --- fifo_samples ---
    connection.setRegister(L3G4200DTestAccess::REG_FIFO_SRC, {0x1A});
    check_true(sensor.fifo_samples() == 26, "fifo_samples");

    // --- enable_highpass / disable_highpass ---
    sensor.enable_highpass(2, 5);
    bool sawHpcf = false, sawHpenSet = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG2 && w[1] == ((2 << 4) | 5)) sawHpcf = true;
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG5 && (w[1] & 0x10)) sawHpenSet = true;
    }
    check_true(sawHpcf, "enable_highpass_hpcf");
    check_true(sawHpenSet, "enable_highpass_hpen");
    sensor.disable_highpass();
    bool sawHpenClear = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG5 && (w[1] & 0x10) == 0) {
            sawHpenClear = true;
            break;
        }
    }
    check_true(sawHpenClear, "disable_highpass_clears_hpen");

    // --- set_interrupt ---
    sensor.set_interrupt(true, false, true, false, true, false, false, true);
    bool sawInt1Cfg = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_INT1_CFG && w[1] == (0x40 | 0x20 | 0x08 | 0x02)) sawInt1Cfg = true;
    }
    check_true(sawInt1Cfg, "set_interrupt_cfg");

    // --- set_threshold: 87.5 dps at ±250 (sensitivity 0.00875) -> 0x270F ---
    // _full_scale was set to 2000 above, so use sensitivity 0.07 (70 mdps/digit).
    //   87.5 / 0.07 ≈ 1250.0 -> 0x04E2
    // Expected raw: int(87.5 / 0.07) = 1250
    sensor.set_threshold('x', 87.5);
    uint16_t expected_x_raw = (uint16_t)(87.5f / 0.07f) & 0x7FFF;
    bool sawXh = false, sawXl = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_INT1_THS_XH && w[1] == ((expected_x_raw >> 8) & 0x7F)) sawXh = true;
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_INT1_THS_XL && w[1] == (expected_x_raw & 0xFF)) sawXl = true;
    }
    check_true(sawXh, "set_threshold_xh");
    check_true(sawXl, "set_threshold_xl");

    // --- set_duration(4, wait=True) -> 0x84 ---
    sensor.set_duration(4, true);
    bool sawDuration = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_INT1_DURATION && w[1] == 0x84) sawDuration = true;
    }
    check_true(sawDuration, "set_duration");

    // --- read_int_source ---
    connection.setRegister(L3G4200DTestAccess::REG_INT1_SRC, {0x7F});
    check_true(sensor.read_int_source() == 0x7F, "read_int_source");

    // --- set_data_ready_pin ---
    sensor.set_data_ready_pin(true);
    bool sawDrdy = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == L3G4200DTestAccess::REG_CTRL_REG3 && (w[1] & 0x08)) sawDrdy = true;
    }
    check_true(sawDrdy, "set_data_ready_pin");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
