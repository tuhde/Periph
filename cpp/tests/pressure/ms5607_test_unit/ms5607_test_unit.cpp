#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Ms5607.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

// Datasheet example coefficients C1..C6
static const uint16_t COEFFS[6] = {46372, 43981, 29059, 27842, 31553, 28165};
static const uint32_t D1_EX = 6465444, D2_EX = 8077636;

static uint8_t crc4(const uint16_t w[8]) {
    uint16_t p[8];
    for (int i = 0; i < 8; i++) p[i] = w[i];
    p[7] &= 0xFF00;
    uint16_t n = 0;
    for (int cnt = 0; cnt < 16; cnt++) {
        n ^= (cnt & 1) ? (uint16_t)(p[cnt >> 1] & 0x00FF) : (uint16_t)(p[cnt >> 1] >> 8);
        for (int b = 0; b < 8; b++) n = (n & 0x8000) ? (uint16_t)((n << 1) ^ 0x3000) : (uint16_t)(n << 1);
    }
    return (uint8_t)((n >> 12) & 0x0F);
}

static void make_prom(uint16_t w[8], uint16_t serial = 0x5A3) {
    w[0] = 0x3132;
    for (int i = 0; i < 6; i++) w[i + 1] = COEFFS[i];
    w[7] = (uint16_t)(serial << 4);
    w[7] |= crc4(w);
}

// Queue the eight PROM word reads of init()/read_prom().
static void queue_prom(I2CConnectionMock& c, bool corrupt = false) {
    uint16_t w[8];
    make_prom(w);
    if (corrupt) w[3] ^= 0x0100;
    for (int i = 0; i < 8; i++) c.queueRead({(uint8_t)(w[i] >> 8), (uint8_t)(w[i] & 0xFF)});
}

