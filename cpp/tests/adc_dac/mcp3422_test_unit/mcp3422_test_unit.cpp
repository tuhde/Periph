#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "MCP3422.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol = 1e-6f) { return fabsf(a - b) < tol; }

// Queue a 2-byte result frame (12/14/16 bit) followed by the config byte.
static void q2(I2CConnectionMock& m, int32_t raw, bool rdy = false, uint8_t cfg = 0x00) {
    m.queueRead({(uint8_t)((raw >> 8) & 0xFF), (uint8_t)(raw & 0xFF), (uint8_t)(cfg | (rdy ? 0x80 : 0))});
}

// Queue a 3-byte result frame (18 bit) followed by the config byte.
static void q3(I2CConnectionMock& m, int32_t raw, bool rdy = false, uint8_t cfg = 0x00) {
    m.queueRead({(uint8_t)((raw >> 16) & 0xFF), (uint8_t)((raw >> 8) & 0xFF), (uint8_t)(raw & 0xFF), (uint8_t)(cfg | (rdy ? 0x80 : 0))});
}

static uint8_t lastWrite(const I2CConnectionMock& m) { return m.writes().back()[0]; }

int main() {
    // Init writes the default config: one-shot, 16 bit, x1, CH1, RDY = 0.
    {
        I2CConnectionMock m;
        MCP3422Full chip(m);
        check_true(m.writes().size() == 1 && m.writes()[0].size() == 1 && m.writes()[0][0] == 0x08, "init_writes_default_config");
    }
    // One-shot config bytes and channel clamping.
    {
        I2CConnectionMock m; q2(m, 0); q2(m, 0); q2(m, 0); q2(m, 0);
        MCP3422Minimal chip(m);
        chip.readRaw(0);
        check_true(lastWrite(m) == 0x88, "one_shot_config_ch1");
        chip.readRaw(1);
        check_true(lastWrite(m) == 0xA8, "one_shot_config_ch2");
        chip.readRaw(7);
        check_true(lastWrite(m) == 0xA8, "channel_clamped_high");
        chip.readRaw(-3);
        check_true(lastWrite(m) == 0x88, "channel_clamped_low");
    }
    // Decoding and sign extension at every resolution (12 bit keeps the tests fast).
    {
        I2CConnectionMock m;
        q2(m, 100); q2(m, -100); q2(m, 2047); q2(m, -2048);
        MCP3422Full chip(m);
        chip.setResolution(12);
        check_true(chip.readRaw(0) == 100, "decode_12_positive");
        check_true(chip.readRaw(0) == -100, "decode_12_negative");
        check_true(chip.readRaw(0) == 2047, "decode_12_max");
        check_true(chip.readRaw(0) == -2048, "decode_12_min");
    }
    {
        I2CConnectionMock m;
        q2(m, 8191); q2(m, -8192);
        MCP3422Full chip(m);
        chip.setResolution(14);
        check_true(chip.readRaw(0) == 8191, "decode_14_positive");
        check_true(chip.readRaw(0) == -8192, "decode_14_negative");
    }
    {
        I2CConnectionMock m;
        q2(m, 12345); q2(m, -12345); q2(m, -32768);
        MCP3422Minimal chip(m);
        check_true(chip.readRaw(0) == 12345, "decode_16_positive");
        check_true(chip.readRaw(0) == -12345, "decode_16_negative");
        check_true(chip.readRaw(0) == -32768, "decode_16_min");
    }
    {
        I2CConnectionMock m;
        q3(m, 131071); q3(m, -131072); q3(m, -1);
        m.queueRead({0xFC, 0x00, 0x05, 0x00});     // byte0's upper six bits are not data
        MCP3422Full chip(m);
        chip.setResolution(18);
        check_true(chip.readRaw(0) == 131071, "decode_18_positive");
        check_true(chip.readRaw(0) == -131072, "decode_18_negative");
        check_true(chip.readRaw(0) == -1, "decode_18_small_negative");
        check_true(chip.readRaw(0) == 5, "decode_18_masks_upper_bits");
    }
    // read_voltage = raw * lsb / gain, lsb and full scale.
    {
        I2CConnectionMock m; q2(m, 16384); q2(m, -32768);
        MCP3422Full chip(m);
        check_true(near(chip.readVoltage(0), 16384 * 2 * 2.048f / 65536, 1e-5f), "voltage_x1");
        chip.setGain(8);
        check_true(near(chip.readVoltage(0), -0.256f, 1e-5f), "voltage_full_scale_x8");
        check_true(near(chip.lsb(), 62.5e-6f, 1e-9f), "lsb_16");
        check_true(near(chip.fullScale(), 0.256f), "full_scale_x8");
        chip.setResolution(12);
        check_true(near(chip.lsb(), 1e-3f, 1e-8f), "lsb_12");
        chip.setResolution(18);
        check_true(near(chip.lsb(), 15.625e-6f, 1e-9f), "lsb_18");
    }
    // readAll converts CH1 then CH2.
    {
        I2CConnectionMock m; q2(m, 100); q2(m, 200);
        MCP3422Minimal chip(m);
        float v[2] = {0, 0};
        check_true(chip.readAll(v) && near(v[0], 100 * 2 * 2.048f / 65536, 1e-5f) && near(v[1], 200 * 2 * 2.048f / 65536, 1e-5f), "read_all_two_values");
        check_true(m.writes()[1][0] == 0x88 && m.writes()[2][0] == 0xA8, "read_all_channel_order");
    }
    // RDY polling and timeout.
    {
        I2CConnectionMock m; q2(m, 1, true); q2(m, 1, true); q2(m, 777);
        MCP3422Minimal chip(m);
        check_true(chip.readRaw(0) == 777 && chip.lastReadOk(), "polls_until_ready");
    }
    {
        I2CConnectionMock m;
        for (int i = 0; i < 40; i++) q2(m, 1, true);
        MCP3422Minimal chip(m);
        chip.readRaw(0);
        check_true(!chip.lastReadOk(), "timeout_flagged");
    }
    // Full configuration.
    {
        I2CConnectionMock m;
        MCP3422Full chip(m);
        check_true(!chip.setResolution(10) && !chip.setGain(3), "bad_values_rejected");
        check_true(chip.setResolution(18) && chip.setGain(8) && chip.getResolution() == 18 && chip.getGain() == 8, "getters");
        q3(m, 0);
        chip.readRaw(0);
        check_true(lastWrite(m) == 0x8F, "one_shot_config_18bit_x8");
    }
    // Continuous mode.
    {
        I2CConnectionMock m; q2(m, 5); q2(m, 300); q2(m, 301);
        MCP3422Full chip(m);
        chip.setResolution(12);
        chip.startContinuous();
        check_true(lastWrite(m) == 0x10 && chip.isContinuous(), "start_continuous_config");
        check_true(chip.readContinuousRaw() == 300, "read_continuous_discards_first_sample");
        check_true(chip.readContinuousRaw() == 301, "read_continuous_next");
        chip.startContinuous(1);
        check_true(lastWrite(m) == 0x30, "start_continuous_channel");
        chip.stopContinuous();
        check_true(lastWrite(m) == 0x20 && !chip.isContinuous(), "stop_continuous_config");
        chip.readContinuousRaw();
        check_true(!chip.lastReadOk(), "read_continuous_requires_mode");
    }
    {
        I2CConnectionMock m; q2(m, 5); q2(m, 16384);
        MCP3422Full chip(m);
        chip.startContinuous();
        check_true(near(chip.readContinuous(), 16384 * 2 * 2.048f / 65536, 1e-5f), "read_continuous_voltage");
    }
    {
        I2CConnectionMock m; q2(m, 1); q2(m, 2); q2(m, 3); q2(m, 4);
        MCP3422Full chip(m);
        chip.setResolution(12);
        chip.startContinuous();
        chip.readContinuousRaw();
        size_t n = m.writes().size();
        chip.setGain(2);
        check_true(m.writes().size() == n + 1 && lastWrite(m) == 0x11, "gain_change_rewrites_config");
        check_true(chip.readContinuousRaw() == 4, "gain_change_discards_sample");
    }
    // readConfig / isReady.
    {
        I2CConnectionMock m; q2(m, 0, true, 0x08); q2(m, 0, false, 0x08);
        MCP3422Full chip(m);
        check_true(chip.readConfig() == 0x88, "read_config");
        check_true(chip.isReady(), "is_ready");
    }
    // readScan.
    {
        I2CConnectionMock m; q2(m, 10); q2(m, 20); q2(m, 30);
        MCP3422Full chip(m);
        const uint8_t ch[3] = {1, 0, 1};
        float out[3];
        bool ok = chip.readScan(ch, 3, out);
        check_true(ok && m.writes()[1][0] == 0xA8 && m.writes()[2][0] == 0x88 && m.writes()[3][0] == 0xA8, "read_scan_order");
    }
    // General calls.
    {
        I2CConnectionMock m;
        MCP3422Full chip(m);
        chip.setResolution(18);
        chip.setGain(8);
        chip.generalCallReset();
        check_true(m.writes().back().size() == 2 && m.writes().back()[0] == 0x00 && m.writes().back()[1] == 0x06, "general_call_reset_bytes");
        check_true(chip.getResolution() == 12 && chip.getGain() == 1 && chip.isContinuous(), "general_call_reset_state");
    }
    {
        I2CConnectionMock m; q2(m, 321);
        MCP3422Full chip(m);
        chip.generalCallConvert();
        check_true(m.writes().back().size() == 2 && m.writes().back()[1] == 0x08, "general_call_convert_bytes");
        check_true(chip.readPendingRaw() == 321, "read_pending_raw");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
