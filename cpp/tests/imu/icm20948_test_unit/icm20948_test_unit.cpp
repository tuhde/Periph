#include <stdio.h>
#include <math.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "ICM20948.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Models the ICM-20948 user banks (REG_BANK_SEL = 0x7F). Registers are keyed
// (bank << 8) | reg; every write is logged together with the bank it landed in.
class BankedMock : public RegisterConnection {
public:
    struct Entry { uint8_t bank; std::vector<uint8_t> data; };
    uint8_t bank = 0;
    std::vector<Entry> log;
    std::map<uint16_t, uint8_t> regs;

    void set(uint8_t b, uint8_t reg, std::initializer_list<uint8_t> values) {
        uint16_t o = 0;
        for (uint8_t v : values) regs[(b << 8) | (reg + o++)] = v;
    }
    uint8_t get(uint8_t b, uint8_t reg) { return regs[(b << 8) | reg]; }

    // Writes (excluding bank selects) that landed in the given bank.
    std::vector<std::vector<uint8_t>> writesIn(uint8_t b) const {
        std::vector<std::vector<uint8_t>> out;
        for (const auto& e : log)
            if (e.bank == b && e.data.size() > 1 && e.data[0] != 0x7F) out.push_back(e.data);
        return out;
    }
    bool wrote(uint8_t b, std::vector<uint8_t> d) const {
        for (const auto& w : writesIn(b)) if (w == d) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.push_back({bank, std::vector<uint8_t>(data, data + len)});
        if (data[0] == 0x7F) { bank = data[1] >> 4; return; }
        for (size_t i = 1; i < len; i++) regs[(bank << 8) | (data[0] + i - 1)] = data[i];
    }
    void _read(uint8_t* buf, size_t len) override { for (size_t i = 0; i < len; i++) buf[i] = 0; }
    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t buf_len) override {
        log.push_back({bank, std::vector<uint8_t>(data, data + 1)});
        for (size_t i = 0; i < buf_len; i++) {
            auto it = regs.find((bank << 8) | (data[0] + i));
            buf[i] = it != regs.end() ? it->second : 0;
        }
    }
};

class ICM20948TestAccess : public ICM20948Full {
public:
    using ICM20948Full::ICM20948Full;
};

typedef std::vector<uint8_t> V;

static void be(BankedMock& m, uint8_t bank, uint8_t reg, std::initializer_list<int16_t> vals) {
    uint8_t o = 0;
    for (int16_t v : vals) {
        uint16_t u = static_cast<uint16_t>(v);
        m.set(bank, reg + o, {static_cast<uint8_t>(u >> 8), static_cast<uint8_t>(u & 0xFF)});
        o += 2;
    }
}

static void le(BankedMock& m, uint8_t bank, uint8_t reg, std::initializer_list<int16_t> vals) {
    uint8_t o = 0;
    for (int16_t v : vals) {
        uint16_t u = static_cast<uint16_t>(v);
        m.set(bank, reg + o, {static_cast<uint8_t>(u & 0xFF), static_cast<uint8_t>(u >> 8)});
        o += 2;
    }
}

static const float DEG2RAD = 3.141592653589793f / 180.0f;
static bool near(float a, float b, float tol = 1e-3f) { return fabsf(a - b) < tol; }

