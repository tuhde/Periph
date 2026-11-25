#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "SAA1064.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

int main() {
    I2CConnectionMock connection;
    SAA1064Full chip(connection);

    const auto& w = connection.writes();
    check_true(eq(w[0], {0x00, 0x47, 0, 0, 0, 0}), "init");

    const int8_t d1[4] = {1, 2, 3, 4};
    check_true(chip.show_digits(d1), "show_digits_ok");
    check_true(eq(connection.writes().back(), {0x01, 0x06, 0x5B, 0x4F, 0x66}), "show_digits");
    const int8_t d2[4] = {SAA1064Minimal::BLANK, 0, 9, 8};
    chip.show_digits(d2);
    check_true(eq(connection.writes().back(), {0x01, 0x00, 0x3F, 0x6F, 0x7F}), "show_digits_blank");
    size_t n = connection.writes().size();
    const int8_t bad[4] = {10, 0, 0, 0};
    check_true(!chip.show_digits(bad) && connection.writes().size() == n, "show_digits_range");

    chip.show_number(42);
    check_true(eq(connection.writes().back(), {0x01, 0x00, 0x00, 0x66, 0x5B}), "show_number");
    chip.show_number(0);
    check_true(eq(connection.writes().back(), {0x01, 0x00, 0x00, 0x00, 0x3F}), "show_number_zero");
    chip.show_number(9999);
    check_true(eq(connection.writes().back(), {0x01, 0x6F, 0x6F, 0x6F, 0x6F}), "show_number_max");
    n = connection.writes().size();
    check_true(!chip.show_number(10000) && connection.writes().size() == n, "show_number_range");

    chip.clear();
    check_true(eq(connection.writes().back(), {0x01, 0, 0, 0, 0}), "clear");
    const uint8_t raw[2] = {0xAA, 0x55};
    check_true(chip.write_raw(raw, 2, 2), "write_raw_ok");
    check_true(eq(connection.writes().back(), {0x02, 0xAA, 0x55}), "write_raw");
    n = connection.writes().size();
    check_true(!chip.write_raw(raw, 2, 4) && !chip.write_raw(raw, 0, 1) && !chip.write_raw(raw, 1, 0), "write_raw_rejects");
    check_true(connection.writes().size() == n, "write_raw_rejects_no_write");

    chip.set_mode(false);
    check_true(eq(connection.writes().back(), {0x00, 0x46}), "mode_static");
    chip.set_mode(true);
    check_true(eq(connection.writes().back(), {0x00, 0x47}), "mode_dynamic");
    chip.blank(true, false);
    check_true(eq(connection.writes().back(), {0x00, 0x45}), "blank_13");
    chip.blank(true, true);
    check_true(eq(connection.writes().back(), {0x00, 0x41}), "blank_both");
    chip.blank(false, false);
    check_true(eq(connection.writes().back(), {0x00, 0x47}), "blank_none");
    check_true(chip.set_current(21) && eq(connection.writes().back(), {0x00, 0x77}), "current_21");
    chip.set_current(0);
    check_true(eq(connection.writes().back(), {0x00, 0x07}), "current_0");
    chip.set_current(12);
    check_true(eq(connection.writes().back(), {0x00, 0x47}), "current_12");
    n = connection.writes().size();
    check_true(!chip.set_current(10) && connection.writes().size() == n, "current_invalid");
    chip.segment_test(true);
    check_true(eq(connection.writes().back(), {0x00, 0x4F}), "test_on");
    chip.segment_test(false);
    check_true(eq(connection.writes().back(), {0x00, 0x47}), "test_off");
    chip.write_control(0xFF);
    check_true(eq(connection.writes().back(), {0x00, 0x7F}), "write_control_bit7");
    chip.write_control(0x47);

    chip.show_digit(2, 7, true);
    check_true(eq(connection.writes().back(), {0x02, 0x87}), "show_digit_dp");
    chip.set_decimal_point(2, false);
    check_true(eq(connection.writes().back(), {0x02, 0x07}), "dp_off");
    chip.set_decimal_point(3, true);
    check_true(eq(connection.writes().back(), {0x03, 0xD5}), "dp_on");
    check_true(!chip.show_digit(5, 1) && !chip.set_decimal_point(0, true), "position_range");

    const uint8_t map[8] = {7, 6, 5, 4, 3, 2, 1, 0};
    check_true(chip.set_segment_map(map), "segment_map_ok");
    const int8_t ones[4] = {1, 1, 1, 1};
    chip.show_digits(ones);
    check_true(eq(connection.writes().back(), {0x01, 0x60, 0x60, 0x60, 0x60}), "segment_map");
    const uint8_t badmap[8] = {8, 1, 2, 3, 4, 5, 6, 7};
    check_true(!chip.set_segment_map(badmap), "segment_map_range");

    connection.queueRead({0x80});
    check_true(chip.read_status() == 0x80, "read_status");
    connection.queueRead({0x80});
    check_true(chip.power_reset_flag(), "power_reset_flag_set");
    connection.queueRead({0x00});
    check_true(!chip.power_reset_flag(), "power_reset_flag_clear");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
