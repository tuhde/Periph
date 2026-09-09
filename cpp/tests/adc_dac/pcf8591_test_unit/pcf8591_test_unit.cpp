#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "PCF8591.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionMock connection;
    PCF8591Full adc(connection);
    check_true(true, "init");

    // read_channel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh.
    connection.queueRead({0x11, 0x7F});
    check_true(adc.read_channel(2) == 0x7F, "read_channel");
    check_true(connection.writes().back() == std::vector<uint8_t>({0x02}), "read_channel_writes_control");

    // read_channel clamps an out-of-range channel to 0.
    connection.queueRead({0x00, 0x55});
    check_true(adc.read_channel(9) == 0x55, "read_channel_invalid_clamps_to_0");
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00}), "read_channel_invalid_writes_ctrl_0");

    // read_all(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte.
    connection.queueRead({0x00, 0x10, 0x20, 0x30, 0x40});
    uint8_t all[4];
    adc.read_all(all);
    check_true(all[0] == 0x10 && all[1] == 0x20 && all[2] == 0x30 && all[3] == 0x40, "read_all");
    check_true(connection.writes().back() == std::vector<uint8_t>({0x04}), "read_all_writes_ctrl");

    // configure(input_mode=3, auto_increment=true, dac_enabled=true) -> 0x74.
    adc.configure(3, true, true);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x74}), "configure");

    // read_channel_voltage(0, vref=3.3, vagnd=0.0): raw=128.
    connection.queueRead({0x00, 128});
    float v = adc.read_channel_voltage(0, 3.3f, 0.0f);
    check_true(fabsf(v - (128 * 3.3f / 256.0f)) < 1e-4f, "read_channel_voltage");

    // read_all_voltage(vref=3.3, vagnd=0.0): raws [0, 64, 128, 255].
    connection.queueRead({0x00, 0, 64, 128, 255});
    float voltages[4];
    adc.read_all_voltage(voltages, 3.3f, 0.0f);
    int raws[4] = {0, 64, 128, 255};
    bool voltage_ok = true;
    for (int i = 0; i < 4; i++) {
        float expected = raws[i] * 3.3f / 256.0f;
        if (fabsf(voltages[i] - expected) >= 1e-4f) voltage_ok = false;
    }
    check_true(voltage_ok, "read_all_voltage");

    // read_differential(1): raw byte 200 -> signed two's complement = -56.
    connection.queueRead({0x00, 200});
    check_true(adc.read_differential(1) == -56, "read_differential_negative");

    // raw byte 100 (< 128) stays positive.
    connection.queueRead({0x00, 100});
    check_true(adc.read_differential(1) == 100, "read_differential_positive");

    // set_dac(200): sets AOE=1, AI=0, writes [ctrl, value].
    adc.set_dac(200);
    auto last = connection.writes().back();
    check_true(last[1] == 200, "set_dac_value");
    check_true((last[0] & 0x40) != 0, "set_dac_sets_aoe");
    check_true((last[0] & 0x04) == 0, "set_dac_clears_ai");

    // set_dac_voltage(0.5) -> value = round(0.5*255) = 128.
    adc.set_dac_voltage(0.5f);
    check_true(connection.writes().back()[1] == 128, "set_dac_voltage");

    // disable_dac(): clears AOE bit.
    adc.disable_dac();
    check_true((connection.writes().back()[0] & 0x40) == 0, "disable_dac");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
