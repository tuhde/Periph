#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Bh1750.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

static void queue_word(I2CConnectionMock& c, uint16_t raw) {
    c.queueRead({(uint8_t)(raw >> 8), (uint8_t)(raw & 0xFF)});
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
        I2CConnectionMock c;
        BH1750Full s(c);
        check_true(c.writes().empty(), "ctor_no_bus_access");
        s.init();
        check_true(eq(cmds(c), {0x01}), "init_power_on");
    }
    {   // Minimal read: one-time H mode, raw / 1.2 (datasheet example 0x8342)
        I2CConnectionMock c;
        BH1750Minimal s(c);
        queue_word(c, 0x8342);
        float lux = 0;
        check_true(s.read(lux), "read_ok");
        check_close(lux, 0x8342 / 1.2f, 0.01f, "read_value");
        check_true(eq(cmds(c), {0x20}), "read_command");
    }
    {   // one-time modes
        I2CConnectionMock c;
        BH1750Full s(c);
        float lux = 0;
        queue_word(c, 0x0012);
        check_true(s.measure_once(BH1750Full::MODE_H, lux), "measure_once_h_ok");
        check_close(lux, 15.0f, 1e-3f, "measure_once_h");
        check_true(eq(cmds(c), {0x20}), "measure_once_h_opcode");
        c.clearWrites();
        queue_word(c, 0x0012);
        s.measure_once(BH1750Full::MODE_H2, lux);
        check_close(lux, 7.5f, 1e-3f, "measure_once_h2");
        check_true(eq(cmds(c), {0x21}), "measure_once_h2_opcode");
        c.clearWrites();
        queue_word(c, 0x0012);
        s.measure_once(BH1750Full::MODE_L, lux);
        check_close(lux, 15.0f, 1e-3f, "measure_once_l");
        check_true(eq(cmds(c), {0x23}), "measure_once_l_opcode");
        c.clearWrites();
        check_true(!s.measure_once(7, lux), "measure_once_bad_mode");
        check_true(c.writes().empty(), "measure_once_bad_mode_no_write");
    }
    {   // read / read_raw follow the current mode
        I2CConnectionMock c;
        BH1750Full s(c);
        float lux = 0;
        queue_word(c, 0x0012);
        s.measure_once(BH1750Full::MODE_H2, lux);
        c.clearWrites();
        queue_word(c, 0x0012);
        s.read(lux);
        check_close(lux, 7.5f, 1e-3f, "read_uses_current_mode");
        check_true(eq(cmds(c), {0x21}), "read_current_mode_opcode");
        c.clearWrites();
        uint16_t raw = 0;
        queue_word(c, 0x0012);
        check_true(s.read_raw(raw) && raw == 0x12, "read_raw");
    }
    {   // continuous mode
        I2CConnectionMock c;
        BH1750Full s(c);
        check_true(s.start_continuous(BH1750Full::MODE_H), "continuous_h");
        s.start_continuous(BH1750Full::MODE_H2);
        s.start_continuous(BH1750Full::MODE_L);
        check_true(eq(cmds(c), {0x10, 0x11, 0x13}), "continuous_opcodes");
        s.start_continuous(BH1750Full::MODE_H2);
        c.clearWrites();
        queue_word(c, 0x0012);
        float lux = 0;
        check_true(s.read_continuous(lux), "read_continuous_ok");
        check_close(lux, 7.5f, 1e-3f, "read_continuous");
        check_true(c.writes().empty(), "read_continuous_no_write");
        check_true(!s.start_continuous(9), "continuous_bad_mode");
    }
    {   // power control and reset
        I2CConnectionMock c;
        BH1750Full s(c);
        s.power_down();
        s.power_on();
        check_true(eq(cmds(c), {0x00, 0x01}), "power_opcodes");
        c.clearWrites();
        s.reset();
        check_true(eq(cmds(c), {0x01, 0x07}), "reset_powers_on_first");
    }
    {   // measurement-time register
        I2CConnectionMock c;
        BH1750Full s(c);
        check_true(s.get_measurement_time_register() == 69, "mtreg_default");
        check_true(s.set_measurement_time(69), "mtreg_69_ok");
        check_true(eq(cmds(c), {0x42, 0x65}), "mtreg_69_opcodes");
        c.clearWrites();
        s.set_measurement_time(254);
        check_true(eq(cmds(c), {0x47, 0x7E}), "mtreg_254_opcodes");
        check_true(s.get_measurement_time_register() == 254, "mtreg_cached");
        c.clearWrites();
        s.set_measurement_time(31);
        check_true(eq(cmds(c), {0x40, 0x7F}), "mtreg_31_opcodes");
        c.clearWrites();
        check_true(!s.set_measurement_time(30), "mtreg_low_rejected");
        check_true(!s.set_measurement_time(255), "mtreg_high_rejected");
        check_true(c.writes().empty(), "mtreg_rejected_no_write");
        check_true(s.get_measurement_time_register() == 31, "mtreg_unchanged_after_reject");
    }
    {   // sensitivity, lux per count and measurement time scale with MTreg
        I2CConnectionMock c;
        BH1750Full s(c);
        check_close(s.lux_per_count(), 1.0f / 1.2f, 1e-5f, "lux_per_count_default");
        check_close(s.lux_per_count(BH1750Full::MODE_H2), 0.5f / 1.2f, 1e-5f, "lux_per_count_h2");
        check_close(s.measurement_time(), 0.180f, 1e-5f, "measurement_time_h");
        check_close(s.measurement_time(BH1750Full::MODE_L), 0.024f, 1e-5f, "measurement_time_l");
        check_true(s.set_sensitivity(2.0f), "sensitivity_ok");
        check_true(s.get_measurement_time_register() == 138, "sensitivity_mtreg");
        check_close(s.lux_per_count(), 1.0f / 1.2f / 2.0f, 1e-5f, "sensitivity_lux_per_count");
        check_close(s.measurement_time(), 0.360f, 1e-5f, "sensitivity_time");
        queue_word(c, 0x0012);
        float lux = 0;
        s.measure_once(BH1750Full::MODE_H, lux);
        check_close(lux, 7.5f, 1e-3f, "sensitivity_scales_read");
        check_true(!s.set_sensitivity(0.4f), "sensitivity_low_limit");
        check_true(!s.set_sensitivity(3.7f), "sensitivity_high_limit");
        s.set_sensitivity(0.45f);
        check_true(s.get_measurement_time_register() == 31, "sensitivity_min");
        s.set_sensitivity(3.68f);
        check_true(s.get_measurement_time_register() == 254, "sensitivity_max");
    }
    {   // bus failure
        I2CConnectionMock c;
        BH1750Full s(c);
        c.failNextWrites(1);
        float lux = 0;
        check_true(!s.measure_once(BH1750Full::MODE_H, lux), "bus_error_fails");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