static void queue_adc(I2CConnectionMock& c, uint32_t v) {
    c.queueRead({(uint8_t)(v >> 16), (uint8_t)((v >> 8) & 0xFF), (uint8_t)(v & 0xFF)});
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
    {
        uint16_t z[8] = {0};
        check_true(crc4(z) == 0, "crc4_zero");
        uint16_t w[8];
        make_prom(w);
        check_true(crc4(w) == (w[7] & 0xF), "crc4_roundtrip");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        queue_prom(c);
        check_true(s.init(), "init_ok");
        check_true(eq(cmds(c), {0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE}), "init_sequence");
        uint16_t k[6];
        s.coefficients(k);
        bool same = true;
        for (int i = 0; i < 6; i++) same = same && k[i] == COEFFS[i];
        check_true(same, "init_coefficients");
        check_true(s.prom_crc_ok(), "init_crc_ok");
        check_true(s.serial_code() == 0x5A3, "serial_code");

        float t = 0, p = 0;
        s.compensate(D1_EX, D2_EX, t, p);
        check_close(t, 20.0f, 0.001f, "example_temperature");
        check_close(p, 110002.0f, 0.5f, "example_pressure");
        s.compensate(D1_EX, D2_EX - 300000, t, p);
        check_close(t, 9.52f, 0.001f, "cold_temperature");
        check_close(p, 107505.0f, 0.5f, "cold_pressure");
        s.compensate(D1_EX, D2_EX - 300000, t, p, false);
        check_close(t, 9.93f, 0.001f, "cold_first_order_temperature");
        check_close(p, 107577.0f, 0.5f, "cold_first_order_pressure");
        s.compensate(D1_EX, D2_EX - 1200000, t, p);
        check_close(t, -26.98f, 0.001f, "very_cold_temperature");
        check_close(p, 99055.0f, 0.5f, "very_cold_pressure");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        queue_prom(c, true);
        check_true(!s.init(), "init_bad_crc");
        I2CConnectionMock c2;
        MS5607Full s2(c2);
        c2.failNextWrites(1);
        check_true(!s2.init(), "bus_error_propagates");
    }
    {
        I2CConnectionMock c;
        MS5607Minimal m(c);
        queue_prom(c);
        m.init();
        c.clearWrites();
        queue_adc(c, D2_EX);
        queue_adc(c, D1_EX);
        float t = 0, p = 0;
        check_true(m.read(t, p), "read_ok");
        check_true(eq(cmds(c), {0x58, 0x00, 0x48, 0x00}), "read_commands");
        check_close(t, 20.0f, 0.001f, "read_temperature");
        check_close(p, 110002.0f, 0.5f, "read_pressure");
        queue_adc(c, 0);
        check_true(!m.read(t, p), "adc_zero_fails");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        queue_prom(c);
        s.init();
        queue_adc(c, D2_EX);
        queue_adc(c, D1_EX);
        check_close(s.read_pressure(), 110002.0f, 0.5f, "read_pressure_single");
        c.clearWrites();
        queue_adc(c, D2_EX);
        check_close(s.read_temperature(), 20.0f, 0.001f, "read_temperature_single");
        check_true(eq(cmds(c), {0x58, 0x00}), "read_temperature_d2_only");
        queue_adc(c, D2_EX);
        queue_adc(c, D1_EX);
        uint32_t d1 = 0, d2 = 0;
        check_true(s.read_raw(d1, d2) && d1 == D1_EX && d2 == D2_EX, "read_raw");
        // failure gives NAN
        check_true(isnan(s.read_pressure()), "read_pressure_nan_on_failure");
    }
    {
        const uint16_t osrs[5] = {256, 512, 1024, 2048, 4096};
        for (int i = 0; i < 5; i++) {
            I2CConnectionMock c;
            MS5607Full s(c);
            queue_prom(c);
            s.init();
            check_true(s.set_oversampling(osrs[i]) && s.get_oversampling() == osrs[i], "osr_roundtrip");
            c.clearWrites();
            queue_adc(c, D2_EX);
            queue_adc(c, D1_EX);
            float t, p;
            s.read(t, p);
            check_true(cmds(c)[0] == (0x50 | (i << 1)) && cmds(c)[2] == (0x40 | (i << 1)), "osr_commands");
        }
        I2CConnectionMock c;
        MS5607Full s(c);
        check_true(!s.set_oversampling(300), "osr_invalid");
        check_true(s.get_oversampling() == 4096, "default_osr");
        check_close(s.conversion_time(256), 0.0006f, 1e-7f, "conversion_time_256");
        check_close(s.conversion_time(512), 0.00117f, 1e-7f, "conversion_time_512");
        check_close(s.conversion_time(1024), 0.00228f, 1e-7f, "conversion_time_1024");
        check_close(s.conversion_time(2048), 0.00454f, 1e-7f, "conversion_time_2048");
        check_close(s.conversion_time(), 0.00904f, 1e-7f, "conversion_time_current");
        check_true(s.conversion_time(100) == 0.0f, "conversion_time_invalid");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        queue_prom(c);
        s.init();
        c.clearWrites();
        check_true(s.start_conversion(MS5607Full::TEMPERATURE), "start_conversion_temperature");
        check_true(eq(cmds(c), {0x58}), "start_conversion_temperature_cmd");
        s.start_conversion(MS5607Full::PRESSURE, 512);
        check_true(cmds(c).back() == 0x42, "start_conversion_pressure_osr");
        check_true(!s.start_conversion(0, 99), "start_conversion_invalid");
        queue_adc(c, D1_EX);
        uint32_t v = 0;
        check_true(s.read_adc(v) && v == D1_EX, "read_adc_value");
        queue_adc(c, 0);
        check_true(!s.read_adc(v), "read_adc_zero_is_error");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        queue_prom(c);
        s.init();
        c.clearWrites();
        queue_prom(c);
        check_true(s.reset(), "reset_ok");
        check_true(cmds(c)[0] == 0x1E && cmds(c).size() == 9, "reset_commands");
        queue_prom(c);
        uint16_t w[8], ref[8];
        make_prom(ref);
        check_true(s.read_prom(w), "read_prom_ok");
        bool same = true;
        for (int i = 0; i < 8; i++) same = same && w[i] == ref[i];
        check_true(same, "read_prom_words");
    }
    {
        I2CConnectionMock c;
        MS5607Full s(c);
        queue_prom(c);
        s.init();
        queue_adc(c, D2_EX);
        queue_adc(c, D1_EX);
        float expected = 44330.0f * (1.0f - powf(110002.0f / 101325.0f, 1.0f / 5.255f));
        float h = s.altitude();
        check_close(h, expected, 0.01f, "altitude_formula");
        check_true(h < 0.0f, "altitude_below_sea_level");
        queue_adc(c, D2_EX);
        queue_adc(c, D1_EX);
        check_close(s.altitude(110002.0f), 0.0f, 0.01f, "altitude_reference");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
