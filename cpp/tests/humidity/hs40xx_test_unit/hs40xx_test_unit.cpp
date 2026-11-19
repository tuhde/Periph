#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "HS40xx.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static uint8_t crc8(const std::vector<uint8_t>& d) {
    uint8_t crc = 0xFF;
    for (uint8_t b : d) {
        crc ^= b;
        for (int i = 0; i < 8; i++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x1D) : (uint8_t)(crc << 1);
    }
    return crc;
}

static std::vector<uint8_t> ht(uint16_t h, uint16_t t) {
    std::vector<uint8_t> d = {(uint8_t)(h >> 8), (uint8_t)h, (uint8_t)(t >> 8), (uint8_t)t};
    d.push_back(crc8(d));
    return d;
}

static std::vector<uint8_t> t_only(uint16_t t) {
    std::vector<uint8_t> d = {(uint8_t)(t >> 8), (uint8_t)t};
    d.push_back(crc8({0, 0, d[0], d[1]}));
    return d;
}

typedef std::vector<std::vector<uint8_t>> Writes;

static bool writes_are(const I2CConnectionMock& c, const Writes& expected) {
    return c.writes() == expected;
}

int main() {
    check_true(crc8({0x05, 0x80, 0x05, 0x80}) == 0xF2, "crc_datasheet_example");

    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        HS4001Full a(c);
        HS4003Minimal b(c);
        (void)a; (void)b;
        check_true(c.writes().empty(), "constructor_no_bus_access");
        check_true(sizeof(HS4003Full) == sizeof(HS40xxFull), "alias_hs4003_full");
    }

    // datasheet example frame
    {
        I2CConnectionMock c;
        HS40xxMinimal s(c);
        float t = 0, rh = 0;
        c.queueRead({0x05, 0x80, 0x05, 0x80, 0xF2});
        check_true(s.read(t, rh), "read_ok");
        check_true(writes_are(c, {{0xF5}}), "read_command");
        check_close(rh, 8.59f, 0.01f, "example_humidity");
        check_close(t, -25.8f, 0.05f, "example_temperature");
        c.queueRead(ht(0x3FFF, 0x3FFF));
        s.read(t, rh);
        check_close(rh, 100.0f, 1e-3f, "humidity_full_scale");
        check_close(t, 125.0f, 1e-3f, "temperature_full_scale");
        c.queueRead(ht(0, 0));
        s.read(t, rh);
        check_close(rh, 0.0f, 1e-3f, "humidity_zero");
        check_close(t, -40.0f, 1e-3f, "temperature_zero");
    }

    // NACKed read header is retried; a persistent NACK fails
    {
        I2CConnectionMock c;
        HS40xxMinimal s(c);
        float t, rh;
        c.failNextReads(2);
        c.queueRead(ht(0x2000, 0x1000));
        check_true(s.read(t, rh), "nack_retry_ok");
        check_close(rh, 50.0f, 0.01f, "nack_retry_value");
        c.failNextReads(1000);
        check_true(!s.read(t, rh), "nack_timeout_fails");
    }

    // CRC mismatch: retried once, then fails
    {
        I2CConnectionMock c;
        HS40xxMinimal s(c);
        float t, rh;
        std::vector<uint8_t> bad = ht(0x2000, 0x1000);
        bad[4] ^= 0xFF;
        c.queueRead(bad);
        c.queueRead(ht(0x2000, 0x1000));
        check_true(s.read(t, rh), "crc_retry_ok");
        check_true(writes_are(c, {{0xF5}, {0xF5}}), "crc_retry_reissues_command");
        c.queueRead(bad);
        c.queueRead(bad);
        check_true(!s.read(t, rh), "crc_twice_fails");
    }

    // write NACK
    {
        I2CConnectionMock c;
        HS40xxMinimal s(c);
        float t, rh;
        c.failNextWrites(1);
        check_true(!s.read(t, rh), "write_nack_fails");
    }

    // Full: single values, hold, raw
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        c.queueRead(t_only(0x1000));
        check_close(s.read_temperature(), 4096.0f / 16383.0f * 165.0f - 40.0f, 1e-3f, "read_temperature");
        check_true(writes_are(c, {{0xF3}}), "read_temperature_command");
        c.queueRead(ht(0x2000, 0x1000));
        check_close(s.read_humidity(), 50.0f, 0.01f, "read_humidity");
        float t, rh;
        c.clearWrites();
        c.queueRead(ht(0x2000, 0x1000));
        check_true(s.read_hold(t, rh), "read_hold_ok");
        check_true(writes_are(c, {{0xE5}}), "read_hold_command");
        uint16_t hr, tr;
        c.queueRead(ht(0x1234, 0x0ABC));
        check_true(s.read_raw(hr, tr) && hr == 0x1234 && tr == 0x0ABC, "read_raw");
    }

    // registers
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        uint8_t v = 0;
        c.queueRead({0x5A});
        check_true(s.read_register(0x02, v) && v == 0x5A, "read_register");
        check_true(writes_are(c, {{0xA7, 0x02}}), "read_register_command");
        c.clearWrites();
        s.write_register(0x03, 0x0A);
        check_true(writes_are(c, {{0xA6, 0x03, 0x0A}}), "write_register_command");
    }

    // resolution
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        c.queueRead({0xF3});
        check_true(s.set_resolution(8, 12), "set_resolution_ok");
        check_true(writes_are(c, {{0xA7, 0}, {0xA6, 0, 0xF2}}), "set_resolution_sequence");
        check_true(!s.set_resolution(9, 0), "set_resolution_invalid");
        check_true(c.writes().size() == 2, "set_resolution_invalid_no_bus");
        c.queueRead({0x0B});
        uint8_t h, t;
        check_true(s.get_resolution(h, t) && h == 12 && t == 14, "get_resolution");
    }

    // periodic, alerts
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        c.queueRead({0x00});
        c.queueRead({0x01});
        check_true(s.start_periodic(2.5f), "start_periodic");
        check_true(writes_are(c, {{0xA7, 3}, {0xA7, 2}, {0xA6, 2, 0xA1}}), "start_periodic_sequence");
        check_true(!s.start_periodic(3.0f), "start_periodic_invalid");
        float t, rh;
        uint8_t st = 0xFF;
        c.queueRead(ht(0x2000, 0x1000));
        check_true(s.read_periodic(t, rh, st) && st == 0, "read_periodic_no_alert");
        s.stop_periodic();
        check_true(c.writes().back() == std::vector<uint8_t>{0x30}, "stop_periodic");
    }
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        check_true(s.poll_interrupt() == 0, "poll_interrupt_without_alerts");
        c.queueRead({0x00});
        s.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH);
        check_true(writes_are(c, {{0xA7, 3}, {0xA6, 3, 0x02}}), "enable_interrupt");
        std::vector<uint8_t> f = ht(0x2000, 0x1000);
        f.insert(f.begin(), 0x0A);
        c.queueRead(f);
        float t, rh;
        uint8_t st = 0;
        check_true(s.read_periodic(t, rh, st) && st == 0x0A, "read_periodic_status");
        f[0] = 0x02;
        c.queueRead(f);
        check_true(s.poll_interrupt() == 0x02, "poll_interrupt");
        c.queueRead({0x02});
        s.disable_interrupt(HS40xxFull::SOURCE_RH_HIGH);
        check_true(c.writes().back() == (std::vector<uint8_t>{0xA6, 3, 0x00}), "disable_interrupt");
    }

    // thresholds
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        s.set_alert_thresholds(60.0f, 55.0f);
        check_true(writes_are(c, {{0xA6, 0x0F, 0x66}, {0xA6, 0x10, 0x26}, {0xA6, 0x0D, 0x33}, {0xA6, 0x0E, 0x23}}),
                   "rh_thresholds");
    }
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        s.set_alert_thresholds(NAN, NAN, NAN, NAN, 125.0f, NAN, -40.0f, NAN);
        check_true(writes_are(c, {{0xA6, 0x07, 0xFF}, {0xA6, 0x08, 0x3F}, {0xA6, 0x09, 0x00}, {0xA6, 0x0A, 0x00}}),
                   "temp_thresholds");
    }

    // polarity
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        c.queueRead({0xB1});
        s.set_alert_polarity(false);
        check_true(c.writes().back() == (std::vector<uint8_t>{0xA6, 2, 0xB0}), "set_alert_polarity");
    }

    // ID, reset
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        uint32_t id = 0;
        c.queueRead({0xDE, 0xAD, 0xBE, 0xEF});
        check_true(s.read_id(id) && id == 0xDEADBEEF, "read_id");
        check_true(writes_are(c, {{0xD7}}), "read_id_command");
        c.clearWrites();
        check_true(s.soft_reset(), "soft_reset");
        check_true(writes_are(c, {{0x30}, {0xFE}}), "soft_reset_sequence");
    }

    // dew point
    {
        I2CConnectionMock c;
        HS40xxFull s(c);
        c.queueRead(ht((uint16_t)(0.5f * 16383), (uint16_t)((25.0f + 40.0f) / 165.0f * 16383)));
        check_close(s.dew_point(), 13.86f, 0.1f, "dew_point");
        c.queueRead(ht(0, 0x1000));
        check_close(s.dew_point(), -273.15f, 1e-3f, "dew_point_zero_humidity");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
