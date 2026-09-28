#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "HMC5883L.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool close_f(float a, float b, float eps = 1e-6f) {
    return fabsf(a - b) < eps;
}

static int lastWriteTo(const I2CConnectionMock& conn, uint8_t reg) {
    const auto& w = conn.writes();
    for (auto it = w.rbegin(); it != w.rend(); ++it) {
        if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
    }
    return -1;
}

int main() {
    // --- Minimal constructor: writes Config A, Config B, Mode ---
    I2CConnectionMock conn;
    HMC5883LMinimal chip(conn);
    check_true(lastWriteTo(conn, 0x00) == 0x70, "init_writes_config_a");
    check_true(lastWriteTo(conn, 0x01) == 0x20, "init_writes_config_b");
    check_true(lastWriteTo(conn, 0x02) == 0x00, "init_writes_mode");

    // --- magnetic_field(): X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss) ---
    conn.setRegister(0x03, {0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0}); // x=1000,z=-500,y=2000
    float x, y, z;
    bool ok = chip.magnetic_field(x, y, z);
    check_true(ok, "magnetic_field_valid");
    check_true(close_f(x, (1000.0f / 1090.0f) * 1e-4f), "magnetic_field_x");
    check_true(close_f(y, (2000.0f / 1090.0f) * 1e-4f), "magnetic_field_y");
    check_true(close_f(z, (-500.0f / 1090.0f) * 1e-4f), "magnetic_field_z");

    // --- magnetic_field(): overflow sentinel -> invalid ---
    conn.setRegister(0x03, {0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8}); // x overflow, z/y=1000
    ok = chip.magnetic_field(x, y, z);
    check_true(!ok, "magnetic_field_overflow_invalid");

    // --- Full: configure() writes Config A/B, updates cached gain ---
    I2CConnectionMock fullConn;
    HMC5883LFull full(fullConn);
    bool cfgOk = full.configure(30.0f, 4, 5);
    check_true(cfgOk, "configure_accepted");
    check_true(lastWriteTo(fullConn, 0x00) == 0x54, "configure_config_a");  // MA=10,DO=101
    check_true(lastWriteTo(fullConn, 0x01) == 0xA0, "configure_config_b");  // GN=101

    check_true(!full.configure(30.0f, 3, 1), "configure_bad_averaging_rejected");
    check_true(!full.configure(100.0f, 4, 1), "configure_bad_odr_rejected");
    check_true(!full.configure(30.0f, 4, 8), "configure_bad_gain_rejected");

    // --- set_gain() ---
    check_true(full.set_gain(2), "set_gain_accepted");
    check_true(lastWriteTo(fullConn, 0x01) == (2 << 5), "set_gain_writes_config_b");
    check_true(!full.set_gain(9), "set_gain_invalid_rejected");

    // --- set_mode() ---
    check_true(full.set_mode("single"), "set_mode_single_accepted");
    check_true(lastWriteTo(fullConn, 0x02) == 0b01, "set_mode_single");
    check_true(full.set_mode("idle"), "set_mode_idle_accepted");
    check_true(lastWriteTo(fullConn, 0x02) == 0b10, "set_mode_idle");
    check_true(full.set_mode("continuous"), "set_mode_continuous_accepted");
    check_true(lastWriteTo(fullConn, 0x02) == 0b00, "set_mode_continuous");
    check_true(!full.set_mode("bogus"), "set_mode_invalid_rejected");

    // --- data_ready()/status() ---
    fullConn.setRegister(0x09, {0x01});
    check_true(full.data_ready(), "data_ready_true");
    check_true(full.status() == 0x01, "status_raw");
    fullConn.setRegister(0x09, {0x02});  // LOCK set, RDY clear
    check_true(!full.data_ready(), "data_ready_false");

    // --- single_measurement(): writes mode=0x01, then reads data ---
    fullConn.setRegister(0x03, {0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0});
    float sx, sy, sz;
    full.single_measurement(sx, sy, sz);
    check_true(lastWriteTo(fullConn, 0x02) == 0x01, "single_measurement_writes_mode");
    check_true(close_f(sx, (1000.0f / 820.0f) * 1e-4f), "single_measurement_x");

    // --- identify() ---
    fullConn.setRegister(0x0A, {0x48, 0x34, 0x33});
    uint8_t idA, idB, idC;
    full.identify(idA, idB, idC);
    check_true(idA == 0x48 && idB == 0x34 && idC == 0x33, "identify");

    // --- self_test(): sets MS bias bits, restores normal mode afterward ---
    I2CConnectionMock stConn;
    HMC5883LFull selftest(stConn);
    stConn.setRegister(0x00, {0x70});  // current Config A (post-init)
    stConn.setRegister(0x03, {0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0});
    float rx, ry, rz;
    selftest.self_test(true, rx, ry, rz);
    bool sawPositiveBias = false;
    const auto& stWrites = stConn.writes();
    for (const auto& w : stWrites) {
        if (w.size() == 2 && w[0] == 0x00 && w[1] == 0x71) sawPositiveBias = true;  // 0x70|0b01
    }
    check_true(sawPositiveBias, "self_test_sets_positive_bias");
    check_true(lastWriteTo(stConn, 0x00) == 0x70, "self_test_restores_normal");  // 0x71&0xFC|0b00
    check_true(close_f(rx, (1000.0f / 1090.0f) * 1e-4f), "self_test_result");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
