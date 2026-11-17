#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "HT16K33.h"

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
    HT16K33Full chip(connection);

    const auto& w = connection.writes();
    std::vector<uint8_t> zeros17(17, 0x00);
    check_true(eq(w[0], {0x21}), "init_osc");
    check_true(eq(w[1], {0xA0}), "init_row");
    check_true(eq(w[2], {0xEF}), "init_dim");
    check_true(w[3] == zeros17, "init_ram");
    check_true(eq(w[4], {0x81}), "init_display");

    check_true(chip.set_pixel(1, 9), "set_pixel");
    chip.show();
    std::vector<uint8_t> exp(17, 0x00);
    exp[1 + 3] = 0x02;
    check_true(connection.writes().back() == exp, "set_pixel_show");
    check_true(chip.get_pixel(1, 9) && !chip.get_pixel(1, 8), "get_pixel");
    chip.set_pixel(7, 15);
    chip.set_pixel(1, 9, false);
    check_true(!chip.get_pixel(1, 9) && chip.get_pixel(7, 15), "clear_pixel");
    check_true(!chip.set_pixel(8, 0) && !chip.set_pixel(0, 16), "range");

    chip.clear();
    check_true(connection.writes().back() == zeros17, "clear");

    uint8_t raw[2] = {0xAB, 0xCD};
    check_true(chip.write_raw(raw, 2, 4), "write_raw_ok");
    check_true(eq(connection.writes().back(), {0x04, 0xAB, 0xCD}), "write_raw");
    size_t n = connection.writes().size();
    check_true(!chip.write_raw(raw, 2, 15) && !chip.write_raw(raw, 0, 0), "write_raw_rejects");
    check_true(connection.writes().size() == n, "write_raw_rejects_no_write");

    chip.set_brightness(7);
    check_true(eq(connection.writes().back(), {0xE7}), "brightness");
    n = connection.writes().size();
    chip.set_brightness(16);
    check_true(connection.writes().size() == n, "brightness_range");

    chip.set_blink(1.0f);
    check_true(eq(connection.writes().back(), {0x85}), "blink_1hz");
    chip.set_blink(0.5f);
    check_true(eq(connection.writes().back(), {0x87}), "blink_0_5hz");
    chip.set_blink(2.0f);
    check_true(eq(connection.writes().back(), {0x83}), "blink_2hz");
    check_true(!chip.set_blink(3.0f), "blink_invalid");
    chip.display_off();
    check_true(eq(connection.writes().back(), {0x82}), "display_off_keeps_blink");
    chip.display_on();
    check_true(eq(connection.writes().back(), {0x83}), "display_on");
    chip.set_blink(0.0f);

    chip.set_int_output(true);
    check_true(eq(connection.writes().back(), {0xA1}), "int_low");
    chip.set_int_output(true, true);
    check_true(eq(connection.writes().back(), {0xA3}), "int_high");

    chip.standby();
    check_true(eq(connection.writes().back(), {0x20}), "standby");
    n = connection.writes().size();
    chip.wake();
    const auto& w2 = connection.writes();
    check_true(eq(w2[n], {0x21}) && eq(w2[n + 1], {0xA3}) && eq(w2[n + 2], {0xE7}) &&
               w2[n + 3].size() == 17 && eq(w2[n + 4], {0x81}), "wake_sequence");

    connection.queueRead({0x01, 0x00, 0x00, 0x10, 0x00, 0x00});
    uint64_t keys = chip.read_keys();
    check_true(eq(connection.writes().back(), {0x40}), "read_keys_ptr");
    check_true(keys == (1ull | (1ull << 25)), "read_keys_mask");
    connection.queueRead({0x01});
    check_true(chip.key_pressed() && eq(connection.writes().back(), {0x60}), "key_pressed");
    connection.queueRead({0x00});
    check_true(chip.poll_interrupt() == 0, "poll_none");
    connection.queueRead({0x01});
    connection.queueRead({0, 0, 0, 0, 0x01, 0});
    check_true(chip.poll_interrupt() == (1ull << 26), "poll_keys");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
