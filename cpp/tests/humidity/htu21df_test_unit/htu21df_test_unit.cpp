#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "HTU21DF.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static uint8_t crc8(uint8_t a, uint8_t b) {
    uint8_t d[2] = {a, b};
    uint8_t crc = 0x00;
    for (int i = 0; i < 2; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

static void queue_frame(I2CConnectionMock& c, uint16_t code, bool corrupt = false) {
    uint8_t m = code >> 8, l = code & 0xFF, crc = crc8(m, l);
    if (corrupt) crc ^= 0xFF;
    c.queueRead({m, l, crc});
}

static uint16_t rh_code(float rh) { return (uint16_t)((lroundf((rh + 6.0f) * 65536.0f / 125.0f) & 0xFFFC) | 0x02); }
static uint16_t t_code(float t) { return (uint16_t)(lroundf((t + 46.85f) * 65536.0f / 175.72f) & 0xFFFC); }

// First byte of every logged write.
static std::vector<uint8_t> cmds(const I2CConnectionMock& c) {
    std::vector<uint8_t> out;
    for (const auto& w : c.writes()) out.push_back(w[0]);
    return out;
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

static bool last_write_is(const I2CConnectionMock& c, uint8_t a, uint8_t b) {
    const auto& w = c.writes().back();
    return w.size() == 2 && w[0] == a && w[1] == b;
}

int main() {
    {
        I2CConnectionMock c;
        HTU21DFFull s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        s.init();
        check_true(eq(cmds(c), {0xFE}), "init_soft_reset");

        // read: 0xF3 then 0xF5, status bits ignored
        c.clearWrites();
        queue_frame(c, t_code(25.0f));
        queue_frame(c, rh_code(50.0f));
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(eq(cmds(c), {0xF3, 0xF5}), "read_commands");
        check_close(rh, 50.0f, 0.05f, "read_humidity");
        check_close(t, 25.0f, 0.05f, "read_temperature");

        // datasheet value 0x683A -> status bits cleared 0x6838
        queue_frame(c, 0x683A); queue_frame(c, 0x683A);
        s.read(t, rh);
        check_close(t, -46.85f + 175.72f * 0x6838 / 65536.0f, 1e-3f, "conv_temperature");
        check_close(rh, -6.0f + 125.0f * 0x6838 / 65536.0f, 1e-3f, "conv_humidity");

        // NACKed read header: polls until ready
        c.failNextReads(3);
        queue_frame(c, t_code(20.0f));
        queue_frame(c, rh_code(40.0f));
        check_true(s.read(t, rh), "read_after_nacks");
        check_close(rh, 40.0f, 0.05f, "read_after_nacks_rh");

        // chip never ready
        c.failNextReads(1000);
        check_true(!s.read(t, rh), "timeout_fails");
        c.failNextReads(0);

        // CRC mismatch: retried once, then fails
        queue_frame(c, 0x6000, true);
        queue_frame(c, 0x6000, true);
        check_true(!s.read(t, rh), "crc_mismatch_fails");
        c.clearWrites();
        queue_frame(c, 0x6000, true);
        queue_frame(c, t_code(25.0f));
        queue_frame(c, rh_code(50.0f));
        check_true(s.read(t, rh), "crc_retry_recovers");
        check_true(eq(cmds(c), {0xF3, 0xF3, 0xF5}), "crc_retry_commands");

        // not clamped
        queue_frame(c, 0x0000); queue_frame(c, 0x0000);
        s.read(t, rh);
        check_close(rh, -6.0f, 1e-6f, "humidity_not_clamped");

        // single values and raw codes
        c.clearWrites();
        queue_frame(c, rh_code(30.0f));
        check_close(s.read_humidity(), 30.0f, 0.05f, "read_humidity_only");
        queue_frame(c, t_code(-10.0f));
        check_close(s.read_temperature(), -10.0f, 0.05f, "read_temperature_only");
        check_true(eq(cmds(c), {0xF5, 0xF3}), "single_commands");
        uint16_t rt = 0, rr = 0;
        queue_frame(c, 0x1237); queue_frame(c, 0x4321);
        check_true(s.read_raw(rt, rr) && rt == 0x1234 && rr == 0x4320, "read_raw");

        // compensated humidity: 50 %RH at 35 degC -> 51.5
        queue_frame(c, t_code(35.0f)); queue_frame(c, rh_code(50.0f));
        check_close(s.read_humidity_compensated(), 51.5f, 0.1f, "humidity_compensated");

        // hold master
        c.clearWrites();
        s.set_hold_master(true);
        queue_frame(c, t_code(25.0f)); queue_frame(c, rh_code(50.0f));
        s.read(t, rh);
        check_true(eq(cmds(c), {0xE3, 0xE5}), "hold_master_commands");
        s.set_hold_master(false);

        // user register
        c.setRegister(0xE7, {0x02});
        c.clearWrites();
        check_true(s.set_resolution(3), "set_resolution_3");
        check_true(last_write_is(c, 0xE6, 0x83), "set_resolution_3_write");
        s.set_resolution(1);
        check_true(last_write_is(c, 0xE6, 0x03), "set_resolution_1_write");
        s.set_resolution(2);
        check_true(last_write_is(c, 0xE6, 0x82), "set_resolution_2_write");
        check_true(!s.set_resolution(4), "set_resolution_invalid");
        c.setRegister(0xE7, {0x83});
        check_true(s.get_resolution() == 3, "get_resolution_3");
        c.setRegister(0xE7, {0x03});
        check_true(s.get_resolution() == 1, "get_resolution_1");
        c.setRegister(0xE7, {0x82});
        check_true(s.get_resolution() == 2, "get_resolution_2");
        c.setRegister(0xE7, {0x02});
        check_true(s.get_resolution() == 0, "get_resolution_default");
        s.set_heater(true);
        check_true(last_write_is(c, 0xE6, 0x06), "heater_on_write");
        c.setRegister(0xE7, {0x06});
        check_true(s.heater(), "heater_on");
        s.set_heater(false);
        check_true(last_write_is(c, 0xE6, 0x02), "heater_off_write");
        c.setRegister(0xE7, {0x02});
        check_true(!s.heater() && !s.battery_low(), "heater_off_battery_ok");
        c.setRegister(0xE7, {0x42});
        check_true(s.battery_low(), "battery_low");
        check_true(s.read_user_register() == 0x42, "read_user_register");

        // soft reset
        c.clearWrites();
        s.soft_reset();
        check_true(eq(cmds(c), {0xFE}), "soft_reset");

        // dew point 25 degC / 50 %RH -> about 13.86 degC
        queue_frame(c, t_code(25.0f)); queue_frame(c, rh_code(50.0f));
        check_close(s.dew_point(), 13.86f, 0.1f, "dew_point");
    }
    {
        I2CConnectionMock c;
        HTU21DFMinimal m(c);
        float t, rh;
        queue_frame(c, t_code(25.0f)); queue_frame(c, rh_code(50.0f));
        check_true(m.read(t, rh), "minimal_class_read");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
