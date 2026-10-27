#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "LSM303.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool last_write(const I2CConnectionMock& mock, uint8_t reg, uint8_t& out) {
    bool found = false;
    out = 0;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == reg) { out = w[1]; found = true; }
    }
    return found;
}

static void fresh_mocks(I2CConnectionMock& accel, I2CConnectionMock& mag) {
    mag.setRegister(0x0A, {0x48, 0x34, 0x33});
    // High-resolution (±2 g, shift 4): 0x0010/0x0020/0x0030 -> 1/2/3 mg.
    accel.setRegister(0x28 | 0x80, {0x10, 0x00, 0x20, 0x00, 0x30, 0x00});
    // X = 1100 (100 uT), Z = 980 (100 uT), Y = -1100 (-100 uT), high byte first.
    mag.setRegister(0x03, {0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4});
}

int main() {
    {
        I2CConnectionMock accel, mag;
        fresh_mocks(accel, mag);
        LSM303Minimal chip(accel, mag);
        check_true(true, "construct_minimal");

        uint8_t v;
        check_true(last_write(accel, 0x23, v) && v == 0x88, "init_ctrl_reg4_default");
        check_true(last_write(accel, 0x20, v) && v == 0x57, "init_ctrl_reg1_default");
        check_true(last_write(accel, 0x24, v) && v == 0x00, "init_ctrl_reg5_cleared");
        check_true(last_write(mag, 0x01, v) && v == 0x20, "init_mag_gain_130ut");
        check_true(last_write(mag, 0x02, v) && v == 0x00, "init_mag_continuous");

        float x, y, z;
        chip.read_acceleration(x, y, z);
        check_true(fabsf(x - 0.001f) < 1e-6f, "acceleration_x");
        check_true(fabsf(y - 0.002f) < 1e-6f, "acceleration_y");
        check_true(fabsf(z - 0.003f) < 1e-6f, "acceleration_z");

        float mx, my, mz;
        chip.read_magnetic(mx, my, mz);
        check_true(fabsf(mx - 100.0f) < 1e-3f, "magnetic_x");
        check_true(fabsf(my + 100.0f) < 1e-3f, "magnetic_y");
        check_true(fabsf(mz - 100.0f) < 1e-3f, "magnetic_z");
    }

    {
        I2CConnectionMock accel, mag;
        fresh_mocks(accel, mag);
        LSM303Full chip(accel, mag);
        check_true(true, "construct_full");

        chip.set_range(4);
        float x, y, z;
        chip.read_acceleration(x, y, z);
        check_true(fabsf(x - 0.002f) < 1e-6f, "set_range_4g_sensitivity");
        uint8_t v;
        check_true(last_write(accel, 0x23, v) && (v & 0x30) == 0x10, "set_range_4g_fs_bits");

        chip.set_data_rate(400);
        check_true(last_write(accel, 0x20, v) && (v >> 4) == 0x07, "set_data_rate_400hz");

        chip.set_mode(1);
        check_true(last_write(accel, 0x23, v) && (v & 0x08) == 0, "set_mode_normal_hr_clear");

        chip.set_range(2);
        chip.set_motion_interrupt(1, 0x3F, 0.5f, 0, true);
        check_true(last_write(accel, 0x32, v) && v == 32, "motion_threshold_code");
        check_true(last_write(accel, 0x30, v) && (v & 0x80) == 0x80, "motion_and_mode");

        chip.set_free_fall(1, 0.35f, 30);
        check_true(last_write(accel, 0x30, v) && v == 0x95, "free_fall_cfg");

        chip.set_click(0, 0x04, 0.5f, 10, 20, 50);
        check_true(last_write(accel, 0x38, v) && (v & 0x20) == 0x20, "click_double_z");

        chip.enable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);
        check_true(last_write(accel, 0x22, v) && (v & 0x10) == 0x10, "route_data_ready_int1");
        chip.enable_interrupt(LSM303Full::SOURCE_CLICK, 2);
        check_true(last_write(accel, 0x25, v) && (v & 0x80) == 0x80, "route_click_int2");
        chip.disable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);

        chip.set_mag_range(470);
        check_true(last_write(mag, 0x01, v) && v == 0xA0, "set_mag_range_470ut");

        mag.setRegister(0x03, {0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C});
        float mx, my, mz;
        chip.read_magnetic(mx, my, mz);
        check_true(fabsf(mx - 100.0f) < 1e-3f, "mag_gain_470ut_x");

        chip.enable_temperature(true);
        chip.set_mag_data_rate(30.0f);
        check_true(last_write(mag, 0x00, v) && (v & 0x1C) == 0x14, "set_mag_data_rate_30hz");
        check_true(last_write(mag, 0x00, v) && (v & 0x80) == 0x80, "set_mag_data_rate_keeps_temp");

        uint8_t identity[3];
        chip.read_mag_identity(identity);
        check_true(identity[0] == 0x48 && identity[1] == 0x34 && identity[2] == 0x33,
                   "mag_identity");

        mag.setRegister(0x31, {0x00, 0x80});
        check_true(fabsf(chip.read_temperature_delta() - 1.0f) < 1e-6f, "temperature_delta");

        int16_t rx, ry, rz;
        chip.read_magnetic_raw(rx, ry, rz);
        check_true(rx == 400, "read_magnetic_raw_x");

        bool ready = chip.new_data_available();
        check_true(!ready, "new_data_available_false");
        uint8_t status = chip.read_status();
        check_true(status == 0, "read_status_zero");

        uint16_t isr = chip.poll_interrupt();
        check_true((isr & LSM303Full::SOURCE_MAG_DATA_READY) == 0, "poll_mag_not_ready");

        chip.reboot();
        check_true(true, "reboot");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
