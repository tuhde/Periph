#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "DS3502.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Writes with a data byte (a bare register-pointer write has size 1).
static size_t dataWrites(const I2CConnectionMock& m) {
    size_t n = 0;
    for (const auto& w : m.writes()) if (w.size() > 1) n++;
    return n;
}

static void preload(I2CConnectionMock& m, uint8_t wr) {
    m.setRegister(0x00, {wr});
    m.setRegister(0x02, {0x00});
}

int main() {
    // Init: CR <- 0x80 (MODE = 1), WR read; the wiper is not written.
    {
        I2CConnectionMock mock; preload(mock, 0x23);
        DS3502Full pot(mock);
        check_true(mock.registers().at(0x02) == 0x80, "init_sets_mode_sram");
        check_true(dataWrites(mock) == 1 && mock.writes()[0][0] == 0x02, "init_writes_only_cr");
        check_true(mock.registers().at(0x00) == 0x23, "init_keeps_wiper");
        check_true(pot.getWiper() == 0x23, "init_caches_wr");
    }
    // Init retries once after a NACK (power-up recall).
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        mock.failNextWrites(1);
        DS3502Minimal pot(mock);
        check_true(mock.registers().at(0x02) == 0x80 && mock.writes().size() >= 2, "init_retries_after_nack");
    }
    // setWiper / getWiper
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Minimal pot(mock);
        pot.setWiper(100);
        check_true(mock.registers().at(0x00) == 100, "set_wiper");
        pot.setWiper(300);
        check_true(mock.registers().at(0x00) == 127, "set_wiper_clamps_high");
        pot.setWiper(-5);
        check_true(mock.registers().at(0x00) == 0, "set_wiper_clamps_low");
        mock.setRegister(0x00, {0xFF});
        check_true(pot.getWiper() == 127, "get_wiper_masks_bit7");
        mock.setRegister(0x00, {17});
        check_true(pot.getWiper() == 17, "get_wiper");
    }
    // Voltage conversion (RH = 15 V, RL = 0 V)
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Minimal pot(mock, 15.0f, 0.0f);
        pot.setVoltage(6.0f);
        check_true(mock.registers().at(0x00) == 51, "set_voltage_code");
        check_true(fabsf(pot.getVoltage() - 15.0f * 51.0f / 127.0f) < 1e-4f, "get_voltage");
        pot.setVoltage(99.0f);
        check_true(mock.registers().at(0x00) == 127, "set_voltage_clamps_high");
        pot.setVoltage(-3.0f);
        check_true(mock.registers().at(0x00) == 0, "set_voltage_clamps_low");
        mock.setRegister(0x00, {127});
        check_true(fabsf(pot.getVoltage() - 15.0f) < 1e-4f, "get_voltage_full_scale");
    }
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Minimal pot(mock, 12.0f, 2.0f);
        pot.setVoltage(7.0f);
        check_true(mock.registers().at(0x00) == 64, "set_voltage_offset_low");
    }
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Minimal pot(mock, 5.0f, 5.0f);
        pot.setVoltage(5.0f);
        check_true(mock.registers().at(0x00) == 0, "set_voltage_zero_span");
    }
    // Ratio / resistance
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        pot.setRatio(0.5f);
        check_true(mock.registers().at(0x00) == 64, "set_ratio");
        pot.setRatio(2.0f);
        check_true(mock.registers().at(0x00) == 127, "set_ratio_clamps");
        check_true(pot.getRatio() == 1.0f, "get_ratio");
        mock.setRegister(0x00, {0});
        check_true(pot.getResistance('L') == 0.0f && pot.getResistance('H') == 10000.0f, "resistance_zero");
        mock.setRegister(0x00, {127});
        check_true(pot.getResistance() == 10000.0f && pot.getResistance('H') == 0.0f, "resistance_full");
        check_true(pot.getResistance('X') < 0.0f, "resistance_bad_terminal");
    }
    // increment
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        check_true(pot.increment(5) == 69 && mock.registers().at(0x00) == 69, "increment_returns_code");
        check_true(pot.increment(-10) == 59, "increment_negative");
        mock.setRegister(0x00, {125});
        check_true(pot.increment(10) == 127, "increment_clamps_high");
        mock.setRegister(0x00, {3});
        check_true(pot.increment(-10) == 0, "increment_clamps_low");
    }
    // MODE
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        check_true(!pot.getMode(), "get_mode_sram");
        pot.setMode(true);
        check_true(mock.registers().at(0x02) == 0x00 && pot.getMode(), "set_mode_eeprom");
        pot.setMode(false);
        check_true(mock.registers().at(0x02) == 0x80, "set_mode_sram");
    }
    // storeWiper: CR <- 0x00, WR <- code, (poll), CR <- 0x80
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        size_t n0 = mock.writes().size();
        check_true(pot.storeWiper(77), "store_wiper_ok");
        std::vector<std::pair<uint8_t, uint8_t>> seq;
        for (size_t i = n0; i < mock.writes().size(); i++)
            if (mock.writes()[i].size() > 1) seq.push_back({mock.writes()[i][0], mock.writes()[i][1]});
        check_true(seq.size() == 3 && seq[0] == std::make_pair((uint8_t)0x02, (uint8_t)0x00) &&
                   seq[1] == std::make_pair((uint8_t)0x00, (uint8_t)77) &&
                   seq[2] == std::make_pair((uint8_t)0x02, (uint8_t)0x80), "store_wiper_sequence");
        check_true(mock.registers().at(0x02) == 0x80 && !pot.getMode(), "store_wiper_restores_mode");
        check_true(pot.getWiper() == 77, "store_wiper_moves_wiper");
        mock.setRegister(0x00, {33});
        pot.storeWiper();
        check_true(mock.registers().at(0x00) == 33, "store_wiper_default_current");
    }
    // storeWiper restores MODE = 1 even when the EEPROM write never finishes.
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        mock.failNextWrites(31, 2);  // CR <- 0, WR <- code succeed; all 31 polls NACK
        check_true(!pot.storeWiper(10), "store_wiper_timeout_returns_false");
        check_true(mock.registers().at(0x02) == 0x80, "store_wiper_restores_mode_on_error");
    }
    // waitReady
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock);
        mock.failNextWrites(3);
        check_true(pot.waitReady(), "wait_ready_polls_until_ack");
        mock.failNextWrites(1000);
        check_true(!pot.waitReady(5.0f), "wait_ready_times_out");
    }
    // setEndpoints: no bus traffic, changes the conversions.
    {
        I2CConnectionMock mock; preload(mock, 0x40);
        DS3502Full pot(mock, 1.0f, 0.0f);
        size_t n = mock.writes().size();
        pot.setEndpoints(10.0f, 0.0f);
        check_true(mock.writes().size() == n, "set_endpoints_no_traffic");
        mock.setRegister(0x00, {127});
        check_true(fabsf(pot.getVoltage() - 10.0f) < 1e-4f, "set_endpoints_used");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
