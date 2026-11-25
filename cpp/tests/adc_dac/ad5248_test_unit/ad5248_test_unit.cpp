#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "AD5248.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

using Bytes = std::vector<uint8_t>;

int main() {
    I2CConnectionMock connection;
    AD5248Full chip(connection);
    check_true(connection.writes().empty(), "init_no_traffic");

    // set_wiper(0, 100) -> [0x00, 100]; set_wiper(1, 200) -> [0x80, 200].
    chip.set_wiper(0, 100);
    check_true(connection.writes().back() == Bytes({0x00, 100}), "set_wiper_ch0");
    chip.set_wiper(1, 200);
    check_true(connection.writes().back() == Bytes({0x80, 200}), "set_wiper_ch1");
    chip.set_wiper(0, 999);
    check_true(connection.writes().back() == Bytes({0x00, 255}), "set_wiper_clamps_high");
    chip.set_wiper(0, -5);
    check_true(connection.writes().back() == Bytes({0x00, 0}), "set_wiper_clamps_low");

    // get_wiper sends the instruction byte alone, then reads one byte.
    chip.set_wiper(0, 100);
    chip.set_wiper(1, 200);
    size_t before = connection.writes().size();
    check_true(chip.get_wiper(0) == 100, "get_wiper_ch0");
    check_true(connection.writes().size() == before + 1 && connection.writes().back() == Bytes({0x00}),
               "get_wiper_instruction_alone");
    check_true(chip.get_wiper(1) == 200, "get_wiper_ch1");

    // Resistance model: R = D/256*RAB + 2*RW (RAB = 10k, RW = 160).
    chip.set_wiper(0, 128);
    check_true(fabsf(chip.get_resistance(0) - 5320.0f) < 1e-2f, "get_resistance");
    chip.set_wiper(0, 0);
    check_true(fabsf(chip.get_resistance(0) - 320.0f) < 1e-2f, "get_resistance_zero_scale");
    chip.set_resistance(1, 5320.0f);
    check_true(connection.writes().back() == Bytes({0x80, 128}), "set_resistance");
    chip.set_resistance(1, 1.0f);
    check_true(connection.writes().back() == Bytes({0x80, 0}), "set_resistance_clamps_low");
    chip.set_resistance(1, 1e9f);
    check_true(connection.writes().back() == Bytes({0x80, 255}), "set_resistance_clamps_high");

    // Full: set_both / ratio.
    chip.set_both(10, 20);
    const auto& w = connection.writes();
    check_true(w[w.size() - 2] == Bytes({0x00, 10}) && w[w.size() - 1] == Bytes({0x80, 20}), "set_both");
    chip.set_ratio(0, 0.5f);
    check_true(connection.writes().back() == Bytes({0x00, 128}), "set_ratio");
    chip.set_ratio(0, 1.0f);
    check_true(connection.writes().back() == Bytes({0x00, 255}), "set_ratio_clamps_255");
    chip.set_wiper(1, 64);
    check_true(fabsf(chip.get_ratio(1) - 0.25f) < 1e-6f, "get_ratio");

    // increment reads, adds, clamps, writes.
    chip.set_wiper(0, 250);
    check_true(chip.increment(0, 10) == 255 && connection.writes().back() == Bytes({0x00, 255}), "increment_clamps");
    check_true(chip.increment(0, -5) == 250 && connection.writes().back() == Bytes({0x00, 250}), "decrement");

    // shutdown writes the instruction with SD=1 and the current code; resume clears SD.
    chip.set_wiper(1, 77);
    check_true(!chip.is_shutdown(1), "not_shutdown_initially");
    chip.shutdown(1);
    check_true(connection.writes().back() == Bytes({0xC0, 77}), "shutdown_write");
    check_true(chip.is_shutdown(1) && !chip.is_shutdown(0), "is_shutdown");
    chip.set_wiper(1, 99);
    check_true(connection.writes().back() == Bytes({0xC0, 99}), "set_wiper_keeps_sd");
    chip.resume(1);
    check_true(connection.writes().back() == Bytes({0x80, 99}), "resume_write");
    check_true(!chip.is_shutdown(1), "resume_clears_state");

    // reset writes midscale to both channels with SD=0.
    chip.shutdown(0);
    chip.reset();
    const auto& w2 = connection.writes();
    check_true(w2[w2.size() - 2] == Bytes({0x00, 0x80}) && w2[w2.size() - 1] == Bytes({0x80, 0x80}), "reset_writes");
    check_true(!chip.is_shutdown(0), "reset_clears_shutdown");

    // set_rab changes the model without I2C traffic.
    size_t n = connection.writes().size();
    chip.set_rab(50000.0f, 100.0f);
    check_true(connection.writes().size() == n, "set_rab_no_traffic");
    chip.set_wiper(0, 128);
    check_true(fabsf(chip.get_resistance(0) - 25200.0f) < 1e-1f, "set_rab_resistance");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
