#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "AD5243.h"

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
        AD5243Minimal pot(mock);
        check_true(mock.writes().empty(), "init_no_traffic");
    }

    // setWiper(): instruction byte, clamping, invalid channel.
    {
        I2CConnectionMock mock;
        AD5243Minimal pot(mock);
        pot.setWiper(0, 100);
        check_true(lastIs(mock, 0x00, 100), "set_wiper_ch0");
        pot.setWiper(1, 200);
        check_true(lastIs(mock, 0x80, 200), "set_wiper_ch1");
        pot.setWiper(0, 300);
        check_true(lastIs(mock, 0x00, 255), "set_wiper_clamps_high");
        pot.setWiper(0, -5);
        check_true(lastIs(mock, 0x00, 0), "set_wiper_clamps_low");
        size_t n = mock.writes().size();
        check_true(!pot.setWiper(2, 1) && mock.writes().size() == n, "invalid_channel_rejected");
    }

    // getWiper(): instruction byte selects the channel.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x00, {17});
        mock.setRegister(0x80, {99});
        AD5243Minimal pot(mock);
        check_true(pot.getWiper(0) == 17, "get_wiper_ch0");
        check_true(pot.getWiper(1) == 99, "get_wiper_ch1");
    }

    // Resistance conversions (10 kOhm, RW = 160).
    {
        I2CConnectionMock mock;
        AD5243Minimal pot(mock);
        pot.setResistance(0, 5320.0f);
        check_true(lastIs(mock, 0x00, 128), "set_resistance_mid");
        pot.setResistance(0, 0.0f);
        check_true(lastIs(mock, 0x00, 0), "set_resistance_clamps_low");
        pot.setResistance(1, 1e6f);
        check_true(lastIs(mock, 0x80, 255), "set_resistance_clamps_high");
        mock.setRegister(0x00, {128});
        mock.setRegister(0x80, {0});
        check_true(std::fabs(pot.getResistance(0) - 5320.0f) < 0.01f, "get_resistance_mid");
        check_true(std::fabs(pot.getResistance(1) - 320.0f) < 0.01f, "get_resistance_zero");
    }

    // Full: both / ratio / W-A / increment.
    {
        I2CConnectionMock mock;
        AD5243Full pot(mock);
        pot.setBoth(1, 2);
        check_true(mock.writes().size() == 2 && mock.writes()[0][0] == 0x00 && mock.writes()[0][1] == 1 &&
                   mock.writes()[1][0] == 0x80 && mock.writes()[1][1] == 2, "set_both");
        pot.setRatio(0, 0.25f);
        check_true(lastIs(mock, 0x00, 64), "set_ratio_quarter");
        pot.setRatio(0, 1.0f);
        check_true(lastIs(mock, 0x00, 255), "set_ratio_clamps_255");
        pot.setResistanceWa(1, 5320.0f);
        check_true(lastIs(mock, 0x80, 128), "set_resistance_wa_mid");

        mock.setRegister(0x00, {250});
        mock.setRegister(0x80, {128});
        check_true(pot.getRatio(1) == 0.5f, "get_ratio");
        check_true(std::fabs(pot.getResistanceWa(1) - 5320.0f) < 0.01f, "get_resistance_wa");
        check_true(pot.increment(0, 3) == 253 && lastIs(mock, 0x00, 253), "increment_returns");
        check_true(pot.increment(0, 100) == 255, "increment_clamps_high");
        check_true(pot.increment(1, -28) == 100 && lastIs(mock, 0x80, 100), "increment_negative");
    }

    // Shutdown tracking and reset.
    {
        I2CConnectionMock mock;
        AD5243Full pot(mock);
        pot.setWiper(1, 77);
        check_true(!pot.isShutdown(1), "not_shutdown_initially");
        pot.shutdown(1);
        check_true(lastIs(mock, 0xC0, 77), "shutdown_write");
        check_true(pot.isShutdown(1) && !pot.isShutdown(0), "is_shutdown");
        pot.setWiper(1, 50);
        check_true(lastIs(mock, 0xC0, 50), "set_wiper_keeps_sd");
        pot.resume(1);
        check_true(lastIs(mock, 0x80, 50) && !pot.isShutdown(1), "resume_write");
        pot.shutdown(0);
        check_true(lastIs(mock, 0x40, 128), "shutdown_ch0_midscale");
        pot.reset();
        const auto& w = mock.writes();
        check_true(w[w.size() - 2][0] == 0x00 && w[w.size() - 2][1] == 128 &&
                   w.back()[0] == 0x80 && w.back()[1] == 128, "reset_writes");
        check_true(!pot.isShutdown(0), "reset_clears_shutdown");
    }

    // setRab(): conversions only.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x00, {128});
        AD5243Full pot(mock);
        pot.setRab(50000.0f, 100.0f);
        check_true(mock.writes().empty(), "set_rab_no_traffic");
        check_true(std::fabs(pot.getResistance(0) - 25200.0f) < 0.01f, "set_rab_conversion");
        pot.setRab(10000.0f);
        check_true(std::fabs(pot.getResistance(0) - 5200.0f) < 0.01f, "set_rab_keeps_rw");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
