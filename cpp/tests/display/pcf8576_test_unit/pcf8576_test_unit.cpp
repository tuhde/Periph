#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "PCF8576.h"

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
    PCF8576Full sensor(connection);

    // init: mode-set (E=1, bias=1/3, mode=1:4 -> 0x40|0x08|0x00|0x00 = 0x48),
    // then load-ptr(0) + 20 zero bytes to blank all RAM.
    check_true(eq(connection.writes()[0], {0x48}), "init_mode_write");
    std::vector<uint8_t> zeros21 = {0x00};
    for (int i = 0; i < 20; i++) zeros21.push_back(0x00);
    check_true(connection.writes()[1] == zeros21, "init_clear_write");

    // clear()
    sensor.clear();
    const auto& writes = connection.writes();
    check_true(eq(writes[writes.size() - 2], {0x48}), "clear_mode_write");
    check_true(writes[writes.size() - 1] == zeros21, "clear_data_write");

    // write_raw()
    uint8_t data1[] = {0xAB, 0xCD};
    sensor.write_raw(5, data1, 2);
    check_true(eq(connection.writes().back(), {0x05, 0xAB, 0xCD}), "write_raw");

    size_t nBefore = connection.writes().size();
    sensor.write_raw(3, nullptr, 0);
    check_true(connection.writes().size() == nBefore, "write_raw_empty_is_noop");

    // set_digit_7seg(): digit '7' -> 0xE0, at RAM address 3*2=6.
    sensor.set_digit_7seg(3, PCF8576Full::SEVEN_SEG[7]);
    check_true(eq(connection.writes().back(), {0x06, 0xE0}), "set_digit_7seg");

    // Full: enable/disable
    sensor.disable();
    check_true(eq(connection.writes().back(), {0x40}), "disable_writes_mode");
    sensor.enable();
    check_true(eq(connection.writes().back(), {0x48}), "enable_writes_mode");

    // set_mode(): mode-set byte = 0x40 | E(0x08) | bias | mode.
    sensor.set_mode(PCF8576Full::BACKPLANES_1, PCF8576Full::BIAS_1_2);
    check_true(eq(connection.writes().back(), {0x4D}), "set_mode_static_bias_1_2");  // 0x40|8|4|1

    sensor.set_mode(PCF8576Full::BACKPLANES_2, PCF8576Full::BIAS_1_3);
    check_true(eq(connection.writes().back(), {0x4A}), "set_mode_1_2");  // 0x40|8|0|2

    sensor.set_mode(PCF8576Full::BACKPLANES_3, PCF8576Full::BIAS_1_3);
    check_true(eq(connection.writes().back(), {0x4B}), "set_mode_1_3");  // 0x40|8|0|3

    sensor.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);
    check_true(eq(connection.writes().back(), {0x48}), "set_mode_1_4");  // 0x40|8|0|0

    // set_blink()
    sensor.set_blink(PCF8576Full::BLINK_1_HZ);
    check_true(eq(connection.writes().back(), {0x72}), "set_blink");  // 0x70|0|2

    sensor.set_blink(PCF8576Full::BLINK_2_HZ, true);
    check_true(eq(connection.writes().back(), {0x75}), "set_blink_alternate_bank");  // 0x70|4|1

    // set_bank()
    sensor.set_bank(1, 0);
    check_true(eq(connection.writes().back(), {0x7A}), "set_bank");  // 0x78|(1<<1)|0

    // device_select()
    sensor.device_select(5);
    check_true(eq(connection.writes().back(), {0x65}), "device_select");  // 0x60|5

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
