#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "AM2315.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static uint16_t crc16(const std::vector<uint8_t>& d) {
    uint16_t crc = 0xFFFF;
    for (uint8_t b : d) {
        crc ^= b;
        for (int k = 0; k < 8; k++) crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0xA001) : (uint16_t)(crc >> 1);
    }
    return crc;
}

static std::vector<uint8_t> frame(std::vector<uint8_t> body) {
    uint16_t crc = crc16(body);
    body.push_back(crc & 0xFF);
    body.push_back(crc >> 8);
    return body;
}

static std::vector<uint8_t> read_resp(std::vector<uint8_t> data) {
    std::vector<uint8_t> body = {0x03, (uint8_t)data.size()};
    body.insert(body.end(), data.begin(), data.end());
    return frame(body);
}

static bool last_write_is(const I2CConnectionMock& c, std::vector<uint8_t> w) {
    return c.writes().back() == w;
}

int main() {
    // CRC-16 (datasheet examples)
    check_true(crc16({0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA}) == 0xA531, "crc16_example1");
    check_true(crc16({0x03, 0x04, 0x03, 0x39, 0x01, 0x15}) == 0xFEE1, "crc16_example2");
    check_true(crc16({0x10, 0x10, 0x02, 0x01, 0x02}) == 0x92C0, "crc16_write");

    {
        I2CConnectionMock c;
        AM2315Full s(c);
        s.set_min_interval_ms(0);
        check_true(c.writes().empty(), "ctor_no_bus_access");

        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(c.writes().size() == 2 && c.writes()[0] == std::vector<uint8_t>{0x00}
                   && c.writes()[1] == std::vector<uint8_t>({0x03, 0x00, 0x04}), "read_writes");
        check_close(rh, 50.0f, 0.001f, "read_humidity");
        check_close(t, 25.0f, 0.001f, "read_temperature");

        // datasheet frame 03 04 03 39 01 15 E1 FE
        c.queueRead({0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE});
        check_true(s.read(t, rh), "datasheet_ok");
        check_close(rh, 82.5f, 0.001f, "datasheet_humidity");
        check_close(t, 27.7f, 0.001f, "datasheet_temperature");

        // sign-magnitude temperature
        c.queueRead(read_resp({0x01, 0xF4, 0x80, 0x65}));
        s.read(t, rh);
        check_close(t, -10.1f, 0.001f, "negative_temperature");

        // wake NACK ignored
        c.failNextWrites(1);
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        check_true(s.read(t, rh), "wake_nack_ignored");

        // CRC mismatch: retried once, then false
        std::vector<uint8_t> bad = read_resp({0x01, 0xF4, 0x00, 0xFA});
        bad.back() ^= 0xFF;
        c.clearWrites();
        c.queueRead(bad);
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        check_true(s.read(t, rh), "crc_retry_recovers");
        check_true(c.writes().size() == 4, "crc_retry_wakes_again");
        c.queueRead(bad);
        c.queueRead(bad);
        check_true(!s.read(t, rh), "crc_mismatch_fails");

        // bus error on read: retried once
        c.failNextReads(1);
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        check_true(s.read(t, rh), "bus_retry_recovers");
        c.failNextReads(2);
        check_true(!s.read(t, rh), "bus_error_fails");

        // error response
        c.queueRead({0x83, 0x00, 0x00, 0x00});
        check_true(!s.read(t, rh) && s.last_error() == 0x83, "error_response");
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        check_true(s.read(t, rh) && s.last_error() == 0, "error_cleared");

        // single values and raw
        c.queueRead(read_resp({0x00, 0x7B}));
        check_close(s.read_humidity(), 12.3f, 0.001f, "read_humidity_only");
        check_true(last_write_is(c, {0x03, 0x00, 0x02}), "read_humidity_cmd");
        c.queueRead(read_resp({0x80, 0x65}));
        check_close(s.read_temperature(), -10.1f, 0.001f, "read_temperature_only");
        check_true(last_write_is(c, {0x03, 0x02, 0x02}), "read_temperature_cmd");
        c.queueRead(read_resp({0x01, 0xF4, 0x80, 0x65}));
        uint16_t rr = 0, tr = 0;
        check_true(s.read_raw(rr, tr) && rr == 0x01F4 && tr == 0x8065, "read_raw");

        // fresh
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        c.queueRead(read_resp({0x01, 0xF9, 0x01, 0x00}));
        check_true(s.read_fresh(t, rh), "read_fresh_ok");
        check_close(rh, 50.5f, 0.001f, "read_fresh_humidity");
        check_close(t, 25.6f, 0.001f, "read_fresh_temperature");

        // registers
        uint8_t buf[10] = {0};
        c.queueRead(read_resp({1, 2, 3}));
        check_true(s.read_registers(0x08, 3, buf) && buf[0] == 1 && buf[2] == 3, "read_registers");
        check_true(!s.read_registers(0, 0, buf) && !s.read_registers(0, 11, buf), "read_registers_count");

        // device info
        uint16_t model; uint8_t ver; uint32_t id;
        c.queueRead(read_resp({0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78}));
        check_true(s.read_device_info(model, ver, id) && model == 0x090F && ver == 0x10 && id == 0x12345678, "device_info");
        check_true(last_write_is(c, {0x03, 0x08, 0x07}), "device_info_cmd");

        // user registers
        uint16_t u1, u2;
        c.queueRead(read_resp({0x12, 0x34, 0xAB, 0xCD}));
        check_true(s.read_user_registers(u1, u2) && u1 == 0x1234 && u2 == 0xABCD, "read_user_registers");
        c.queueRead(frame({0x10, 0x10, 0x04}));
        check_true(s.write_user_registers(0x1234, 0xABCD), "write_user_registers_ok");
        check_true(last_write_is(c, frame({0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD})), "write_user_registers_frame");
        c.queueRead(frame({0x10, 0x10, 0x02}));
        uint8_t two[2] = {1, 2};
        check_true(s.write_registers(0x10, two, 2), "write_registers_ok");
        check_true(last_write_is(c, {0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92}), "write_registers_crc");
        check_true(!s.write_registers(0x0F, two, 1) && !s.write_registers(0x13, two, 2) && !s.write_registers(0x10, two, 0),
                   "write_registers_reject");
        c.queueRead({0x82, 0x00, 0x00, 0x00, 0x00});
        check_true(!s.write_registers(0x10, two, 1) && s.last_error() == 0x82, "write_error_response");

        // wake
        c.clearWrites();
        s.wake();
        check_true(c.writes().size() == 1 && c.writes()[0] == std::vector<uint8_t>{0x00}, "wake_write");
    }
    {
        I2CConnectionMock c;
        AM2315Minimal m(c);
        m.set_min_interval_ms(0);
        float t, rh;
        c.queueRead(read_resp({0x01, 0xF4, 0x00, 0xFA}));
        check_true(m.read(t, rh), "minimal_class_read");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
