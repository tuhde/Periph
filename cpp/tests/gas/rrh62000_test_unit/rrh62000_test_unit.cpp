#include <stdio.h>
#include <string.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "Rrh62000.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b) { return fabsf(a - b) < 1e-3f; }

static uint8_t reg(const I2CConnectionMock& c, uint16_t a) {
    auto it = c.registers().find(a);
    return it == c.registers().end() ? 0 : it->second;
}

static const uint16_t WORDS[17] = {1234, 1000, 500, 100, 20, 52, 125, 203, 61, 140, 230,
                                   2345, 4567, 30, 812, 215, 0};

// Build a 37-byte block (status little-endian, 17 big-endian words, CRC-8) at register 0x00.
static void set_block(I2CConnectionMock& c, uint16_t status, const uint16_t* words, bool bad_crc = false) {
    uint8_t b[37];
    b[0] = status & 0xFF;
    b[1] = status >> 8;
    for (int i = 0; i < 17; i++) {
        b[2 + 2 * i] = words[i] >> 8;
        b[3 + 2 * i] = words[i] & 0xFF;
    }
    b[36] = RRH62000Minimal::crc8(b, 36) ^ (bad_crc ? 0xFF : 0x00);
    for (int i = 0; i < 37; i++) c.setRegister(i, {b[i]});
}

static bool last_write_is(const I2CConnectionMock& c, std::initializer_list<uint8_t> expect) {
    if (c.writes().empty()) return false;
    const auto& w = c.writes().back();
    return w.size() == expect.size() && memcmp(w.data(), expect.begin(), w.size()) == 0;
}

int main() {
    const uint8_t beef[2] = {0xBE, 0xEF}, zero[2] = {0, 0};
    check_true(RRH62000Minimal::crc8(beef, 2) == 0x92, "crc8_beef");
    check_true(RRH62000Minimal::crc8(zero, 2) == 0x81, "crc8_zero");

    I2CConnectionMock c;
    c.setRegister(0x75, {2, 7});
    RRH62000Full s(c, 0);
    check_true(s.init(), "init");
    check_true(last_write_is(c, {0x75}), "init_reads_fwver");

    c.setRegister(0x40, {0x01});
    set_block(c, 0x0005, WORDS);
    RRH62000Measurement m;
    check_true(s.read(m), "read");
    check_true(near(m.pm1_ug_m3, 5.2f) && near(m.pm2_5_ug_m3, 12.5f) && near(m.pm10_ug_m3, 20.3f), "read_pm");
    check_true(near(m.temperature_c, 23.45f) && near(m.humidity_rh, 45.67f), "read_t_rh");
    check_true(m.tvoc == 300 && m.eco2_ppm == 812 && near(m.iaq, 2.15f), "read_tvoc_eco2_iaq");
    size_t n = c.writes().size();
    check_true(c.writes()[n - 2] == std::vector<uint8_t>{0x40} && c.writes()[n - 1] == std::vector<uint8_t>{0x00},
               "read_polls_data_then_block");

    RRH62000Data d;
    check_true(s.read_all(d), "read_all");
    check_true(d.status.raw == 5 && d.status.high_concentration && d.status.fan_speed &&
               !d.status.dust_accumulation && !d.status.fan, "read_all_status");
    check_true(near(d.particles.nc_0_3, 123.4f) && near(d.particles.nc_4, 2.0f), "read_all_nc");
    check_true(near(d.particles.pm1_smoke_ug_m3, 6.1f) && near(d.particles.pm10_smoke_ug_m3, 23.0f), "read_all_smoke");

    uint16_t neg[17] = {0};
    neg[11] = (uint16_t)-512;
    set_block(c, 0x0002, neg);
    c.clearWrites();
    RRH62000Status st;
    check_true(s.read_status(st) && st.raw == 2 && st.dust_accumulation && !st.high_concentration, "read_status");
    check_true(c.writes().size() == 1, "read_status_no_data_poll");
    check_true(s.read_all(d) && near(d.temperature_c, -5.12f), "negative_temperature");
    set_block(c, 0, WORDS);
    RRH62000Particles p;
    check_true(s.read_particles(p) && near(p.pm2_5_smoke_ug_m3, 14.0f) && near(p.nc_1, 50.0f), "read_particles");

    set_block(c, 0, WORDS, true);
    check_true(!s.read(m) && s.last_error() == RRH62000Minimal::ERR_CRC, "crc_error");
    for (int i = 0; i < 37; i++) c.setRegister(i, {0xFF});
    check_true(!s.read(m) && s.last_error() == RRH62000Minimal::ERR_INVALID, "startup_block");
    set_block(c, 0, WORDS);
    c.failNextWrites(1);
    check_true(!s.read(m) && s.last_error() == RRH62000Minimal::ERR_BUS, "bus_error");

    c.setRegister(0x40, {0x00});
    check_true(s.data_ready() == 0, "data_ready_false");
    c.setRegister(0x40, {0x01});
    check_true(s.data_ready() == 1, "data_ready_true");

    check_true(s.sleep() && last_write_is(c, {0x50, 0x00}), "sleep");
    check_true(s.wake() && last_write_is(c, {0x50, 0x80}), "wake");
    check_true(s.start_cleaning() && last_write_is(c, {0x51, 0x01}), "start_cleaning");
    c.setRegister(0x74, {0x01});
    check_true(s.cleaning_done() == 1, "cleaning_done");
    check_true(s.soft_reset() && last_write_is(c, {0x52, 0x81}), "soft_reset");
    c.failNextWrites(1);
    check_true(s.soft_reset(), "soft_reset_nack_ignored");

    check_true(s.set_moving_average(30) && s.get_moving_average() == 30, "moving_average");
    check_true(s.set_fan_speed(70) && s.get_fan_speed() == 70, "fan_speed");
    check_true(s.set_cleaning_interval(86400), "set_cleaning_interval");
    check_true(reg(c, 0x5A) == 0x0B && reg(c, 0x5B) == 0x40, "cleaning_interval_bytes");
    check_true(s.get_cleaning_interval() == 86400, "get_cleaning_interval");
    check_true(s.set_cleaning_time(20) && s.get_cleaning_time() == 20, "cleaning_time");
    check_true(!s.set_moving_average(0) && s.last_error() == RRH62000Minimal::ERR_ARG, "moving_average_range");
    check_true(!s.set_moving_average(61), "moving_average_range_hi");
    check_true(!s.set_fan_speed(59) && !s.set_fan_speed(101), "fan_speed_range");
    check_true(!s.set_cleaning_interval(31) && !s.set_cleaning_interval(1814430), "cleaning_interval_range");
    check_true(!s.set_cleaning_time(61), "cleaning_time_range");

    c.setRegister(0x71, {0x00, 0x01, 0xE2, 0x40});
    uint32_t ohms = 0;
    check_true(s.read_mox_resistance(ohms) && ohms == 123456, "mox_resistance");
    c.setRegister(0x72, {1, 2, 3, 4, 5, 6});
    uint8_t uid[6];
    const uint8_t uid_exp[6] = {1, 2, 3, 4, 5, 6};
    check_true(s.unique_id(uid) && memcmp(uid, uid_exp, 6) == 0, "unique_id");
    c.setRegister(0x73, {3, 1, 4});
    uint8_t a, b, pa;
    check_true(s.algorithm_version(a, b, pa) && a == 3 && b == 1 && pa == 4, "algorithm_version");
    c.setRegister(0x75, {2, 7});
    check_true(s.firmware_version(a, b) && a == 2 && b == 7, "firmware_version");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
