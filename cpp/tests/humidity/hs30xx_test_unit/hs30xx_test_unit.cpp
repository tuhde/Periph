#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "HS30xx.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static std::vector<uint8_t> frame(uint16_t rh, uint16_t t, uint8_t status = 0) {
    return {(uint8_t)((status << 6) | (rh >> 8)), (uint8_t)rh, (uint8_t)(t >> 6), (uint8_t)((t & 0x3F) << 2)};
}

typedef std::vector<std::vector<uint8_t>> Writes;

static bool writes_are(const I2CConnectionMock& c, const Writes& expected) {
    return c.writes() == expected;
}

static int power_cycles = 0;
static void power_cycle() { power_cycles++; }
static void noop_cycle() {}

int main() {
    // constructor: no bus access; aliases
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        HS3001Full a(c);
        HS3002Minimal b(c);
        (void)a; (void)b;
        check_true(c.writes().empty(), "constructor_no_bus_access");
        check_true(sizeof(HS3003Full) == sizeof(HS30xxFull), "alias_hs3003_full");
    }

    // conversions: 0x2000 -> 50 %RH, 0x1000 -> 1.25 degC
    {
        I2CConnectionMock c;
        HS30xxMinimal s(c);
        float t = 0, rh = 0;
        c.queueRead(frame(0x2000, 0x1000));
        check_true(s.read(t, rh), "read_ok");
        check_true(writes_are(c, {{}}), "read_request_is_empty_write");
        check_close(rh, 50.0f, 0.01f, "humidity_50");
        check_close(t, 1.25f, 0.01f, "temperature_1_25");
        c.queueRead(frame(0x3FFF, 0x3FFF));
        s.read(t, rh);
        check_close(rh, 100.0f, 1e-3f, "humidity_full_scale");
        check_close(t, 125.0f, 1e-3f, "temperature_full_scale");
        c.queueRead(frame(0, 0));
        s.read(t, rh);
        check_close(rh, 0.0f, 1e-3f, "humidity_zero");
        check_close(t, -40.0f, 1e-3f, "temperature_zero");
    }

    // undetermined bits 1:0 of byte 3 are masked off
    {
        I2CConnectionMock c;
        HS30xxMinimal s(c);
        float t, rh;
        c.queueRead({0x20, 0x00, 0x10, 0x03});
        s.read(t, rh);
        check_close(t, 1024.0f / 16383.0f * 165.0f - 40.0f, 1e-3f, "masks_undetermined_bits");
    }

    // stale then valid: one retry; stale twice: failure
    {
        I2CConnectionMock c;
        HS30xxMinimal s(c);
        float t, rh;
        c.queueRead(frame(0x2000, 0x1000, 1));
        c.queueRead(frame(0x2000, 0x1000));
        check_true(s.read(t, rh), "stale_retry_ok");
        check_close(rh, 50.0f, 0.01f, "stale_retry_value");
        check_true(writes_are(c, {{}}), "stale_retry_single_request");
        c.queueRead(frame(0, 0, 1));
        c.queueRead(frame(0, 0, 1));
        check_true(!s.read(t, rh), "stale_twice_fails");
    }

    // bus error on the request
    {
        I2CConnectionMock c;
        HS30xxMinimal s(c);
        float t, rh;
        c.failNextWrites(1);
        check_true(!s.read(t, rh), "write_nack_fails");
    }

    // Full: single values, raw, wait
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        c.queueRead(frame(0x2000, 0x1000));
        check_close(s.read_temperature(), 1.25f, 0.01f, "read_temperature");
        c.queueRead({0x20, 0x00});
        check_close(s.read_humidity(), 50.0f, 0.01f, "read_humidity");
        uint16_t rh = 0, t = 0;
        uint8_t st = 9;
        c.queueRead(frame(0x1234, 0x0ABC));
        check_true(s.read_raw(rh, t, st) && rh == 0x1234 && t == 0x0ABC && st == 0, "read_raw");
        c.queueRead(frame(0x1234, 0x0ABC, 1));
        s.read_raw(rh, t, st);
        check_true(st == 1, "read_raw_status_stale");
        s.set_measurement_wait(5);
        c.queueRead(frame(0x2000, 0x1000));
        check_close(s.read_humidity(), 50.0f, 0.01f, "read_after_wait_override");
    }

    // programming mode and read_nvm
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        s.enter_programming_mode();
        s.exit_programming_mode();
        check_true(writes_are(c, {{0xA0, 0, 0}, {0x80, 0, 0}}), "programming_commands");
        c.clearWrites();
        uint16_t v = 0;
        c.queueRead({0x81, 0x12, 0x34});
        check_true(s.read_nvm(0x1E, v) && v == 0x1234, "read_nvm_value");
        check_true(writes_are(c, {{0x1E, 0, 0}}), "read_nvm_command");
        c.queueRead({0x80, 0x12, 0x34});
        check_true(!s.read_nvm(0x1E, v), "read_nvm_bad_status");
    }

    // NVM access without a power_cycle fails before any bus traffic
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        uint8_t hb, tb;
        uint32_t id;
        check_true(!s.get_resolution(hb, tb, nullptr), "get_resolution_needs_power_cycle");
        check_true(!s.set_resolution(12, 12, nullptr), "set_resolution_needs_power_cycle");
        check_true(!s.read_id(id, nullptr), "read_id_needs_power_cycle");
        check_true(c.writes().empty(), "no_bus_without_power_cycle");
    }

    // get_resolution: bits 11:10 (default 0xC00 = 14 bit)
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        uint8_t hb = 0, tb = 0;
        c.queueRead({0x81, 0x0C, 0x00});
        c.queueRead({0x81, 0x08, 0x05});
        power_cycles = 0;
        check_true(s.get_resolution(hb, tb, power_cycle) && hb == 14 && tb == 12, "get_resolution");
        check_true(power_cycles == 1, "get_resolution_power_cycled");
        check_true(writes_are(c, {{0xA0, 0, 0}, {0x06, 0, 0}, {0x11, 0, 0}, {0x80, 0, 0}}), "get_resolution_sequence");
    }

    // set_resolution: read-modify-write preserves the other bits
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        c.queueRead({0x81, 0x0C, 0x07});
        c.queueRead({0x81, 0x0C, 0x21});
        check_true(s.set_resolution(8, 12, noop_cycle), "set_resolution_ok");
        check_true(writes_are(c, {{0xA0, 0, 0}, {0x06, 0, 0}, {0x46, 0x00, 0x07},
                                  {0x11, 0, 0}, {0x51, 0x08, 0x21}, {0x80, 0, 0}}), "set_resolution_sequence");
    }

    // set_resolution: one channel only; invalid value touches no bus
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        c.queueRead({0x81, 0x00, 0x00});
        check_true(s.set_resolution(0, 10, noop_cycle), "set_resolution_temperature_only");
        check_true(writes_are(c, {{0xA0, 0, 0}, {0x11, 0, 0}, {0x51, 0x04, 0x00}, {0x80, 0, 0}}), "set_resolution_temperature_sequence");
        c.clearWrites();
        check_true(!s.set_resolution(9, 0, noop_cycle), "set_resolution_invalid");
        check_true(c.writes().empty(), "set_resolution_invalid_no_bus");
    }

    // a failed NVM read still leaves programming mode
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        uint8_t hb, tb;
        c.queueRead({0x00, 0, 0});
        check_true(!s.get_resolution(hb, tb, noop_cycle), "read_failure_fails");
        check_true(c.writes().back() == std::vector<uint8_t>({0x80, 0, 0}), "read_failure_exits_programming");
    }

    // read_id
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        uint32_t id = 0;
        c.queueRead({0x81, 0xDE, 0xAD});
        c.queueRead({0x81, 0xBE, 0xEF});
        check_true(s.read_id(id, noop_cycle) && id == 0xDEADBEEFu, "read_id");
    }

    // dew point: 25 degC / 50 %RH -> about 13.86 degC
    {
        I2CConnectionMock c;
        HS30xxFull s(c);
        c.queueRead(frame(8191, 6453));
        check_close(s.dew_point(), 13.86f, 0.1f, "dew_point");
        c.queueRead(frame(0, 0x1000));
        check_close(s.dew_point(), -273.15f, 1e-3f, "dew_point_zero_humidity");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
