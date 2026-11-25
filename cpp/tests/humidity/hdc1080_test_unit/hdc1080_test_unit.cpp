#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "HDC1080.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static bool last_write_is(const I2CConnectionMock& c, std::vector<uint8_t> v) {
    return c.writes().back() == v;
}

int main() {
    {
        I2CConnectionMock c;
        HDC1080Full s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        s.init();
        check_true(last_write_is(c, {0x02, 0x10, 0x00}), "init_writes_default_config");

        c.clearWrites();
        c.queueRead({0x66, 0x66, 0x80, 0x00});
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(c.writes().size() == 1 && last_write_is(c, {0x00}), "read_trigger_pointer_0");
        check_close(t, 0x6666 / 65536.0f * 165.0f - 40.0f, 1e-3f, "read_temperature");
        check_close(rh, 50.0f, 1e-3f, "read_humidity");

        c.failNextReads(3);
        c.queueRead({0x66, 0x66, 0x80, 0x00});
        check_true(s.read(t, rh), "read_after_nack");
        c.failNextReads(1000);
        check_true(!s.read(t, rh), "read_timeout");
        c.failNextReads(0);

        c.clearWrites();
        c.queueRead({0x80, 0x00});
        check_close(s.read_temperature(), 42.5f, 1e-3f, "read_temperature_single");
        check_true(c.writes().size() == 2 && c.writes()[0] == std::vector<uint8_t>({0x02, 0x00, 0x00}), "single_mode_cfg");
        c.queueRead({0x80, 0x00});
        check_close(s.read_humidity(), 50.0f, 1e-3f, "read_humidity_single");
        check_true(last_write_is(c, {0x01}), "humidity_pointer_1");

        c.queueRead({0x12, 0x34, 0x56, 0x78});
        uint16_t rt = 0, rr = 0;
        check_true(s.read_raw(rt, rr) && rt == 0x1234 && rr == 0x5678, "read_raw");

        check_true(s.set_resolution(11, 8), "resolution_ok");
        check_true(last_write_is(c, {0x02, 0x16, 0x00}), "resolution_11_8");
        check_true(s.set_resolution(14, 14) && last_write_is(c, {0x02, 0x10, 0x00}), "resolution_14_14");
        check_true(!s.set_resolution(8, 14), "resolution_bad_t");
        check_true(!s.set_resolution(14, 12), "resolution_bad_h");

        s.set_heater(true);
        check_true(last_write_is(c, {0x02, 0x30, 0x00}), "heater_on");
        s.set_heater(false);
        check_true(last_write_is(c, {0x02, 0x10, 0x00}), "heater_off");
        c.queueRead({0x30, 0x00});
        check_true(s.is_heater_on(), "is_heater_on");
        c.queueRead({0x18, 0x00});
        check_true(s.battery_low(), "battery_low");
        c.queueRead({0x10, 0x00});
        check_true(!s.battery_low(), "battery_ok");
        c.queueRead({0x10, 0x00});
        check_true(s.read_config() == 0x1000, "read_config");

        s.set_heater(true);
        s.software_reset();
        const auto& w = c.writes();
        check_true(w[w.size() - 2] == std::vector<uint8_t>({0x02, 0x80, 0x00}) && w.back() == std::vector<uint8_t>({0x02, 0x30, 0x00}), "reset_then_config");

        c.queueRead({0x00, 0x12});
        c.queueRead({0x34, 0x56});
        c.queueRead({0x80, 0x00});
        check_true(s.serial_id() == (((uint64_t)0x12 << 24) | (0x3456u << 8) | 0x100), "serial_id");

        s.set_resolution(14, 14);
        check_close(s.conversion_time(), 0.01285f, 1e-5f, "conv_time_14_14");
        s.set_resolution(11, 8);
        check_close(s.conversion_time(), 0.00365f + 0.0025f, 1e-5f, "conv_time_11_8");

        c.queueRead({0x54, 0x49});
        check_true(s.manufacturer_id() == 0x5449, "manufacturer_id");
        c.queueRead({0x10, 0x50});
        check_true(s.device_id() == 0x1050, "device_id");
    }
    {
        I2CConnectionMock c;
        HDC1080Minimal m(c);
        float t, rh;
        c.queueRead({0x66, 0x66, 0x80, 0x00});
        check_true(m.read(t, rh), "minimal_class_read");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
