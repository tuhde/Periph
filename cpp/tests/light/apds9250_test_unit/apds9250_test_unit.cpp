#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "Apds9250.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol = 1e-3f) { return fabsf(a - b) < tol; }

static void preload(I2CConnectionMock& m, uint8_t part = 0xB5) {
    m.setRegister(0x06, {part});
    m.setRegister(0x07, {0x08});
    m.setRegister(0x04, {0x22});
    m.setRegister(0x05, {0x01});
    m.setRegister(0x19, {0x10});
}

static uint8_t reg(const I2CConnectionMock& m, uint16_t r) {
    auto it = m.registers().find(r);
    return it != m.registers().end() ? it->second : 0;
}

static int g_calls = 0;
static int g_status = 0;
static void onInt(uint8_t status) { g_status = status; g_calls++; }

int main() {
    Apds9250Minimal::startWaitMs = 0;
    Apds9250Minimal::pollStepMs = 0;

    // Init sequence and identity check.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        std::vector<std::pair<uint8_t, uint8_t>> seq;
        for (const auto& w : mock.writes()) if (w.size() > 1) seq.push_back({w[0], w[1]});
        std::vector<std::pair<uint8_t, uint8_t>> want = {{0x04, 0x22}, {0x05, 0x01}, {0x19, 0x10}, {0x00, 0x06}};
        check_true(chip.valid() && seq == want, "init_sequence");
    }
    {
        I2CConnectionMock mock; preload(mock, 0x00);
        Apds9250Minimal chip(mock);
        Apds9250Counts c = {1, 2, 3, 4};
        bool wrote = false;
        for (const auto& w : mock.writes()) if (w.size() > 1) wrote = true;
        check_true(!chip.valid() && !wrote && !chip.read(c) && c.red == 1, "init_rejects_wrong_part");
    }
    {
        I2CConnectionMock mock; preload(mock, 0xC5);
        Apds9250Minimal chip(mock);
        check_true(!chip.valid(), "init_rejects_wrong_nibble");
    }
    // Channel decoding: IR/green/blue/red, 3 bytes LSB first, upper nibble ignored.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        mock.setRegister(0x0A, {0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4});
        Apds9250Counts c = {0, 0, 0, 0};
        check_true(chip.read(c), "read_ok");
        check_true(c.ir == 0x030201, "read_ir");
        check_true(c.green == 16, "read_green");
        check_true(c.blue == 0xFFFFF, "read_blue");
        check_true(c.red == 0x40000, "read_red_masks_nibble");
        Apds9250Counts d = {0, 0, 0, 0};
        chip.readChannels(d);
        check_true(d.red == 0x40000 && d.ir == 0x030201, "read_channels");
        check_true(chip.readRed() == 0x40000 && chip.readGreen() == 16 && chip.readBlue() == 0xFFFFF &&
                   chip.readIr() == 0x030201, "read_single");
    }
    // Configuration.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        check_true(chip.setResolution(20) && reg(mock, 0x04) == 0x02 && chip.getResolution() == 20, "resolution_20");
        check_true(chip.setResolution(13) && reg(mock, 0x04) == 0x52 && near(chip.integrationTime(), 0.003125f, 1e-6f), "resolution_13");
        chip.setResolution(16);
        check_true(chip.setMeasurementRate(2000) && reg(mock, 0x04) == 0x46 && chip.getMeasurementRate() == 2000, "rate_2000");
        check_true(chip.setMeasurementRate(25) && reg(mock, 0x04) == 0x40, "rate_25_keeps_resolution");
        bool ok = true;
        const uint8_t gains[5] = {1, 3, 6, 9, 18};
        for (uint8_t i = 0; i < 5; i++) ok = ok && chip.setGain(gains[i]) && reg(mock, 0x05) == i && chip.getGain() == gains[i];
        check_true(ok, "gains");
        check_true(!chip.setGain(2) && !chip.setResolution(12) && !chip.setMeasurementRate(30), "invalid_config");
        check_true(!chip.setPersistence(0) && !chip.setPersistence(17) && !chip.setVariance(12) &&
                   !chip.setInterruptChannel(4) && !chip.setThresholds(5, 4) && !chip.setThresholds(0, 0x100000), "invalid_interrupt_config");
        chip.setResolution(18);
        chip.setGain(3);
        check_true(near(chip.normalized(3000), 10000.0f, 1.0f), "normalized");
    }
    // Mode, enable, disable, reset, part id.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        mock.setRegister(0x00, {0x06});
        chip.setMode(false);
        check_true(reg(mock, 0x00) == 0x02, "mode_als");
        chip.setMode(true);
        check_true(reg(mock, 0x00) == 0x06, "mode_cs");
        chip.disable();
        check_true(reg(mock, 0x00) == 0x04, "disable");
        chip.enable();
        check_true(reg(mock, 0x00) == 0x06, "enable");
        chip.softReset();
        const auto& last = mock.writes().back();
        check_true(last.size() == 2 && last[0] == 0x00 && last[1] == 0x10, "soft_reset");
        uint8_t part = 0, rev = 0;
        chip.partId(part, rev);
        check_true(part == 0xB && rev == 0x5, "part_id");
    }
    // Thresholds.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        check_true(chip.setThresholds(0x12345, 0xABCDE), "thresholds_set");
        check_true(reg(mock, 0x24) == 0x45 && reg(mock, 0x25) == 0x23 && reg(mock, 0x26) == 0x01, "threshold_low_bytes");
        check_true(reg(mock, 0x21) == 0xDE && reg(mock, 0x22) == 0xBC && reg(mock, 0x23) == 0x0A, "threshold_high_bytes");
        uint32_t lo = 0, hi = 0;
        chip.getThresholds(lo, hi);
        check_true(lo == 0x12345 && hi == 0xABCDE, "thresholds_roundtrip");
    }
    // Interrupt configuration.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        chip.setInterruptChannel(Apds9250Full::CHANNEL_RED);
        check_true(reg(mock, 0x19) == 0x20, "channel_red");
        chip.setInterruptChannel(Apds9250Full::CHANNEL_BLUE);
        check_true(reg(mock, 0x19) == 0x30, "channel_blue");
        chip.enableInterrupt(Apds9250Full::SOURCE_VARIATION);
        check_true(reg(mock, 0x19) == 0x3C, "enable_variation");
        chip.enableInterrupt(Apds9250Full::SOURCE_THRESHOLD);
        check_true(reg(mock, 0x19) == 0x34, "enable_threshold");
        chip.disableInterrupt();
        check_true(reg(mock, 0x19) == 0x30, "disable_interrupt");
        chip.setPersistence(16);
        check_true(reg(mock, 0x1A) == 0xF0, "persistence_16");
        chip.setVariance(1024);
        check_true(reg(mock, 0x27) == 7, "variance_1024");
    }
    // Status latching: dataReady() must not lose the interrupt bit.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        mock.setRegister(0x07, {0x18});
        check_true(chip.dataReady(), "data_ready_true");
        mock.setRegister(0x07, {0x00});
        check_true(!chip.dataReady(), "data_ready_false");
        check_true(chip.pollInterrupt() == 0x10, "interrupt_kept_after_data_ready");
        check_true(chip.pollInterrupt() == 0, "poll_clears_latch");
        mock.setRegister(0x07, {0x20});
        check_true(chip.status() == 0x20, "status_power_on");
    }
    // Callback.
    {
        I2CConnectionMock mock; preload(mock);
        Apds9250Full chip(mock);
        chip.onInterrupt(onInt);
        mock.setRegister(0x07, {0x10});
        chip.service();
        mock.setRegister(0x07, {0x00});
        chip.service();
        check_true(g_calls == 1 && g_status == 0x10, "callback_receives_status");
        chip.offInterrupt();
        mock.setRegister(0x07, {0x10});
        chip.service();
        check_true(g_calls == 1, "off_interrupt_stops_callback");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
