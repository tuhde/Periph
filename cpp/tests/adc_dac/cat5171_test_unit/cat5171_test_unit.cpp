#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "CAT5171.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool lastIs(const I2CConnectionMock& mock, uint8_t instr, uint8_t code) {
    const auto& w = mock.writes();
    return !w.empty() && w.back().size() == 2 && w.back()[0] == instr && w.back()[1] == code;
}

int main() {
    // Construction makes no bus traffic.
    {
        I2CConnectionMock mock;
        CAT5171Minimal pot(mock);
        check_true(mock.writes().empty(), "init_no_traffic");
    }

    // setWiper(): instruction byte and clamping.
    {
        I2CConnectionMock mock;
        CAT5171Minimal pot(mock);
        pot.setWiper(100);
        check_true(lastIs(mock, 0x00, 100), "set_wiper");
        pot.setWiper(300);
        check_true(lastIs(mock, 0x00, 255), "set_wiper_clamps_high");
        pot.setWiper(-5);
        check_true(lastIs(mock, 0x00, 0), "set_wiper_clamps_low");
    }

    // getWiper(): bare one-byte read, no write.
    {
        I2CConnectionMock mock;
        mock.queueRead({17});
        CAT5171Minimal pot(mock);
        check_true(pot.getWiper() == 17, "get_wiper");
        check_true(mock.writes().empty(), "get_wiper_no_write");
    }

    // Resistance conversions (100 kOhm, RW = 50).
    {
        I2CConnectionMock mock;
        CAT5171Minimal pot(mock);
        pot.setResistance(50050.0f);
        check_true(lastIs(mock, 0x00, 128), "set_resistance_mid");
        pot.setResistance(0.0f);
        check_true(lastIs(mock, 0x00, 0), "set_resistance_clamps_low");
        pot.setResistance(1e7f);
        check_true(lastIs(mock, 0x00, 255), "set_resistance_clamps_high");
    }
    {
        I2CConnectionMock mock;
        mock.queueRead({128});
        mock.queueRead({0});
        CAT5171Minimal pot(mock, 100000.0f, 50.0f);
        check_true(fabsf(pot.getResistance() - 50050.0f) < 0.01f, "get_resistance_mid");
        check_true(fabsf(pot.getResistance() - 50.0f) < 0.01f, "get_resistance_zero");
    }

    // Full: ratio, W-A resistance.
    {
        I2CConnectionMock mock;
        CAT5171Full full(mock);
        full.setRatio(0.25f);
        check_true(lastIs(mock, 0x00, 64), "set_ratio_quarter");
        full.setRatio(1.0f);
        check_true(lastIs(mock, 0x00, 255), "set_ratio_clamps_255");
        full.setResistanceWa(50050.0f);
        check_true(lastIs(mock, 0x00, 128), "set_resistance_wa_mid");
    }
    {
        I2CConnectionMock mock;
        mock.queueRead({128});
        mock.queueRead({64});
        CAT5171Full full(mock);
        check_true(full.getRatio() == 0.5f, "get_ratio");
        check_true(fabsf(full.getResistanceWa() - 75050.0f) < 0.01f, "get_resistance_wa");
    }

    // increment().
    {
        I2CConnectionMock mock;
        mock.queueRead({250});
        mock.queueRead({250});
        mock.queueRead({128});
        CAT5171Full full(mock);
        check_true(full.increment(3) == 253 && lastIs(mock, 0x00, 253), "increment_returns");
        check_true(full.increment(100) == 255, "increment_clamps_high");
        check_true(full.increment(-28) == 100 && lastIs(mock, 0x00, 100), "increment_negative");
    }

    // Shutdown tracking: SD set, tracked code rewritten, later writes keep SD.
    {
        I2CConnectionMock mock;
        CAT5171Full full(mock);
        check_true(!full.isShutdown(), "not_shutdown_initially");
        full.shutdown();
        check_true(lastIs(mock, 0x20, 128) && full.isShutdown(), "shutdown_midscale");
        full.resume();
        check_true(lastIs(mock, 0x00, 128) && !full.isShutdown(), "resume_write");
        full.setWiper(77);
        full.shutdown();
        check_true(lastIs(mock, 0x20, 77), "shutdown_write");
        full.setWiper(50);
        check_true(lastIs(mock, 0x20, 50), "set_wiper_keeps_sd");
        full.resume();
        check_true(lastIs(mock, 0x00, 50), "resume_tracked");

        // midscale(): RS bit, SD kept while shut down.
        full.midscale();
        check_true(lastIs(mock, 0x40, 0x80), "midscale_write");
        full.shutdown();
        full.midscale();
        check_true(lastIs(mock, 0x60, 0x80) && full.isShutdown(), "midscale_keeps_sd");

        // reset(): RS bit, SD cleared.
        full.reset();
        check_true(lastIs(mock, 0x40, 0x80), "reset_write");
        check_true(!full.isShutdown(), "reset_clears_shutdown");
    }

    // setRab() changes conversions without traffic.
    {
        I2CConnectionMock mock;
        mock.queueRead({128});
        mock.queueRead({128});
        CAT5171Full full(mock);
        full.setRab(50000.0f, 100.0f);
        check_true(mock.writes().empty(), "set_rab_no_traffic");
        check_true(fabsf(full.getResistance() - 25100.0f) < 0.01f, "set_rab_conversion");
        full.setRab(50000.0f);
        check_true(fabsf(full.getResistance() - 25100.0f) < 0.01f, "set_rab_keeps_rw");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