int main() {
    BankedMock c;
    c.set(0, 0x00, {0xEA});
    c.set(0, 0x17, {0x40});          // I2C_MST_STATUS: SLV4_DONE
    ICM20948TestAccess sensor(c);
    check_true(true, "init");

    // init: bank 0, WHO_AM_I, reset, wake, all axes on, bank-2 configuration.
    check_true(c.log[0].data == V({0x7F, 0x00}), "init_bank_zero");
    check_true(c.log[1].data == V({0x00}), "init_who_am_i_read");
    check_true(c.log[2].data == V({0x06, 0x80}), "init_reset");
    check_true(c.log[3].data == V({0x06, 0x01}), "init_wake");
    check_true(c.log[4].data == V({0x07, 0x00}), "init_pwr_mgmt_2");
    check_true(c.wrote(2, {0x01, 0x19}), "init_gyro_config");
    check_true(c.wrote(2, {0x14, 0x19}), "init_accel_config");
    check_true(c.wrote(2, {0x00, 0x0A}), "init_gyro_div");
    check_true(c.wrote(2, {0x11, 0x0A}), "init_accel_div");
    check_true(c.bank == 0, "init_ends_bank_zero");

    float ax, ay, az;
    be(c, 0, 0x2D, {16384, -8192, 4096});
    sensor.accel(ax, ay, az);
    check_true(near(ax, 9.80665f, 1e-4f), "accel_x");
    check_true(near(ay, -4.903325f, 1e-4f), "accel_y");
    check_true(near(az, 2.4516625f, 1e-4f), "accel_z");

    float gx, gy, gz;
    be(c, 0, 0x33, {131, -131, 262});
    sensor.gyro(gx, gy, gz);
    check_true(near(gx, 1.0f * DEG2RAD, 1e-4f), "gyro_x");
    check_true(near(gy, -1.0f * DEG2RAD, 1e-4f), "gyro_y");
    check_true(near(gz, 2.0f * DEG2RAD, 1e-4f), "gyro_z");

    sensor.configure_gyro(2);
    check_true(c.writesIn(2).back() == V({0x01, 0x1D}), "configure_gyro_writes");
    be(c, 0, 0x33, {328, 0, 0});
    sensor.gyro(gx, gy, gz);
    check_true(near(gx, 10.0f * DEG2RAD), "configure_gyro_sensitivity");

    sensor.configure_accel(1);
    check_true(c.writesIn(2).back() == V({0x14, 0x1B}), "configure_accel_writes");
    be(c, 0, 0x2D, {8192, 0, 0});
    sensor.accel(ax, ay, az);
    check_true(near(ax, 9.80665f, 1e-4f), "configure_accel_sensitivity");

    sensor.configure_filters(5, 2, false, true);
    auto w2 = c.writesIn(2);
    check_true(w2[w2.size() - 2] == V({0x01, (5 << 3) | (2 << 1)}), "configure_filters_gyro");
    check_true(w2.back() == V({0x14, (2 << 3) | (1 << 1) | 1}), "configure_filters_accel");
    sensor.configure_filters(3, 3, true, true);

    sensor.configure_sample_rate(9, 0x123);
    w2 = c.writesIn(2);
    check_true(w2[w2.size() - 3] == V({0x00, 9}) && w2[w2.size() - 2] == V({0x10, 0x01}) &&
               w2.back() == V({0x11, 0x23}), "configure_sample_rate");
    check_true(c.bank == 0, "bank_zero_after_config");

    // read_all(): 14-byte burst (accel at ±4 g, gyro at ±1000 dps).
    be(c, 0, 0x2D, {8192, 0, 0, 262, 0, 0, 340});
    float a[3], g[3], t;
    sensor.read_all(a, g, t);
    check_true(near(a[0], 9.80665f, 1e-4f), "read_all_accel");
    check_true(near(g[0], (262 / 32.8f) * DEG2RAD, 1e-4f), "read_all_gyro");
    check_true(near(t, 340 / 333.87f + 21.0f, 1e-3f), "read_all_temp");

    be(c, 0, 0x39, {340});
    check_true(near(sensor.temperature(), 340 / 333.87f + 21.0f, 1e-3f), "temperature");

    int16_t rx, ry, rz;
    be(c, 0, 0x2D, {100, -200, 300});
    sensor.accel_raw(rx, ry, rz);
    check_true(rx == 100 && ry == -200 && rz == 300, "accel_raw");
    be(c, 0, 0x33, {-50, 60, -70});
    sensor.gyro_raw(rx, ry, rz);
    check_true(rx == -50 && ry == 60 && rz == -70, "gyro_raw");

    c.set(0, 0x1A, {0x01});
    check_true(sensor.data_ready(), "data_ready_true");
    c.set(0, 0x1A, {0x00});
    check_true(!sensor.data_ready(), "data_ready_false");

    sensor.set_sensors(false, true);
    check_true(c.writesIn(0).back() == V({0x07, 0x38}), "set_sensors_accel_off");
    sensor.set_sensors(true, false);
    check_true(c.writesIn(0).back() == V({0x07, 0x07}), "set_sensors_gyro_off");
    sensor.set_sensors(true, true);
    check_true(c.writesIn(0).back() == V({0x07, 0x00}), "set_sensors_on");

    sensor.set_sleep(true);
    check_true(c.writesIn(0).back() == V({0x06, 0x41}), "set_sleep_true");
    sensor.set_sleep(false);
    check_true(c.writesIn(0).back() == V({0x06, 0x01}), "set_sleep_false");

    sensor.set_gyro_offset(DEG2RAD * 1.0f, 0.0f, DEG2RAD * -1.0f);
    check_true(c.writesIn(2).back() == V({0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF}), "set_gyro_offset");

    sensor.set_accel_offset(0x1234, -1, 0);
    auto w1 = c.writesIn(1);
    check_true(w1[w1.size() - 3] == V({0x14, 0x24, 0x68}), "set_accel_offset_x");
    check_true(w1[w1.size() - 2] == V({0x17, 0xFF, 0xFE}), "set_accel_offset_y");
    c.set(1, 0x14, {0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02});
    int16_t ox, oy, oz;
    sensor.get_accel_offset(ox, oy, oz);
    check_true(ox == 0x1234 && oy == -1 && oz == 1, "get_accel_offset");
    check_true(c.bank == 0, "bank_zero_after_offsets");

    c.set(1, 0x28, {0xF6});
    check_true(near(sensor.read_timebase_correction(), -10 * 0.079f), "read_timebase_correction");

    // FIFO
    c.set(0, 0x70, {0x03, 0x45});
    check_true(sensor.fifo_count() == 0x345, "fifo_count");
    c.set(0, 0x70, {0x00, 0x02});
    c.set(0, 0x72, {0xAA, 0xBB});
    uint8_t fb[8] = {0};
    check_true(sensor.read_fifo(fb, 8) == 2 && fb[0] == 0xAA && fb[1] == 0xBB, "read_fifo");
    check_true(sensor.read_fifo(fb, 1) == 1, "read_fifo_limit");
    c.set(0, 0x70, {0x00, 0x00});
    check_true(sensor.read_fifo(fb, 8) == 0, "read_fifo_empty");

    sensor.enable_fifo(true, true, false);
    check_true(c.wrote(0, {0x67, 0x1E}), "enable_fifo_sources");
    check_true(c.wrote(0, {0x03, 0x40}), "enable_fifo_user_ctrl");
    auto w0 = c.writesIn(0);
    check_true(w0[w0.size() - 2] == V({0x68, 0x1F}) && w0.back() == V({0x68, 0x00}), "enable_fifo_reset");
    sensor.set_fifo_mode(true);
    check_true(c.writesIn(0).back() == V({0x69, 0x1F}), "set_fifo_mode_snapshot");
    sensor.set_fifo_mode(false);
    check_true(c.writesIn(0).back() == V({0x69, 0x00}), "set_fifo_mode_stream");

    // Interrupts
    sensor.set_interrupt_pin(true, false, true, true);
    check_true(c.writesIn(0).back() == V({0x0F, 0xB0}), "set_interrupt_pin");
    sensor.enable_interrupt(ICM20948Full::SOURCE_DATA_READY);
    check_true(c.writesIn(0).back() == V({0x11, 0x01}), "enable_data_ready");
    sensor.enable_interrupt(ICM20948Full::SOURCE_PLL_READY);
    check_true(c.writesIn(0).back() == V({0x10, 0x04}), "enable_pll");
    sensor.disable_interrupt(ICM20948Full::SOURCE_PLL_READY);
    check_true(c.writesIn(0).back() == V({0x10, 0x00}), "disable_pll");
    sensor.enable_interrupt(ICM20948Full::SOURCE_FIFO_OVERFLOW);
    check_true(c.writesIn(0).back() == V({0x12, 0x1F}), "enable_fifo_overflow");

    c.set(0, 0x19, {0x09, 0x01, 0x02});
    check_true(sensor.poll_interrupt() == (0x01 | 0x02 | 0x04 | 0x10), "poll_interrupt");
    c.set(0, 0x19, {0x04, 0x00, 0x00});
    check_true(sensor.poll_interrupt() == 0x08, "poll_interrupt_pll");

    // Wake-on-motion
    sensor.set_wake_on_motion(200.0f);
    check_true(c.wrote(2, {0x13, 50}), "wom_thr");
    check_true(c.wrote(2, {0x12, 0x03}), "wom_ctrl");
    check_true(c.writesIn(0).back() == V({0x10, 0x08}), "wom_int_enable");

    // Low power: LP_EN set last; configuration writes clear and restore it.
    c.set(0, 0x06, {0x01});
    sensor.set_low_power(true, true, 2, 3);
    check_true(c.wrote(0, {0x05, 0x70}), "low_power_lp_config");
    check_true(c.writesIn(0).back() == V({0x06, 0x21}), "low_power_lp_en_last");
    check_true(c.wrote(2, {0x15, 0x02}) && c.wrote(2, {0x02, 0x03}), "low_power_averaging");
    c.log.clear();
    sensor.configure_gyro(0);
    w0 = c.writesIn(0);
    check_true(w0.front() == V({0x06, 0x01}) && w0.back() == V({0x06, 0x21}), "lp_guard");
    sensor.set_low_power(false, false);
    check_true((c.get(0, 0x06) & 0x20) == 0, "low_power_off");

    // Magnetometer
    c.set(3, 0x17, {0x09});          // slave-4 read returns WIA2 = 0x09
    check_true(sensor.enable_mag(), "enable_mag");
    check_true(c.bank == 0, "enable_mag_bank_zero");
    check_true(c.wrote(3, {0x01, 0x07}), "enable_mag_i2c_mst_ctrl");
    check_true(c.wrote(3, {0x16, 0x01}) && c.wrote(3, {0x14, 0x32}), "enable_mag_reset");
    check_true(c.wrote(3, {0x16, 0x08}) && c.wrote(3, {0x14, 0x31}), "enable_mag_mode");
    auto w3 = c.writesIn(3);
    check_true(w3[w3.size() - 3] == V({0x03, 0x8C}) && w3[w3.size() - 2] == V({0x04, 0x10}) &&
               w3.back() == V({0x05, 0x89}), "enable_mag_slv0");
    check_true((c.get(0, 0x03) & 0x20) == 0x20, "enable_mag_user_ctrl");

    c.set(0, 0x3B, {0x01});
    le(c, 0, 0x3C, {1000, -500, 250});
    c.set(0, 0x42, {0x00, 0x00});
    float mx, my, mz;
    check_true(sensor.mag(mx, my, mz), "mag_ok");
    check_true(near(mx, 150.0f) && near(my, -75.0f) && near(mz, 37.5f), "mag_values");
    int16_t mrx, mry, mrz;
    check_true(sensor.mag_raw(mrx, mry, mrz) && mrx == 1000 && mry == -500 && mrz == 250, "mag_raw");
    check_true(sensor.mag_data_ready(), "mag_data_ready");
    c.set(0, 0x3B, {0x00});
    check_true(!sensor.mag_data_ready(), "mag_data_not_ready");
    c.set(0, 0x3B, {0x01});
    le(c, 0, 0x3C, {1, 2, 3});
    c.set(0, 0x43, {0x08});
    check_true(!sensor.mag(mx, my, mz), "mag_overflow_fails");
    check_true(sensor.mag_raw(mrx, mry, mrz) && mrx == 1, "mag_raw_ignores_overflow");

    check_true(sensor.mag_set_mode(2), "mag_set_mode");
    check_true(c.wrote(3, {0x16, 0x02}), "mag_set_mode_write");

    c.set(3, 0x17, {0x5A});
    uint8_t byte = 0;
    check_true(sensor.aux_read_byte(0x1E, 0x0A, byte) && byte == 0x5A, "aux_read_byte");
    check_true(c.wrote(3, {0x13, 0x9E}), "aux_read_addr");
    check_true(sensor.aux_write_byte(0x1E, 0x0B, 0xA5), "aux_write_byte");
    check_true(c.wrote(3, {0x13, 0x1E}) && c.wrote(3, {0x16, 0xA5}), "aux_write_regs");
    check_true(c.bank == 0, "bank_zero_after_aux");

    // WIA2 mismatch -> enable_mag fails.
    BankedMock bad;
    bad.set(0, 0x00, {0xEA});
    bad.set(0, 0x17, {0x40});
    ICM20948TestAccess bad_sensor(bad);
    check_true(!bad_sensor.enable_mag(), "wia2_mismatch_fails");
    check_true(!bad_sensor.mag(mx, my, mz), "mag_after_failed_enable");

    // Slave-4 NACK -> aux transfer fails.
    BankedMock nack;
    nack.set(0, 0x00, {0xEA});
    nack.set(0, 0x17, {0x10});
    ICM20948TestAccess nack_sensor(nack);
    check_true(!nack_sensor.aux_read_byte(0x0C, 0x01, byte), "aux_nack_fails");

    // Not enabled
    BankedMock fresh;
    fresh.set(0, 0x00, {0xEA});
    ICM20948TestAccess fresh_sensor(fresh);
    check_true(!fresh_sensor.mag(mx, my, mz) && !fresh_sensor.mag_raw(mrx, mry, mrz) &&
               !fresh_sensor.mag_data_ready(), "mag_not_enabled");

    float sa[3], sg[3];
    fresh_sensor.self_test(sa, sg);
    check_true(sa[0] == 0 && sa[2] == 0 && sg[0] == 0 && sg[2] == 0, "self_test_zero_on_constant");
    check_true(fresh.bank == 0, "self_test_bank_zero");

    fresh_sensor.reset();
    check_true(fresh.log.back().data == V({0x06, 0x80}), "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
