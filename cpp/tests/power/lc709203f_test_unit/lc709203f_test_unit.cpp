#include <stdio.h>
#include <math.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "LC709203F.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Each command code holds one (low, high, crc) word; writes do not overlap neighbours.
class WordMock : public I2CConnectionMock {
public:
    std::map<uint8_t, std::vector<uint8_t>> words;

    void put(uint8_t cmd, uint16_t value) {
        uint8_t lo = value & 0xFF, hi = value >> 8;
        uint8_t frame[5] = {0x16, cmd, 0x17, lo, hi};
        words[cmd] = {lo, hi, lc709203f_crc8(frame, 5)};
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (len == 4) words[data[0]] = {data[1], data[2], data[3]};
    }
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        auto it = words.find(data[0]);
        for (size_t i = 0; i < buf_len; i++) buf[i] = (it != words.end() && i < it->second.size()) ? it->second[i] : 0;
    }
};

static bool last_write_is(WordMock& c, uint8_t cmd, uint8_t lo, uint8_t hi) {
    const auto& w = c.writes().back();
    uint8_t frame[4] = {0x16, cmd, lo, hi};
    return w.size() == 4 && w[0] == cmd && w[1] == lo && w[2] == hi && w[3] == lc709203f_crc8(frame, 4);
}

static bool wrote(WordMock& c, uint8_t cmd, uint8_t lo, uint8_t hi) {
    for (const auto& w : c.writes())
        if (w.size() == 4 && w[0] == cmd && w[1] == lo && w[2] == hi) return true;
    return false;
}

int main() {
    uint8_t d1[5] = {0x16, 0x09, 0x17, 0xC2, 0x0E};
    uint8_t d2[4] = {0x16, 0x09, 0x55, 0xAA};
    check_true(lc709203f_crc8(d1, 5) == 0x86, "crc_read");
    check_true(lc709203f_crc8(d2, 4) == 0x3B, "crc_write");

    WordMock conn;
    conn.put(0x13, 8);
    conn.put(0x14, 0);
    LC709203FFull sensor(conn);

    check_true(wrote(conn, 0x15, 0x01, 0x00), "init_power_mode");
    check_true(wrote(conn, 0x0B, 0x19, 0x00), "init_apa");
    check_true(wrote(conn, 0x12, 0x01, 0x00), "init_profile");
    check_true(wrote(conn, 0x16, 0x00, 0x00), "init_status_i2c");
    check_true(wrote(conn, 0x08, 0xA6, 0x0B), "init_temp_25C");

    conn.put(0x09, 3778);
    check_true(fabsf(sensor.voltage() - 3.778f) < 1e-4f, "voltage");
    conn.put(0x0F, 755);
    check_true(fabsf(sensor.percent() - 75.5f) < 1e-4f, "percent");
    conn.put(0x08, 0x0BA6);
    check_true(fabsf(sensor.temperature() - 25.0f) < 0.06f, "temperature");

    conn.words[0x09] = {0xC2, 0x0E, 0x00};
    sensor.voltage();
    check_true(!sensor.valid(), "crc_mismatch_invalid");
    conn.put(0x09, 3778);
    sensor.voltage();
    check_true(sensor.valid(), "crc_ok_valid");

    sensor.set_temperature(100.0f);
    check_true(last_write_is(conn, 0x08, 0x04, 0x0D), "set_temperature_clamp_high");
    sensor.set_temperature(-50.0f);
    check_true(last_write_is(conn, 0x08, 0xE4, 0x09), "set_temperature_clamp_low");

    conn.put(0x0D, 42);
    check_true(sensor.rsoc() == 42, "rsoc");
    sensor.set_rsoc(55);
    check_true(last_write_is(conn, 0x0D, 55, 0), "set_rsoc");
    sensor.init_rsoc();
    check_true(last_write_is(conn, 0x07, 0x55, 0xAA), "init_rsoc");
    sensor.before_rsoc();
    check_true(last_write_is(conn, 0x04, 0x55, 0xAA), "before_rsoc");

    sensor.use_thermistor(3380);
    check_true(wrote(conn, 0x06, 0x34, 0x0D) && last_write_is(conn, 0x16, 1, 0), "use_thermistor");
    conn.put(0x16, 1);
    check_true(sensor.is_thermistor_mode(), "is_thermistor_mode");
    sensor.use_i2c_temperature(25.0f);
    check_true(last_write_is(conn, 0x08, 0xA6, 0x0B), "use_i2c_temperature");

    check_true(sensor.set_current_direction(LC709203FFull::DIRECTION_DISCHARGE), "direction_ok");
    check_true(last_write_is(conn, 0x0A, 0xFF, 0xFF), "direction_discharge");
    check_true(!sensor.set_current_direction(5), "direction_invalid");

    sensor.set_apa(0x2D);
    check_true(last_write_is(conn, 0x0B, 0x2D, 0), "set_apa");
    sensor.set_apt(0x1234);
    check_true(last_write_is(conn, 0x0C, 0x34, 0x12), "set_apt");
    sensor.set_profile(0);
    check_true(wrote(conn, 0x12, 0, 0) && last_write_is(conn, 0x15, 1, 0), "set_profile");

    conn.put(0x1A, 0x0301);
    check_true(sensor.profile_id() == 0x0301, "profile_id");
    conn.put(0x11, 0x1234);
    check_true(sensor.ic_version() == 0x1234, "ic_version");

    sensor.sleep();
    check_true(last_write_is(conn, 0x15, 2, 0), "sleep");
    conn.put(0x15, 2);
    check_true(sensor.is_sleeping(), "is_sleeping");

    sensor.set_alarm_rsoc(20);
    sensor.set_alarm_voltage(3.5f);
    check_true(last_write_is(conn, 0x14, 0xAC, 0x0D), "set_alarm_voltage");
    conn.put(0x0D, 10); conn.put(0x09, 3400);
    check_true(sensor.pollInterrupt() == 3, "poll_both");
    conn.put(0x0D, 50); conn.put(0x09, 3900);
    check_true(sensor.pollInterrupt() == 0, "poll_none");

    check_true(LC709203FMinimal::apaFor(700) == 0x14, "apa_interp");
    check_true(LC709203FMinimal::apaFor(50) == 0x08 && LC709203FMinimal::apaFor(9000) == 0x36, "apa_clamp");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
