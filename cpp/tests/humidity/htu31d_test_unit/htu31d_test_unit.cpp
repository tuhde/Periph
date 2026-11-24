#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Htu31d.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static uint8_t crc8(const uint8_t* d, int n) {
    uint8_t crc = 0x00;
    for (int i = 0; i < n; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

// Queue one T&RH read (6 bytes), optionally corrupting one CRC (1 = temperature, 2 = humidity).
static void queue_trh(I2CConnectionMock& c, uint16_t t, uint16_t rh, int corrupt = 0) {
    uint8_t a[2] = {(uint8_t)(t >> 8), (uint8_t)(t & 0xFF)};
    uint8_t b[2] = {(uint8_t)(rh >> 8), (uint8_t)(rh & 0xFF)};
    uint8_t ca = crc8(a, 2), cb = crc8(b, 2);
    if (corrupt == 1) ca ^= 0xFF;
    if (corrupt == 2) cb ^= 0xFF;
    c.queueRead({a[0], a[1], ca, b[0], b[1], cb});
}

static void queue_rh(I2CConnectionMock& c, uint16_t rh) {
    uint8_t b[2] = {(uint8_t)(rh >> 8), (uint8_t)(rh & 0xFF)};
    c.queueRead({b[0], b[1], crc8(b, 2)});
}

static std::vector<uint8_t> cmds(const I2CConnectionMock& c) {
    std::vector<uint8_t> out;
    for (const auto& w : c.writes()) out.push_back(w[0]);
    return out;
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

int main() {
    // CRC test vectors from the datasheet
    {
        uint8_t v1[1] = {0xDC}, v2[2] = {0x68, 0x3A}, v3[2] = {0x4E, 0x85};
        check_true(crc8(v1, 1) == 0x79, "crc8_DC");
        check_true(crc8(v2, 2) == 0x7C, "crc8_683A");
        check_true(crc8(v3, 2) == 0x6B, "crc8_4E85");
    }
    {
        I2CConnectionMock c;
        HTU31DFull s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        s.init();
        check_true(eq(cmds(c), {0x1E}), "init_reset");

        // read: 0x5E then 0x00, conversion formulas
        c.clearWrites();
        queue_trh(c, 0x683A, 0x8000);
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(eq(cmds(c), {0x5E, 0x00}), "read_commands");
        check_close(t, -40.0f + 165.0f * 0x683A / 65535.0f, 0.001f, "read_temperature");
        check_close(rh, 100.0f * 0x8000 / 65535.0f, 0.001f, "read_humidity");

        // full scale / zero codes
        queue_trh(c, 0xFFFF, 0xFFFF);
        s.read(t, rh);
        check_close(t, 125.0f, 0.001f, "temperature_max");
        check_close(rh, 100.0f, 0.001f, "humidity_full_scale");
        queue_trh(c, 0x0000, 0x0000);
        s.read(t, rh);
        check_close(t, -40.0f, 0.001f, "temperature_min");
        check_close(rh, 0.0f, 0.001f, "humidity_zero");

        // CRC mismatch on temperature / humidity word
        queue_trh(c, 0x683A, 0x8000, 1);
        check_true(!s.read(t, rh), "crc_mismatch_temperature");
        queue_trh(c, 0x683A, 0x8000, 2);
        check_true(!s.read(t, rh), "crc_mismatch_humidity");

        // bus failure on the conversion write
        c.failNextWrites(1);
        check_true(!s.read(t, rh), "write_failure");
    }
    {
        I2CConnectionMock c;
        HTU31DFull s(c);

        // single values
        queue_rh(c, 0x4000);
        check_close(s.read_humidity(), 100.0f * 0x4000 / 65535.0f, 0.001f, "read_humidity_single");
        check_true(eq(cmds(c), {0x5E, 0x10}), "read_humidity_commands");
        c.clearWrites();
        queue_trh(c, 0x4000, 0x0000);
        check_close(s.read_temperature(), -40.0f + 165.0f * 0x4000 / 65535.0f, 0.001f, "read_temperature_single");
        check_true(eq(cmds(c), {0x5E, 0x00}), "read_temperature_commands");
        c.clearWrites();
        queue_trh(c, 0x1234, 0x5678);
        uint16_t tr = 0, rr = 0;
        check_true(s.read_raw(tr, rr) && tr == 0x1234 && rr == 0x5678, "read_raw");

        // resolution
        uint8_t orh = 0, ot = 0;
        s.get_resolution(orh, ot);
        check_true(orh == 3 && ot == 3, "default_resolution");
        check_close(s.conversion_time(), 0.02032f, 0.00001f, "conversion_time_osr33");
        c.clearWrites();
        check_true(s.set_resolution(0, 0), "set_resolution_00");
        queue_trh(c, 0x4000, 0x4000);
        float t, rh;
        s.read(t, rh);
        check_true(cmds(c)[0] == 0x40, "convert_osr00");
        check_close(s.conversion_time(), 0.00268f, 0.00001f, "conversion_time_osr00");
        c.clearWrites();
        s.set_resolution(2, 1);
        queue_trh(c, 0x4000, 0x4000);
        s.read(t, rh);
        check_true(cmds(c)[0] == (0x40 | (2 << 3) | (1 << 1)), "convert_osr21");
        c.clearWrites();
        s.set_resolution(1, 2);
        s.start_conversion();
        check_true(eq(cmds(c), {(uint8_t)(0x40 | (1 << 3) | (2 << 1))}), "start_conversion_only");
        s.get_resolution(orh, ot);
        check_true(orh == 1 && ot == 2, "get_resolution");
        check_true(!s.set_resolution(4, 0), "resolution_rejects_high_rh");
        check_true(!s.set_resolution(0, 4), "resolution_rejects_high_t");

        // read_result: 0x00 only
        c.clearWrites();
        queue_trh(c, 0x2000, 0x6000);
        check_true(s.read_result(t, rh), "read_result_ok");
        check_true(eq(cmds(c), {0x00}), "read_result_command");
        check_close(rh, 100.0f * 0x6000 / 65535.0f, 0.001f, "read_result_humidity");

        // heater
        c.clearWrites();
        s.set_heater(true);
        s.set_heater(false);
        check_true(eq(cmds(c), {0x04, 0x02}), "heater_commands");
        c.queueRead({0x01});
        check_true(s.is_heater_on(), "is_heater_on_true");
        c.queueRead({0x00});
        check_true(!s.is_heater_on(), "is_heater_on_false");

        // diagnostic
        c.clearWrites();
        c.queueRead({0xA5});
        uint8_t d = s.diagnostic();
        check_true(d == 0xA5 && eq(cmds(c), {0x08}), "diagnostic_byte");
        check_true((d & HTU31DFull::DIAG_NVM_ERR) && (d & HTU31DFull::DIAG_RH_HIGH_ERR) && !(d & HTU31DFull::DIAG_RH_OVERRUN), "diagnostic_flags");

        // serial number
        c.clearWrites();
        uint8_t sn[3] = {0xAB, 0xCD, 0xEF};
        c.queueRead({sn[0], sn[1], sn[2], crc8(sn, 3)});
        uint32_t serial = 0;
        check_true(s.serial_number(serial) && serial == 0xABCDEF && eq(cmds(c), {0x0A}), "serial_number");
        c.queueRead({sn[0], sn[1], sn[2], (uint8_t)(crc8(sn, 3) ^ 0x55)});
        check_true(!s.serial_number(serial), "serial_crc_mismatch");

        // software reset restores OSR 3 / 3
        c.clearWrites();
        s.set_resolution(0, 1);
        s.software_reset();
        s.get_resolution(orh, ot);
        check_true(eq(cmds(c), {0x1E}) && orh == 3 && ot == 3, "software_reset_defaults");

        // dew point: 25 degC / 50 %RH -> about 13.9 degC
        uint16_t tc = (uint16_t)lroundf((25.0f + 40.0f) * 65535.0f / 165.0f);
        uint16_t rc = (uint16_t)lroundf(0.5f * 65535.0f);
        queue_trh(c, tc, rc);
        check_close(s.dew_point(), 13.86f, 0.15f, "dew_point");
    }
    {
        I2CConnectionMock c;
        HTU31DMinimal m(c);
        float t, rh;
        queue_trh(c, 0x683A, 0x8000);
        check_true(m.read(t, rh), "minimal_class_read");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
