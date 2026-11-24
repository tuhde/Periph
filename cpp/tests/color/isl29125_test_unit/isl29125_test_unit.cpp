#include <stdio.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "Isl29125.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void preload(I2CConnectionMock& m, uint8_t id = 0x7D) {
    m.setRegister(0x00, {id});
    m.setRegister(0x01, {0x00});
    m.setRegister(0x02, {0x00});
    m.setRegister(0x03, {0x00});
}

static uint8_t reg(const I2CConnectionMock& m, uint16_t r) {
    auto it = m.registers().find(r);
    return it != m.registers().end() ? it->second : 0;
}

static int g_calls = 0;
static int g_status = 0;
static void onInt(uint8_t status) { g_status = status; g_calls++; }

int main() {
    Isl29125Minimal::pollStepMs = 0;

    // Init sequence and identity check.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        std::vector<std::pair<uint8_t, uint8_t>> seq;
        for (const auto& w : mock.writes()) if (w.size() > 1) seq.push_back({w[0], w[1]});
        std::vector<std::pair<uint8_t, uint8_t>> want = {{0x08, 0x00}, {0x03, 0x00}, {0x02, 0xBF}, {0x01, 0x0D}};
        check_true(chip.valid() && seq == want, "init_sequence");
    }
    {
        I2CConnectionMock mock; preload(mock, 0x00);
        Isl29125Minimal chip(mock);
        Isl29125Counts c = {1, 2, 3};
        bool wrote = false;
        for (const auto& w : mock.writes()) if (w.size() > 1) wrote = true;
        check_true(!chip.valid() && !wrote && !chip.read(c) && c.red == 1, "init_rejects_wrong_id");
    }
    // Channel decoding: green/red/blue, 16 bit LSB first.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        mock.setRegister(0x08, {0x02});
        mock.setRegister(0x09, {0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00});
        Isl29125Counts c = {0, 0, 0};
        check_true(chip.read(c), "read_ok");
        check_true(c.green == 0x1234, "read_green");
        check_true(c.red == 0xFFFF, "read_red");
        check_true(c.blue == 1, "read_blue");
        Isl29125Counts d = {0, 0, 0};
        chip.readRgb(d);
        check_true(d.red == 0xFFFF && d.green == 0x1234 && d.blue == 1, "read_rgb");
        check_true(chip.readRed() == 0xFFFF && chip.readGreen() == 0x1234 && chip.readBlue() == 1, "read_single");
        check_true(chip.deviceId() == 0x7D, "device_id");
    }
    // Configuration read-modify-write.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        mock.setRegister(0x01, {0x0D});
        bool ok = true;
        for (uint8_t mode = 0; mode < 8; mode++)
            ok = ok && chip.setMode(mode) && reg(mock, 0x01) == (0x08 | mode);
        check_true(ok, "modes");
        check_true(chip.setRange(375) && reg(mock, 0x01) == 0x07, "range_375");
        check_true(chip.setRange(10000) && reg(mock, 0x01) == 0x0F, "range_10000");
        check_true(chip.setResolution(12) && reg(mock, 0x01) == 0x1F, "resolution_12");
        check_true(chip.setResolution(16) && reg(mock, 0x01) == 0x0F, "resolution_16");
        chip.setSync(true);
        check_true(reg(mock, 0x01) == 0x2F, "sync_external");
        chip.setSync(false);
        check_true(reg(mock, 0x01) == 0x0F, "sync_i2c");
        check_true(chip.setIrCompensation(false, 0x3F) && reg(mock, 0x02) == 0x3F, "ir_comp_low");
        check_true(chip.setIrCompensation(true, 0x3F) && reg(mock, 0x02) == 0xBF, "ir_comp_high");
        check_true(!chip.setMode(8) && !chip.setRange(100) && !chip.setResolution(8) &&
                   !chip.setIrCompensation(false, 64) && !chip.setPersistence(3) &&
                   !chip.setInterruptChannel(4) && !chip.setThresholds(5, 4), "invalid_config");
    }
    // Reset.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        chip.softReset();
        const auto& last = mock.writes().back();
        check_true(last.size() == 2 && last[0] == 0x00 && last[1] == 0x46, "soft_reset");
    }
    // Thresholds.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        check_true(chip.setThresholds(0x1234, 0xABCD), "thresholds_set");
        check_true(reg(mock, 0x04) == 0x34 && reg(mock, 0x05) == 0x12, "threshold_low_bytes");
        check_true(reg(mock, 0x06) == 0xCD && reg(mock, 0x07) == 0xAB, "threshold_high_bytes");
        uint16_t lo = 0, hi = 0;
        chip.getThresholds(lo, hi);
        check_true(lo == 0x1234 && hi == 0xABCD, "thresholds_roundtrip");
    }
    // Interrupt configuration.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        chip.setInterruptChannel(Isl29125Full::CHANNEL_RED);
        check_true(reg(mock, 0x03) == 0x02, "channel_red");
        chip.setInterruptChannel(Isl29125Full::CHANNEL_BLUE);
        check_true(reg(mock, 0x03) == 0x03, "channel_blue");
        chip.setInterruptChannel(Isl29125Full::CHANNEL_NONE);
        chip.setPersistence(8);
        check_true(reg(mock, 0x03) == 0x0C, "persistence_8");
        chip.setPersistence(4);
        chip.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);
        check_true(reg(mock, 0x03) == 0x09, "persistence_keeps_channel");
        chip.enableInterrupt(Isl29125Full::SOURCE_CONVERSION);
        check_true(reg(mock, 0x03) == 0x19, "enable_conversion");
        chip.disableInterrupt(Isl29125Full::SOURCE_CONVERSION);
        check_true(reg(mock, 0x03) == 0x09, "disable_conversion");
        chip.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
        check_true(reg(mock, 0x03) == 0x08, "disable_threshold");
        chip.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
        check_true(reg(mock, 0x03) == 0x09, "enable_threshold_defaults_green");
        chip.setInterruptChannel(Isl29125Full::CHANNEL_RED);
        chip.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
        check_true(reg(mock, 0x03) == 0x0A, "enable_threshold_keeps_channel");
    }
    // Status latching: dataReady() must not lose the threshold bit.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        mock.setRegister(0x08, {0x03});
        check_true(chip.dataReady(), "data_ready_true");
        mock.setRegister(0x08, {0x00});
        check_true(!chip.dataReady(), "data_ready_false");
        check_true(chip.pollInterrupt() == 0x03, "flags_kept_after_data_ready");
        check_true(chip.pollInterrupt() == 0, "poll_clears_latch");
        mock.setRegister(0x08, {0x04});
        check_true(chip.status() == 0x04, "status_brownout");
    }
    // Callback.
    {
        I2CConnectionMock mock; preload(mock);
        Isl29125Full chip(mock);
        chip.onInterrupt(onInt);
        mock.setRegister(0x08, {0x01});
        chip.service();
        mock.setRegister(0x08, {0x00});
        chip.service();
        check_true(g_calls == 1 && g_status == 0x01, "callback_receives_status");
        chip.offInterrupt();
        mock.setRegister(0x08, {0x01});
        chip.service();
        check_true(g_calls == 1, "off_interrupt_stops_callback");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
