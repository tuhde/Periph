#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "BNO055.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, const char *label) {
    check_true(fabsf(got - expected) < 1e-4f, label);
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

static void put16(I2CConnectionMock& c, uint16_t reg, std::initializer_list<int16_t> values) {
    uint16_t r = reg;
    for (int16_t v : values) {
        uint16_t u = static_cast<uint16_t>(v);
        c.setRegister(r, {static_cast<uint8_t>(u & 0xFF), static_cast<uint8_t>(u >> 8)});
        r += 2;
    }
}

// Last value written to a register (page-0 view; the mock keeps one flat map).
static std::vector<uint8_t> writes_to(const I2CConnectionMock& c, uint8_t reg) {
    std::vector<uint8_t> out;
    for (const auto& w : c.writes()) if (w.size() == 2 && w[0] == reg) out.push_back(w[1]);
    return out;
}

static void preload(I2CConnectionMock& c) {
    c.setRegister(0x00, {0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15});
}

static uint8_t last_cb_status = 0xFF;
static void on_int(uint8_t status) { last_cb_status = status; }

int main() {
    // init
    {
        I2CConnectionMock c;
        preload(c);
        BNO055Minimal m(c);
        check_true(m.valid(), "init_valid");
        const auto& w = c.writes();
        check_true(eq(w[1], {0x3E, 0x00}), "init_pwr_mode");
        check_true(eq(w[2], {0x07, 0x00}), "init_page0");
        check_true(eq(w[3], {0x3B, 0x86}), "init_unit_sel");
        check_true(eq(w[4], {0x3D, 0x0C}), "init_opr_ndof");

        put16(c, 0x1A, {1414, -900, 450});
        float h, r, p;
        m.euler(h, r, p);
        check_close(h, 1414 / 900.0f, "euler_heading");
        check_close(r, -1.0f, "euler_roll");
        check_close(p, 0.5f, "euler_pitch");
    }

    // wrong chip id
    {
        I2CConnectionMock c;
        c.setRegister(0x00, {0x55});
        BNO055Minimal m(c);
        check_true(!m.valid(), "bad_chip_id");
    }

    I2CConnectionMock c;
    preload(c);
    BNO055Full s(c);

    float x, y, z, w;
    put16(c, 0x20, {16384, -8192, 0, 4096});
    s.quaternion(w, x, y, z);
    check_true(w == 1.0f && x == -0.5f && y == 0.0f && z == 0.25f, "quaternion");
    put16(c, 0x28, {100, -250, 981});
    s.linear_acceleration(x, y, z);
    check_close(x, 1.0f, "linear_x"); check_close(y, -2.5f, "linear_y"); check_close(z, 9.81f, "linear_z");
    put16(c, 0x2E, {0, 0, 981});
    s.gravity(x, y, z);
    check_close(z, 9.81f, "gravity_z");
    put16(c, 0x08, {-100, 200, 300});
    s.acceleration(x, y, z);
    check_close(x, -1.0f, "acceleration_x"); check_close(z, 3.0f, "acceleration_z");
    put16(c, 0x0E, {160, -32, 16});
    s.magnetic_field(x, y, z);
    check_close(x, 10.0f, "magnetic_x"); check_close(y, -2.0f, "magnetic_y"); check_close(z, 1.0f, "magnetic_z");
    put16(c, 0x14, {900, 0, -450});
    s.angular_rate(x, y, z);
    check_close(x, 1.0f, "angular_x"); check_close(z, -0.5f, "angular_z");
    c.setRegister(0x34, {0xE7});
    check_true(s.temperature() == -25, "temperature_negative");
    c.setRegister(0x34, {23});
    check_true(s.temperature() == 23, "temperature");

    // calibration status
    uint8_t sy, gy, ac, mg;
    c.setRegister(0x35, {0xE4});
    s.calibration_status(sy, gy, ac, mg);
    check_true(sy == 3 && gy == 2 && ac == 1 && mg == 0, "calibration_status");
    check_true(!s.is_calibrated(), "not_calibrated");
    c.setRegister(0x35, {0xFF});
    check_true(s.is_calibrated(), "is_calibrated");

    // modes
    size_t n0 = c.writes().size();
    s.set_mode(BNO055Full::MODE_IMU);
    check_true(c.writes().size() == n0 + 2 && eq(c.writes()[n0], {0x3D, 0x00}) && eq(c.writes()[n0 + 1], {0x3D, 0x08}), "set_mode_sequence");
    c.setRegister(0x3D, {0x08});
    check_true(s.get_mode() == 8, "get_mode");
    n0 = c.writes().size();
    s.set_mode(13);
    check_true(c.writes().size() == n0, "set_mode_range");
    s.set_mode(BNO055Full::MODE_NDOF);

    // calibration blob
    uint8_t blob[22], got[22];
    for (uint8_t i = 0; i < 22; i++) blob[i] = static_cast<uint8_t>(i + 1);
    c.setRegister(0x55, {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22});
    n0 = c.writes().size();
    s.get_calibration(got);
    bool same = true;
    for (uint8_t i = 0; i < 22; i++) same = same && got[i] == blob[i];
    check_true(same, "get_calibration");
    check_true(eq(c.writes()[n0], {0x3D, 0x00}) && eq(c.writes().back(), {0x3D, 0x0C}), "get_calibration_config");
    n0 = c.writes().size();
    s.set_calibration(blob);
    bool okw = true;
    for (uint8_t i = 0; i < 22; i++) okw = okw && eq(c.writes()[n0 + 1 + i], {static_cast<uint8_t>(0x55 + i), blob[i]});
    check_true(okw, "set_calibration");
    check_true(eq(c.writes().back(), {0x3D, 0x0C}), "set_calibration_restores_mode");

    // configuration
    n0 = c.writes().size();
    s.set_axis_remap(0x21, 0x02);
    check_true(eq(c.writes()[n0 + 1], {0x41, 0x21}) && eq(c.writes()[n0 + 2], {0x42, 0x02}), "axis_remap");
    check_true(eq(c.writes().back(), {0x3D, 0x0C}), "axis_remap_restores");
    s.set_power_mode(BNO055Full::POWER_LOW_POWER);
    check_true(writes_to(c, 0x3E).back() == 1, "power_mode");
    s.set_power_mode(3);
    check_true(writes_to(c, 0x3E).back() == 1, "power_mode_invalid");
    s.set_temperature_source(BNO055Full::TEMP_SOURCE_GYROSCOPE);
    check_true(writes_to(c, 0x40).back() == 1, "temp_source");
    s.use_external_crystal(true);
    check_true(writes_to(c, 0x3F).back() == 0x80, "crystal_on");
    s.use_external_crystal(false);
    check_true(writes_to(c, 0x3F).back() == 0x00, "crystal_off");

    // self-test
    c.setRegister(0x36, {0x0D});
    bool a, m2, g, mcu;
    s.self_test(a, m2, g, mcu);
    check_true(a && !m2 && g && mcu, "self_test_result");
    check_true(writes_to(c, 0x3F).back() == 0x01, "self_test_trigger");
    check_true(eq(c.writes().back(), {0x3D, 0x0C}), "self_test_restores_mode");

    // status, revisions
    uint8_t st, er;
    c.setRegister(0x39, {1, 3});
    s.system_status(st, er);
    check_true(st == 1 && er == 3, "system_status");
    uint8_t cid, aid, mid, gid, bl;
    uint16_t sw;
    s.revisions(cid, aid, mid, gid, sw, bl);
    check_true(cid == 0xA0 && aid == 0xFB && mid == 0x32 && gid == 0x0F && sw == 0x0311 && bl == 0x15, "revisions");

    // unique id
    c.setRegister(0x50, {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
    uint8_t uid[16];
    n0 = c.writes().size();
    s.unique_id(uid);
    check_true(uid[0] == 0 && uid[15] == 15, "unique_id");
    check_true(eq(c.writes()[n0], {0x07, 0x01}) && eq(c.writes().back(), {0x07, 0x00}), "unique_id_pages");

    // interrupts
    c.setRegister(0x10, {0x00});
    c.setRegister(0x0F, {0x00});
    n0 = c.writes().size();
    s.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY | BNO055Full::SOURCE_GYR_DRDY);
    check_true(writes_to(c, 0x10).back() == 0x11 && writes_to(c, 0x0F).back() == 0x11, "enable_interrupt");
    check_true(eq(c.writes()[n0], {0x07, 0x01}) && eq(c.writes().back(), {0x07, 0x00}), "enable_interrupt_pages");
    s.disable_interrupt(BNO055Full::SOURCE_GYR_DRDY);
    check_true(writes_to(c, 0x10).back() == 0x01 && writes_to(c, 0x0F).back() == 0x01, "disable_interrupt");
    c.setRegister(0x37, {0x41});
    check_true(s.poll_interrupt() == 0x41, "poll_interrupt");
    s.on_interrupt(on_int);
    s.off_interrupt();
    check_true(last_cb_status == 0xFF, "on_off_interrupt_without_pin");

    // reset
    n0 = c.writes().size();
    s.reset();
    check_true(eq(c.writes()[n0], {0x3F, 0x20}), "reset_trigger");
    check_true(s.valid() && eq(c.writes().back(), {0x3D, 0x0C}), "reset_reinit");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
