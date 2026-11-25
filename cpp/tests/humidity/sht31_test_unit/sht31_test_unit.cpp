#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "SHT31.h"

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
    uint8_t crc = 0xFF;
    for (int i = 0; i < 2; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

static void queue_frame(I2CConnectionMock& c, uint16_t raw_t, uint16_t raw_rh, bool corrupt = false) {
    uint8_t tm = raw_t >> 8, tl = raw_t & 0xFF, hm = raw_rh >> 8, hl = raw_rh & 0xFF;
    uint8_t ct = crc8(tm, tl), ch = crc8(hm, hl);
    if (corrupt) ct ^= 0xFF;
    c.queueRead({tm, tl, ct, hm, hl, ch});
}

// Command codes (2-byte writes) in order.
static std::vector<uint16_t> cmds(const I2CConnectionMock& c) {
    std::vector<uint16_t> out;
    for (const auto& w : c.writes()) if (w.size() == 2) out.push_back((uint16_t)((w[0] << 8) | w[1]));
    return out;
}

static bool eq(const std::vector<uint16_t>& a, std::initializer_list<uint16_t> b) {
    return a == std::vector<uint16_t>(b);
}

int main() {
    {
        I2CConnectionMock c;
        SHT31Full s(c);
        check_true(eq(cmds(c), {0x30A2}), "init_soft_reset");

        c.clearWrites();
        queue_frame(c, 0x6666, 0x8000);
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(eq(cmds(c), {0x2400}), "read_command");
        check_close(t, -45.0f + 175.0f * 0x6666 / 65535.0f, 1e-3f, "read_temperature");
        check_close(rh, 100.0f * 0x8000 / 65535.0f, 1e-3f, "read_humidity");

        // NACKed read header: polls until ready
        c.failNextReads(3);
        queue_frame(c, 0, 65535);
        check_true(s.read(t, rh), "read_after_nacks");
        check_close(t, -45.0f, 1e-3f, "read_after_nacks_t");
        check_close(rh, 100.0f, 1e-3f, "read_after_nacks_rh");

        // CRC mismatch: one retry, then false
        c.clearWrites();
        queue_frame(c, 0x1234, 0x5678, true);
        queue_frame(c, 0x1234, 0x5678);
        check_true(s.read(t, rh), "crc_retry_ok");
        check_true(eq(cmds(c), {0x2400, 0x2400}), "crc_retry_resends_command");
        queue_frame(c, 0x1234, 0x5678, true);
        queue_frame(c, 0x1234, 0x5678, true);
        check_true(!s.read(t, rh), "crc_persistent_fails");

        // raw / single values
        queue_frame(c, 1000, 2000);
        uint16_t rt = 0, rr = 0;
        check_true(s.read_raw(rt, rr) && rt == 1000 && rr == 2000, "read_raw");
        queue_frame(c, 0, 0);
        check_close(s.read_temperature(), -45.0f, 1e-3f, "read_temperature_only");
        queue_frame(c, 0, 65535);
        check_close(s.read_humidity(), 100.0f, 1e-3f, "read_humidity_only");

        // repeatability and clock stretching
        c.clearWrites();
        check_true(s.set_repeatability(SHT31Full::REPEATABILITY_LOW), "set_repeatability_low");
        queue_frame(c, 0, 0); s.read(t, rh);
        s.set_repeatability(SHT31Full::REPEATABILITY_MEDIUM);
        queue_frame(c, 0, 0); s.read(t, rh);
        s.set_clock_stretching(true);
        queue_frame(c, 0, 0); s.read(t, rh);
        s.set_repeatability(SHT31Full::REPEATABILITY_HIGH);
        queue_frame(c, 0, 0); s.read(t, rh);
        check_true(eq(cmds(c), {0x2416, 0x240B, 0x2C0D, 0x2C06}), "repeatability_commands");
        check_true(!s.set_repeatability(3), "repeatability_invalid");
        s.set_clock_stretching(false);

        // periodic
        c.clearWrites();
        check_true(s.start_periodic(2.0f), "periodic_2");
        check_true(s.start_periodic(0.5f, SHT31Full::REPEATABILITY_LOW), "periodic_half_low");
        check_true(s.start_periodic(10.0f, SHT31Full::REPEATABILITY_MEDIUM), "periodic_10_medium");
        check_true(s.start_periodic(1.0f, SHT31Full::REPEATABILITY_HIGH, true), "periodic_art");
        check_true(eq(cmds(c), {0x2236, 0x202F, 0x2721, 0x2B32}), "periodic_commands");
        check_true(!s.start_periodic(3.0f), "periodic_bad_rate");
        c.clearWrites();
        queue_frame(c, 0x4000, 0x4000);
        check_true(s.fetch(t, rh), "fetch_ok");
        check_true(eq(cmds(c), {0xE000}), "fetch_command");
        check_close(t, -45.0f + 175.0f * 0x4000 / 65535.0f, 1e-3f, "fetch_value");
        c.failNextReads(1);
        check_true(!s.fetch(t, rh), "fetch_false_when_nacked");
        c.clearWrites();
        s.stop_periodic();
        check_true(eq(cmds(c), {0x3093}), "stop_periodic");

        // heater, status, reset
        c.clearWrites();
        s.set_heater(true);
        s.set_heater(false);
        s.clear_status();
        check_true(eq(cmds(c), {0x306D, 0x3066, 0x3041}), "heater_and_clear_commands");
        c.queueRead({0x80, 0x10, crc8(0x80, 0x10)});
        uint16_t st = 0;
        check_true(s.read_status(st) && st == 0x8010 && cmds(c).back() == 0xF32D, "read_status");
        c.queueRead({0x80, 0x10, 0x00});
        check_true(!s.read_status(st), "read_status_crc");
        c.clearWrites();
        s.soft_reset();
        check_true(eq(cmds(c), {0x30A2}), "soft_reset");

        // dew point 25 degC / 50 %RH -> 13.86 degC
        queue_frame(c, (uint16_t)lroundf((25.0f + 45.0f) * 65535.0f / 175.0f), (uint16_t)lroundf(50.0f * 65535.0f / 100.0f));
        check_close(s.dew_point(), 13.86f, 0.05f, "dew_point");

        // nothing queued and the chip silent: read fails
        c.failNextReads(100);
        check_true(!s.read(t, rh), "read_fails_when_chip_silent");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
