#include <stdio.h>
#include <math.h>
#include <string.h>
#include <map>
#include <vector>
#include <deque>
#include "I2CConnectionMock.h"
#include "QMI8658A.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> V;

// Models the CTRL9 handshake, the self-test Avail flag and the FIFO_DATA port.
class QmiMock : public RegisterConnection {
public:
    std::map<uint8_t, uint8_t> regs;
    std::vector<V> log;                              // every write (reg, data...)
    struct Cmd { uint8_t cmd; uint8_t cal[8]; };
    std::vector<Cmd> cmds;
    std::deque<uint8_t> fifo;

    QmiMock() { regs[0x00] = 0x05; }

    void set(uint8_t reg, std::initializer_list<uint8_t> values) {
        uint8_t o = 0;
        for (uint8_t v : values) regs[reg + o++] = v;
    }
    bool wrote(V d) const { for (const auto& w : log) if (w == d) return true; return false; }
    // Last value written to a register, -1 if never written.
    int last(uint8_t reg) const {
        int v = -1;
        for (const auto& w : log) if (w.size() == 2 && w[0] == reg) v = w[1];
        return v;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.push_back(V(data, data + len));
        for (size_t i = 1; i < len; i++) regs[data[0] + i - 1] = data[i];
        if (len == 2 && (data[0] == 0x03 || data[0] == 0x04)) {     // self-test start / end
            if (data[1] & 0x80) regs[0x2D] |= 0x01; else regs[0x2D] &= ~0x01;
        }
        if (len == 2 && data[0] == 0x0A) {
            if (data[1]) {
                Cmd c; c.cmd = data[1];
                for (int i = 0; i < 8; i++) c.cal[i] = regs[0x0B + i];
                cmds.push_back(c);
                regs[0x2D] |= 0x80;
            } else {
                regs[0x2D] &= ~0x80;
            }
        }
    }
    void _read(uint8_t* buf, size_t len) override { for (size_t i = 0; i < len; i++) buf[i] = 0; }
    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t n) override {
        for (size_t i = 0; i < n; i++) {
            if (data[0] == 0x17) {
                if (fifo.empty()) buf[i] = 0; else { buf[i] = fifo.front(); fifo.pop_front(); }
            } else {
                auto it = regs.find(data[0] + i);
                buf[i] = it != regs.end() ? it->second : 0;
            }
        }
    }
};

static void le(QmiMock& m, uint8_t reg, std::initializer_list<int16_t> vals) {
    uint8_t o = 0;
    for (int16_t v : vals) {
        uint16_t u = static_cast<uint16_t>(v);
        m.set(reg + o, {static_cast<uint8_t>(u & 0xFF), static_cast<uint8_t>(u >> 8)});
        o += 2;
    }
}

static const float G = 9.80665f;
static const float D2R = 3.14159265358979f / 180.0f;
static bool near(float a, float b, float tol = 1e-3f) { return fabsf(a - b) < tol; }

static uint16_t g_status = 0;
static void on_int(uint16_t s) { g_status = s; }

