#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include <initializer_list>
#include "I2CConnectionMock.h"
#include "AD5252.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool lastIs(const I2CConnectionMock& mock, uint8_t instr, uint8_t code) {
    const auto& w = mock.writes();
    return !w.empty() && w.back().size() == 2 && w.back()[0] == instr && w.back()[1] == code;
}

static bool writesAre(const I2CConnectionMock& mock, std::initializer_list<uint8_t> bytes) {
    const auto& w = mock.writes();
    if (w.size() != bytes.size()) return false;
    size_t i = 0;
    for (uint8_t b : bytes) {
        if (w[i].size() != 1 || w[i][0] != b) return false;
        i++;
    }
    return true;
}

int main() {
    // Construction makes no bus traffic.
    {
        I2CConnectionMock mock;
        AD5252Minimal pot(mock);
        check_true(mock.writes().empty(), "init_no_traffic");
    }

    // setWiper(): instruction byte, clamping, invalid channel.
    {
        I2CConnectionMock mock;
        AD5252Minimal pot(mock);
        pot.setWiper(0, 100);
        check_true(lastIs(mock, 0x01, 100), "set_wiper_ch0");
        pot.setWiper(1, 200);
        check_true(lastIs(mock, 0x03, 200), "set_wiper_ch1");
        pot.setWiper(0, 300);
        check_true(lastIs(mock, 0x01, 255), "set_wiper_clamps_high");
        pot.setWiper(0, -5);
        check_true(lastIs(mock, 0x01, 0), "set_wiper_clamps_low");
        size_t n = mock.writes().size();
        check_true(!pot.setWiper(2, 1) && mock.writes().size() == n, "invalid_channel_rejected");
    }

    // getWiper(): the instruction byte is the register pointer.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x01, {17});
        mock.setRegister(0x03, {99});
        AD5252Minimal pot(mock);
        check_true(pot.getWiper(0) == 17, "get_wiper_ch0");
        check_true(pot.getWiper(1) == 99, "get_wiper_ch1");
    }

    // Resistance conversions (10 kOhm, RW = 75).
    {
        I2CConnectionMock mock;
        AD5252Minimal pot(mock);
        pot.setResistance(0, 5075.0f);
        check_true(lastIs(mock, 0x01, 128), "set_resistance_mid");
        pot.setResistance(0, 0.0f);
        check_true(lastIs(mock, 0x01, 0), "set_resistance_clamps_low");
        pot.setResistance(1, 1e6f);
        check_true(lastIs(mock, 0x03, 255), "set_resistance_clamps_high");
        mock.setRegister(0x01, {128});
        mock.setRegister(0x03, {0});
        check_true(std::fabs(pot.getResistance(0) - 5075.0f) < 0.01f, "get_resistance_mid");
        check_true(std::fabs(pot.getResistance(1) - 75.0f) < 0.01f, "get_resistance_zero");
    }

    // Full: both / ratio / W-A.
    {
        I2CConnectionMock mock;
        AD5252Full pot(mock);
        pot.setBoth(1, 2);
        check_true(mock.writes().size() == 1 && mock.writes()[0].size() == 3 && mock.writes()[0][0] == 0x01 &&
                   mock.writes()[0][1] == 1 && mock.writes()[0][2] == 2, "set_both_one_transaction");
        pot.setRatio(0, 0.25f);
        check_true(lastIs(mock, 0x01, 64), "set_ratio_quarter");
        pot.setRatio(0, 1.0f);
        check_true(lastIs(mock, 0x01, 255), "set_ratio_clamps_255");
        pot.setResistanceWa(1, 5075.0f);
        check_true(lastIs(mock, 0x03, 128), "set_resistance_wa_mid");

        mock.setRegister(0x01, {250});
        mock.setRegister(0x03, {128});
        check_true(pot.getRatio(1) == 0.5f, "get_ratio");
        check_true(std::fabs(pot.getResistanceWa(1) - 5075.0f) < 0.01f, "get_resistance_wa");
    }

    // Quick commands.
    {
        I2CConnectionMock mock;
        AD5252Full pot(mock);
        pot.increment(0); pot.increment(1); pot.increment();
        pot.decrement(0); pot.decrement(1); pot.decrement();
        check_true(writesAre(mock, {0xD1, 0xD3, 0xD8, 0xA9, 0xAB, 0xB0}), "increment_decrement_bytes");
        mock.clearWrites();
        pot.doubleCode(0); pot.doubleCode(1); pot.doubleCode();
        pot.halve(0); pot.halve(1); pot.halve();
        check_true(writesAre(mock, {0xC1, 0xC3, 0xC8, 0x99, 0x9B, 0xA0}), "double_halve_bytes");
        mock.clearWrites();
        pot.nop();
        check_true(writesAre(mock, {0x80}), "nop");
        mock.clearWrites();
        check_true(!pot.increment(2) && mock.writes().empty(), "quick_invalid_channel_rejected");
    }

    // Store / restore / reset with ACK polling.
    {
        I2CConnectionMock mock;
        AD5252Full pot(mock);
        check_true(pot.store(1) && writesAre(mock, {0x93, 0x80}), "store_ch1");
        mock.clearWrites();
        mock.failNextWrites(2, 1);
        check_true(pot.store(0) && writesAre(mock, {0x91, 0x80, 0x80, 0x80}), "store_polls_until_ack");
        mock.clearWrites();
        check_true(pot.store() && writesAre(mock, {0x91, 0x80, 0x93, 0x80}), "store_both");
        mock.clearWrites();
        check_true(pot.restore(0) && writesAre(mock, {0x89, 0x80}), "restore_ch0");
        mock.clearWrites();
        check_true(pot.restore() && writesAre(mock, {0x89, 0x8B, 0x80}), "restore_both");
        mock.clearWrites();
        check_true(pot.reset() && writesAre(mock, {0xB8, 0x80}), "reset");
    }
    {
        I2CConnectionMock mock;
        AD5252Full pot(mock);
        mock.failNextWrites(1000, 1);
        check_true(!pot.store(0), "store_timeout_fails");
    }

    // User EEMEM.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x24, {0x5A});
        mock.setRegister(0x2F, {0xA5});
        AD5252Full pot(mock);
        check_true(pot.readEemem(4) == 0x5A && pot.readEemem(15) == 0xA5, "read_eemem");
        mock.clearWrites();
        check_true(pot.writeEemem(9, 0x42) && mock.writes().size() == 2 &&
                   mock.writes()[0].size() == 2 && mock.writes()[0][0] == 0x29 && mock.writes()[0][1] == 0x42 &&
                   mock.writes()[1].size() == 1 && mock.writes()[1][0] == 0x80, "write_eemem");
        mock.clearWrites();
        check_true(!pot.writeEemem(3, 0) && !pot.writeEemem(16, 0) && mock.writes().empty(), "write_eemem_range");
        check_true(pot.readEemem(3) == 0, "read_eemem_range");
    }

    // Tolerance: datasheet example 0x1C / 0x0F -> +28.0586 %.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x3A, {0x1C, 0x0F});
        mock.setRegister(0x3E, {0x82, 0x80});
        AD5252Full pot(mock);
        check_true(std::fabs(pot.readTolerance(0) - 28.05859375f) < 1e-4f, "tolerance_positive");
        check_true(std::fabs(pot.readTolerance(1) + 2.5f) < 1e-5f, "tolerance_negative");
        check_true(std::fabs(pot.rabActual(0) - 12805.859f) < 0.05f, "rab_actual");
    }

    // setRab(): conversions only.
    {
        I2CConnectionMock mock;
        mock.setRegister(0x01, {128});
        AD5252Full pot(mock);
        pot.setRab(50000.0f, 100.0f);
        check_true(mock.writes().empty(), "set_rab_no_traffic");
        check_true(std::fabs(pot.getResistance(0) - 25100.0f) < 0.01f, "set_rab_conversion");
        pot.setRab(10000.0f);
        check_true(std::fabs(pot.getResistance(0) - 5100.0f) < 0.01f, "set_rab_keeps_rw");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed > 0 ? 1 : 0;
}
