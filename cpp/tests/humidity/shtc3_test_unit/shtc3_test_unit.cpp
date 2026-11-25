#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Shtc3.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static uint8_t crc8(const uint8_t* d, int n) {
    uint8_t crc = 0xFF;
    for (int i = 0; i < n; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

// Queue one measurement read (6 bytes), optionally corrupting one CRC (1 = temperature, 2 = humidity).
static void queue_trh(I2CConnectionMock& c, uint16_t t, uint16_t rh, int corrupt = 0) {
    uint8_t a[2] = {(uint8_t)(t >> 8), (uint8_t)(t & 0xFF)};
    uint8_t b[2] = {(uint8_t)(rh >> 8), (uint8_t)(rh & 0xFF)};
    uint8_t ca = crc8(a, 2), cb = crc8(b, 2);
    if (corrupt == 1) ca ^= 0xFF;
    if (corrupt == 2) cb ^= 0xFF;
    c.queueRead({a[0], a[1], ca, b[0], b[1], cb});
}

static void queue_id(I2CConnectionMock& c, uint16_t id, bool bad_crc = false) {
    uint8_t a[2] = {(uint8_t)(id >> 8), (uint8_t)(id & 0xFF)};
    uint8_t crc = crc8(a, 2);
    if (bad_crc) crc ^= 0x55;
    c.queueRead({a[0], a[1], crc});
}

static std::vector<uint16_t> cmds(const I2CConnectionMock& c) {
    std::vector<uint16_t> out;
    for (const auto& w : c.writes()) out.push_back((uint16_t)((w[0] << 8) | w[1]));
    return out;
}

static bool eq(const std::vector<uint16_t>& a, std::initializer_list<uint16_t> b) {
    return a == std::vector<uint16_t>(b);
}

int main() {
    // CRC test vectors from the datasheet
    {
        uint8_t v1[2] = {0xBE, 0xEF}, v2[1] = {0x00};
        check_true(crc8(v1, 2) == 0x92, "crc8_BEEF");
        check_true(crc8(v2, 1) == 0xAC, "crc8_00");
    }
    {
        I2CConnectionMock c;
        SHTC3Full s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        queue_id(c, 0x0807);
        check_true(s.init(), "init_ok");
        check_true(eq(cmds(c), {0x3517, 0x805D, 0xEFC8, 0xB098}), "init_sequence");
    }
    {
        I2CConnectionMock c;
        SHTC3Full s(c);
        queue_id(c, 0x0000);
        check_true(!s.init(), "init_wrong_id");
        I2CConnectionMock c2;
        SHTC3Full s2(c2);
        queue_id(c2, 0x0807, true);
        check_true(!s2.init(), "init_crc_mismatch");
    }
    {
        I2CConnectionMock c;
        SHTC3Full s(c);
        // read: measurement command then sleep (the driver starts idle)
        queue_trh(c, 0x6666, 0x6000);
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(eq(cmds(c), {0x7866, 0xB098}), "read_commands");
        check_close(t, -45.0f + 175.0f * 0x6666 / 65536.0f, 0.001f, "read_temperature");
        check_close(rh, 37.5f, 0.001f, "read_humidity");

        // second read wakes first
        c.clearWrites();
        queue_trh(c, 0x6666, 0x6000);
        s.read(t, rh);
        check_true(eq(cmds(c), {0x3517, 0x7866, 0xB098}), "read_wakes_after_sleep");

        // full scale / zero codes
        queue_trh(c, 0xFFFF, 0xFFFF);
        s.read(t, rh);
        check_close(t, 130.0f, 0.01f, "temperature_max");
        check_true(rh <= 100.0f && rh > 99.9f, "humidity_full_scale");
        queue_trh(c, 0x0000, 0x0000);
        s.read(t, rh);
        check_close(t, -45.0f, 0.001f, "temperature_min");
        check_close(rh, 0.0f, 0.001f, "humidity_zero");

        // CRC mismatch: retried once, then fails
        c.clearWrites();
        queue_trh(c, 0x6666, 0x6000, 1);
        queue_trh(c, 0x6666, 0x6000, 1);
        check_true(!s.read(t, rh), "crc_mismatch_temperature");
        queue_trh(c, 0x6666, 0x6000, 2);
        queue_trh(c, 0x6666, 0x6000, 2);
        check_true(!s.read(t, rh), "crc_mismatch_humidity");
        // single glitch is recovered
        c.clearWrites();
        queue_trh(c, 0x6666, 0x6000, 1);
        queue_trh(c, 0x6666, 0x6000);
        check_true(s.read(t, rh), "crc_glitch_recovered");
        int measures = 0;
        for (uint16_t w : cmds(c)) if (w == 0x7866) measures++;
        check_true(measures == 2, "crc_glitch_retried");
    }
    {
        // Full: single values and raw
        I2CConnectionMock c;
        SHTC3Full s(c);
        queue_trh(c, 0x4000, 0x4000);
        check_close(s.read_humidity(), 25.0f, 0.001f, "read_humidity_single");
        queue_trh(c, 0x4000, 0x4000);
        check_close(s.read_temperature(), -45.0f + 175.0f * 0.25f, 0.001f, "read_temperature_single");
        queue_trh(c, 0x1234, 0x5678);
        uint16_t tr = 0, rr = 0;
        check_true(s.read_raw(tr, rr) && tr == 0x1234 && rr == 0x5678, "read_raw");
    }
    {
        // mode selection
        I2CConnectionMock c;
        SHTC3Full s(c);
        float t, rh;
        s.set_low_power(true);
        queue_trh(c, 0x6666, 0x6000);
        s.read(t, rh);
        check_true(eq(cmds(c), {0x609C, 0xB098}), "low_power_command");
        c.clearWrites();
        s.set_clock_stretching(true);
        queue_trh(c, 0x6666, 0x6000);
        s.read(t, rh);
        check_true(eq(cmds(c), {0x3517, 0x6458, 0xB098}), "low_power_stretching_command");
        c.clearWrites();
        s.set_low_power(false);
        queue_trh(c, 0x6666, 0x6000);
        s.read(t, rh);
        check_true(eq(cmds(c), {0x3517, 0x7CA2, 0xB098}), "stretching_command");
    }
    {
        // sleep control
        I2CConnectionMock c;
        SHTC3Full s(c);
        float t, rh;
        s.set_sleep_between(false);
        queue_trh(c, 0x6666, 0x6000);
        queue_trh(c, 0x6666, 0x6000);
        s.read(t, rh);
        s.read(t, rh);
        check_true(eq(cmds(c), {0x7866, 0x7866}), "no_sleep_between");
        s.sleep();
        check_true(cmds(c).back() == 0xB098, "manual_sleep");
        s.wake();
        check_true(cmds(c).back() == 0x3517, "manual_wake");
    }
    {
        // soft reset and ID
        I2CConnectionMock c;
        SHTC3Full s(c);
        s.soft_reset();
        check_true(eq(cmds(c), {0x805D}), "soft_reset_command");
        c.clearWrites();
        uint16_t id = 0;
        queue_id(c, 0xAB07);
        check_true(s.read_id(id) && id == 0xAB07, "read_id_value");
        check_true(eq(cmds(c), {0xEFC8}), "read_id_command");
        queue_id(c, 0x0807, true);
        check_true(!s.read_id(id), "read_id_crc_mismatch");
        s.sleep();
        c.clearWrites();
        queue_id(c, 0x0807);
        check_true(s.read_id(id), "read_id_asleep");
        check_true(eq(cmds(c), {0x3517, 0xEFC8, 0xB098}), "read_id_wakes_and_sleeps");
    }
    {
        // dew point: Magnus, 25 degC / 50 %RH is about 13.9 degC
        I2CConnectionMock c;
        SHTC3Full s(c);
        uint16_t t_raw = (uint16_t)lroundf((25.0f + 45.0f) * 65536.0f / 175.0f);
        queue_trh(c, t_raw, 0x8000);
        check_close(s.dew_point(), 13.86f, 0.15f, "dew_point");
        queue_trh(c, t_raw, 0x0000);
        check_true(s.dew_point() < -20.0f, "dew_point_dry_finite");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