int main() {
    QmiMock c;
    QMI8658AFull s(c);

    // init sequence
    check_true(c.log[0] == V({0x60, 0xB0}), "init_reset");
    check_true(c.wrote({0x02, 0x40}), "init_ctrl1");
    check_true(c.wrote({0x09, 0x80}), "init_ctrl8");
    check_true(c.wrote({0x03, 0x05}), "init_ctrl2");
    check_true(c.wrote({0x04, 0x45}), "init_ctrl3");
    check_true(c.wrote({0x06, 0x77}), "init_ctrl5");
    check_true(c.log.back() == V({0x08, 0x03}), "init_ctrl7");

    // accel / gyro conversion (±2 g, ±256 dps)
    float x, y, z;
    le(c, 0x35, {16384, -8192, 0});
    s.accel(x, y, z);
    check_true(near(x, G) && near(y, -G / 2) && near(z, 0.0f), "accel");
    le(c, 0x3B, {128, -256, 64});
    s.gyro(x, y, z);
    check_true(near(x, D2R, 1e-5f) && near(y, -2 * D2R, 1e-5f) && near(z, 0.5f * D2R, 1e-5f), "gyro");

    // raw / read_all / temperature
    le(c, 0x33, {0x1980, 2048, 0, -2048, 16, 32, -64});
    int16_t rx, ry, rz;
    s.accel_raw(rx, ry, rz);
    check_true(rx == 2048 && ry == 0 && rz == -2048, "accel_raw");
    s.gyro_raw(rx, ry, rz);
    check_true(rx == 16 && ry == 32 && rz == -64, "gyro_raw");
    float a[3], g[3], t;
    s.read_all(a, g, t);
    check_true(near(t, 25.5f), "read_all_temp");
    check_true(near(a[0], 2048.0f / 16384 * G) && near(g[2], -64.0f / 128 * D2R, 1e-5f), "read_all_values");
    check_true(near(s.temperature(), 25.5f), "temperature");

    // timestamp / data_ready
    c.set(0x30, {0x01, 0x02, 0x03});
    check_true(s.timestamp() == 0x030201UL, "timestamp");
    c.set(0x2E, {0x00});
    check_true(!s.data_ready(), "data_ready_false");
    c.set(0x2E, {0x02});
    check_true(s.data_ready(), "data_ready_true");

    // configure
    s.configure_accel(2, 4);
    check_true(c.log.back() == V({0x03, 0x24}), "configure_accel");
    le(c, 0x35, {4096, 0, 0});
    s.accel(x, y, z);
    check_true(near(x, G), "accel_after_range");
    s.configure_gyro(7, 3);
    check_true(c.log.back() == V({0x04, 0x73}), "configure_gyro");
    le(c, 0x3B, {16, 0, 0});
    s.gyro(x, y, z);
    check_true(near(x, D2R, 1e-5f), "gyro_after_range");
    s.configure_accel();
    s.configure_gyro();
    s.configure_filters(true, 3, false, 1);
    check_true(c.log.back() == V({0x06, 0x27}), "configure_filters");

    // sensors / power
    s.set_sensors(true, false);
    check_true(c.log.back() == V({0x08, 0x01}), "set_sensors_accel");
    s.set_sensors(true, true, true);
    check_true(c.log.back() == V({0x08, 0x13}), "set_sensors_snooze");
    s.set_sensors(true, true);
    s.set_power_down(true);
    check_true(c.log.back() == V({0x02, 0x41}), "power_down_ctrl1");
    check_true(c.log[c.log.size() - 2] == V({0x08, 0x00}), "power_down_ctrl7");
    s.set_power_down(false);
    check_true(c.log.back() == V({0x02, 0x40}), "wake_ctrl1");
    s.set_sensors(true, true);

    // sync sample
    c.cmds.clear();
    s.set_sync_sample(true);
    check_true(c.cmds.back().cmd == 0x12 && c.cmds.back().cal[0] == 1, "sync_gating_cmd");
    check_true(c.wrote({0x08, 0x83}), "sync_ctrl7");
    c.regs[0x2D] = 0x03;
    le(c, 0x35, {16384, 0, 0});
    s.accel(x, y, z);
    check_true(near(x, G), "sync_accel");
    s.set_sync_sample(false);
    check_true(c.last(0x08) == 0x03, "sync_off_ctrl7");
    check_true(c.cmds.back().cmd == 0x12 && c.cmds.back().cal[0] == 0, "sync_gating_restore");

    // FIFO
    s.configure_fifo(2, 1, 16);
    check_true(c.wrote({0x14, 0x06}) && c.wrote({0x13, 0x10}), "fifo_ctrl");
    c.set(0x16, {0x00});
    c.set(0x15, {12});
    check_true(s.fifo_count() == 24 && s.fifo_status() == 0, "fifo_count");
    for (int i = 0; i < 24; i++) c.fifo.push_back(i);
    c.cmds.clear();
    uint8_t buf[64];
    uint16_t n = s.read_fifo(buf, sizeof buf);
    bool ok = n == 24;
    for (int i = 0; i < 24; i++) ok = ok && buf[i] == i;
    check_true(ok, "read_fifo_data");
    check_true(c.cmds.size() == 1 && c.cmds[0].cmd == 0x05, "read_fifo_req");
    check_true(c.wrote({0x02, 0x00}) && c.last(0x02) == 0x40, "read_fifo_ai_restored");
    check_true(c.last(0x14) == 0x06, "read_fifo_rd_mode_cleared");
    c.fifo.clear();
    for (int i = 0; i < 24; i++) c.fifo.push_back(i);
    check_true(s.read_fifo(buf, 10) == 10 && buf[9] == 9, "read_fifo_limit");
    s.reset_fifo();
    check_true(c.cmds.back().cmd == 0x04, "reset_fifo");
    s.configure_fifo(0, 3, 0);
    check_true(c.last(0x08) == 0x03, "fifo_bypass_drdy");

    // motion engines
    c.cmds.clear();
    s.configure_any_motion(0.25f, 3, 0x07, false);
    check_true(c.cmds.size() == 2 && c.cmds[0].cmd == 0x0E && c.cmds[1].cmd == 0x0E, "motion_two_sets");
    check_true(c.cmds[0].cal[0] == 8 && c.cmds[0].cal[3] == 4 && c.cmds[0].cal[6] == 0xF7 &&
               c.cmds[0].cal[7] == 0x10, "motion_set1");
    check_true(c.cmds[1].cal[0] == 3 && c.cmds[1].cal[1] == 30 && c.cmds[1].cal[2] == 150 &&
               c.cmds[1].cal[4] == (600 & 0xFF) && c.cmds[1].cal[5] == (600 >> 8) &&
               c.cmds[1].cal[7] == 0x20, "motion_set2");
    check_true(c.wrote({0x08, 0x00}) && c.log.back() == V({0x08, 0x03}), "motion_sensors_off_restore");
    s.configure_significant_motion(100, 300);
    check_true(c.cmds.back().cal[2] == 100 && c.cmds.back().cal[4] == (300 & 0xFF) &&
               c.cmds.back().cal[5] == 1, "sig_motion_set2");
    s.enable_motion(true, true, false);
    check_true(c.log.back() == V({0x09, 0x86}), "enable_motion");
    s.enable_motion();
    check_true(c.log.back() == V({0x09, 0x80}), "disable_motion");

    // tap
    c.cmds.clear();
    s.configure_tap();
    check_true(c.cmds.size() == 2 && c.cmds[0].cmd == 0x0C, "tap_two_sets");
    check_true(c.cmds[0].cal[0] == 20 && c.cmds[0].cal[1] == 0 && c.cmds[0].cal[2] == 50 &&
               c.cmds[0].cal[4] == 250 && c.cmds[0].cal[7] == 0x10, "tap_set1");
    check_true(c.cmds[1].cal[0] == 8 && c.cmds[1].cal[1] == 32 && c.cmds[1].cal[2] == 0x33 &&
               c.cmds[1].cal[3] == 0x03 && c.cmds[1].cal[4] == 0x9A && c.cmds[1].cal[5] == 0x01 &&
               c.cmds[1].cal[7] == 0x20, "tap_set2");
    s.enable_tap(true);
    check_true(c.log.back() == V({0x09, 0x81}), "enable_tap");
    c.set(0x59, {0xA2});
    uint8_t cnt, axis; bool neg;
    s.tap_status(cnt, axis, neg);
    check_true(cnt == 2 && axis == 2 && neg, "tap_status");
    s.enable_tap(false);

    // pedometer
    c.cmds.clear();
    s.configure_pedometer();
    check_true(c.cmds.size() == 2 && c.cmds[0].cmd == 0x0D, "ped_two_sets");
    check_true(c.cmds[0].cal[0] == 50 && c.cmds[0].cal[2] == 205 && c.cmds[0].cal[4] == 102 &&
               c.cmds[0].cal[7] == 0x10, "ped_set1");
    check_true(c.cmds[1].cal[0] == 200 && c.cmds[1].cal[2] == 20 && c.cmds[1].cal[3] == 10 &&
               c.cmds[1].cal[5] == 4 && c.cmds[1].cal[7] == 0x20, "ped_set2");
    s.enable_pedometer(true);
    check_true(c.log.back() == V({0x09, 0x90}), "enable_pedometer");
    c.set(0x5A, {0x34, 0x12, 0x00});
    check_true(s.step_count() == 0x1234, "step_count");
    s.reset_step_count();
    check_true(c.cmds.back().cmd == 0x0F, "reset_step_count");
    s.enable_pedometer(false);

    // wake-on-motion
    c.cmds.clear();
    s.set_wake_on_motion(100, 2, 1, 5);
    check_true(c.wrote({0x03, 0x0C}), "wom_ctrl2");
    check_true(c.cmds.back().cmd == 0x08 && c.cmds.back().cal[0] == 100 && c.cmds.back().cal[1] == 0xC5, "wom_cmd");
    check_true(c.wrote({0x08, 0x01}) && (c.last(0x02) & 0x10), "wom_accel_on_int2");
    s.set_wake_on_motion(0);
    check_true(c.cmds.back().cmd == 0x08 && c.cmds.back().cal[0] == 0 && !(c.last(0x02) & 0x18), "wom_exit");
    s.configure_accel();
    s.set_sensors(true, true);

    // offsets
    c.cmds.clear();
    s.set_accel_offset(G, 0.0f, -G);
    check_true(c.cmds.back().cmd == 0x09 && c.cmds.back().cal[0] == 0x00 && c.cmds.back().cal[1] == 0x10 &&
               c.cmds.back().cal[4] == 0x00 && c.cmds.back().cal[5] == 0xF0, "accel_offset");
    s.set_gyro_offset(D2R, 0.0f, 0.0f);
    check_true(c.cmds.back().cmd == 0x0A && c.cmds.back().cal[0] == 32 && c.cmds.back().cal[1] == 0, "gyro_offset");

    // calibration-on-demand and gains
    c.set(0x46, {0x00});
    uint8_t cod = 0xFF;
    check_true(s.calibrate_gyro(&cod) && cod == 0 && c.cmds.back().cmd == 0xA2, "calibrate_gyro");
    c.set(0x46, {0x01});
    check_true(!s.calibrate_gyro(&cod) && cod == 1, "calibrate_gyro_fail");
    le(c, 0x51, {1000, 2000, 3000});
    uint16_t gx, gy, gz;
    s.get_gyro_gains(gx, gy, gz);
    check_true(gx == 1000 && gy == 2000 && gz == 3000, "get_gyro_gains");
    s.set_gyro_gains(1000, 2000, 3000);
    check_true(c.cmds.back().cmd == 0xAA && c.cmds.back().cal[0] == 0xE8 && c.cmds.back().cal[1] == 0x03 &&
               c.cmds.back().cal[4] == 0xB8 && c.cmds.back().cal[5] == 0x0B, "set_gyro_gains");

    // device info
    c.set(0x01, {0x7C});
    c.set(0x49, {1, 2, 0, 3});
    c.set(0x4B, {3});
    c.set(0x51, {1, 2, 3, 4, 5, 6});
    uint8_t rev, fw[3], usid[6];
    s.device_info(rev, fw, usid);
    check_true(rev == 0x7C && fw[0] == 1 && fw[1] == 2 && fw[2] == 3 && usid[0] == 1 && usid[5] == 6 &&
               c.cmds.back().cmd == 0x10, "device_info");

    // pull-ups
    s.set_pullups(false, true, true, false);
    check_true(c.cmds.back().cmd == 0x11 && c.cmds.back().cal[0] == 0x09, "set_pullups");

    // self-test
    c.set(0x2D, {0x00});
    le(c, 0x51, {2048, 2048, -2048});
    float v[3];
    check_true(s.self_test_accel(v) && near(v[0], G), "self_test_accel");
    le(c, 0x51, {16 * 400, 16 * 400, 16 * 400});
    check_true(s.self_test_gyro(v) && near(v[0], 400 * D2R, 1e-3f), "self_test_gyro");
    le(c, 0x51, {16 * 100, 16 * 400, 16 * 400});
    check_true(!s.self_test_gyro(v), "self_test_gyro_fail");
    c.set(0x2D, {0x00});

    // interrupts
    s.set_interrupt_pins(1, 1);
    check_true(c.last(0x09) & 0x40, "int_pins_ctrl8");
    s.enable_interrupt(QMI8658AFull::SOURCE_TAP);
    check_true((c.last(0x09) & 0x01) && (c.last(0x02) & 0x08), "int_tap_engine_int1");
    s.enable_interrupt(QMI8658AFull::SOURCE_DATA_READY);
    check_true(c.last(0x02) & 0x10, "int_drdy_int2");
    s.disable_interrupt(QMI8658AFull::SOURCE_DATA_READY);
    check_true(!(c.last(0x02) & 0x10) && (c.last(0x08) & 0x20), "int_drdy_off");
    s.disable_interrupt(QMI8658AFull::SOURCE_TAP);
    check_true(!(c.last(0x02) & 0x08), "int_tap_off");
    s.set_interrupt_pins(2, 2);
    c.set(0x2E, {0x01, 0xA2});
    c.set(0x16, {0xC0});
    uint16_t expect = QMI8658AFull::SOURCE_DATA_READY | QMI8658AFull::SOURCE_FIFO_WATERMARK |
                      QMI8658AFull::SOURCE_FIFO_FULL | QMI8658AFull::SOURCE_ANY_MOTION |
                      QMI8658AFull::SOURCE_SIGNIFICANT_MOTION | QMI8658AFull::SOURCE_TAP;
    check_true(s.poll_interrupt() == expect, "poll_interrupt");
    s.on_interrupt(on_int);
    s.off_interrupt();
    check_true(g_status == 0, "on_off_interrupt_no_pin");

    // reset
    s.reset();
    check_true(c.wrote({0x60, 0xB0}), "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
