#include <stdio.h>
#include <stdint.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "DS1881.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool lastIs(const I2CConnectionMock& mock, std::vector<uint8_t> bytes) {
    const auto& w = mock.writes();
    return !w.empty() && w.back() == bytes;
}

int main() {
    // Construction makes no bus traffic; init writes the config when it differs.
    {
        I2CConnectionMock mock;
        DS1881Minimal pot(mock);
        check_true(mock.writes().empty(), "ctor_no_traffic");
        pot.init();
        check_true(mock.writes().size() == 1 && lastIs(mock, {0x86}), "init_writes_config");
    }
    {
        I2CConnectionMock mock;
        mock.queueRead({10, 20, 0x86});
        DS1881Minimal pot(mock);
        pot.init();
        check_true(mock.writes().empty(), "init_matching_sends_nothing");
    }
    {
        I2CConnectionMock mock;
        mock.queueRead({0, 0, 0xB6});  // stray upper bits, low bits already 0b110
        DS1881Minimal pot(mock);
        pot.init();
        check_true(mock.writes().empty(), "init_ignores_upper_bits");
    }

    // Attenuation: command byte (channel << 6) + position, rounding, clamping.
    {
        I2CConnectionMock mock;
        DS1881Minimal pot(mock);
        pot.setAttenuation(0, 20.0f);
        check_true(lastIs(mock, {20}), "set_attenuation_ch0");
        pot.setAttenuation(1, 20.4f);
        check_true(lastIs(mock, {0x40 | 20}), "set_attenuation_rounds_down");
        pot.setAttenuation(1, 20.6f);
        check_true(lastIs(mock, {0x40 | 21}), "set_attenuation_rounds_up");
        pot.setAttenuation(0, 100.0f);
        check_true(lastIs(mock, {62}), "set_attenuation_clamps_62");
        pot.setAttenuation(0, -5.0f);
        check_true(lastIs(mock, {0}), "set_attenuation_clamps_0");
        size_t n = mock.writes().size();
        pot.setAttenuation(2, 5.0f);
        check_true(mock.writes().size() == n, "bad_channel_ignored");
        pot.mute(1);
        check_true(lastIs(mock, {0x7F}), "mute");
        pot.setPosition(0, 99);
        check_true(lastIs(mock, {63}), "set_position_clamps");
        pot.setPosition(0, -3);
        check_true(lastIs(mock, {0}), "set_position_clamps_low");
    }

    // Reads: three-byte round robin, upper bits masked.
    {
        I2CConnectionMock mock;
        for (int i = 0; i < 4; i++) mock.queueRead({0xC5, 0x3F, 0x86});
        DS1881Minimal pot(mock);
        check_true(pot.getPosition(0) == 5, "get_position_ch0");
        check_true(pot.getPosition(1) == 63, "get_position_ch1");
        check_true(pot.getAttenuation(1) == 80.0f, "get_attenuation_mute");
        check_true(pot.getAttenuation(0) == 5.0f, "get_attenuation_value");
        check_true(mock.writes().empty(), "reads_no_writes");
    }

    // Full: stereo write in one transaction.
    {
        I2CConnectionMock mock;
        DS1881Full full(mock);
        full.setBoth(10, 99);
        check_true(mock.writes().size() == 1 && lastIs(mock, {10, 0x40 | 63}), "set_both_one_transaction");
        full.setAttenuationBoth(30.0f);
        check_true(lastIs(mock, {30, 0x40 | 30}), "set_attenuation_both_default");
        full.setAttenuationBoth(5.0f, 70.0f);
        check_true(lastIs(mock, {5, 0x7F}), "set_attenuation_both_mute");
        check_true(full.positionToDb(0) == 0.0f && full.positionToDb(62) == 62.0f && full.positionToDb(63) == 80.0f, "opt1_db");
        check_true(full.maxPosition() == 62, "max_position_opt1");
        check_true(full.dbToPosition(12.4f) == 12 && full.dbToPosition(75.0f) == 63, "db_to_position_opt1");
    }

    // configure: Option 2 -> 0x87, unchanged -> no write, table switches.
    {
        I2CConnectionMock mock;
        DS1881Full full(mock);
        full.configure(2, true, false);
        check_true(lastIs(mock, {0x87}), "configure_option2");
        size_t n = mock.writes().size();
        full.configure(2, true, false);
        check_true(mock.writes().size() == n, "configure_unchanged_no_write");
        check_true(full.positionToDb(0) == 0.0f && full.positionToDb(12) == 12.0f && full.positionToDb(13) == 14.0f &&
                   full.positionToDb(24) == 36.0f && full.positionToDb(25) == 39.0f && full.positionToDb(32) == 60.0f &&
                   full.positionToDb(33) == 80.0f, "opt2_table");
        check_true(full.maxPosition() == 32, "max_position_opt2");
        check_true(full.dbToPosition(14.0f) == 13 && full.dbToPosition(36.0f) == 24 && full.dbToPosition(60.0f) == 32, "db_to_position_opt2");
        check_true(full.dbToPosition(37.0f) == 24 && full.dbToPosition(38.0f) == 25, "db_to_position_opt2_nearest");
        check_true(full.dbToPosition(70.0f) == 33, "db_to_position_opt2_mute");
        full.setBoth(40, 40);
        check_true(lastIs(mock, {33, 0x40 | 33}), "opt2_positions_clamped");
        full.mute(0);
        check_true(lastIs(mock, {33}), "opt2_mute");
        n = mock.writes().size();
        full.configure(3);
        check_true(mock.writes().size() == n, "bad_option_ignored");
    }

    // set_zero_crossing / set_nonvolatile / set_option keep the other bits.
    {
        I2CConnectionMock mock;
        DS1881Full full(mock);
        full.setZeroCrossing(false);
        check_true(lastIs(mock, {0x84}), "zc_off");
        full.setNonvolatile(true);
        check_true(lastIs(mock, {0x80}), "nv_on");
        full.setOption(2);
        check_true(lastIs(mock, {0x81}), "option2_keeps_bits");
        mock.failNextReads(1);
        full.setBoth(1, 2);
        check_true(lastIs(mock, {1, 0x42}), "nv_write_polls");
    }

    // getConfig, readAll.
    {
        I2CConnectionMock mock;
        mock.queueRead({0, 0, 0x87});
        mock.queueRead({1, 2, 0x82});
        mock.queueRead({0x41, 0x02, 0x86});
        DS1881Full full(mock);
        DS1881Config cfg = full.getConfig();
        check_true(cfg.option == 2 && cfg.zeroCrossing && !cfg.nonvolatile && cfg.raw == 0x87, "get_config");
        check_true(full.maxPosition() == 32, "get_config_updates_cache");
        cfg = full.getConfig();
        check_true(cfg.nonvolatile && cfg.option == 1 && cfg.zeroCrossing, "get_config_nv");
        uint8_t p0, p1, c;
        full.readAll(p0, p1, c);
        check_true(p0 == 1 && p1 == 2 && c == 0x86, "read_all");
    }

    // waitReady: recovers, times out.
    {
        I2CConnectionMock mock;
        DS1881Full full(mock);
        mock.failNextReads(3);
        check_true(full.waitReady(0.05f), "wait_ready_recovers");
        mock.failNextReads(1000);
        check_true(!full.waitReady(0.01f), "wait_ready_times_out");
    }

    // store: volatile -> NV config, both wipers in one transaction, back to volatile.
    {
        I2CConnectionMock mock;
        mock.queueRead({12, 34, 0x86});
        DS1881Full full(mock);
        full.store();
        const auto& w = mock.writes();
        check_true(w.size() == 3 && w[0] == std::vector<uint8_t>({0x82}) &&
                   w[1] == std::vector<uint8_t>({12, 0x40 | 34}) && w[2] == std::vector<uint8_t>({0x86}), "store_sequence");
    }

    // reset.
    {
        I2CConnectionMock mock;
        DS1881Full full(mock);
        full.configure(2, false, true);
        full.reset();
        check_true(lastIs(mock, {0x86, 0x3F, 0x7F}), "reset");
        check_true(full.maxPosition() == 62, "reset_restores_option1");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
