#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "MAX44009.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol = 1e-3f) { return fabsf(a - b) < tol; }

static void preload(I2CConnectionMock& m, uint8_t high = 0, uint8_t low = 0) {
    m.setRegister(0x02, {0x03});
    m.setRegister(0x03, {high});
    m.setRegister(0x04, {low});
}

// A mock whose LUX_HIGH changes between the first and second read (a new conversion arrives).
class FlipMock : public I2CConnectionMock {
public:
    int highReads = 0;
protected:
    void _write_read(const uint8_t* data, size_t dlen, uint8_t* buf, size_t blen) override {
        if (dlen > 0 && data[0] == 0x03) {
            highReads++;
            if (highReads == 2) { setRegister(0x03, {0x20}); setRegister(0x04, {0x02}); }
        }
        I2CConnectionMock::_write_read(data, dlen, buf, blen);
    }
};

static int g_status = 0;
static int g_calls = 0;
static void onInt(uint8_t status) { g_status = status; g_calls++; }

int main() {
    MAX44009Minimal::initWaitMs = 0;   // skip the 900 ms first-conversion wait

    // Init sequence: reset state written, INT_STATUS read once.
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Full chip(mock);
        std::vector<std::pair<uint8_t, uint8_t>> seq;
        bool readStatus = false;
        for (const auto& w : mock.writes()) {
            if (w.size() > 1) seq.push_back({w[0], w[1]});
            else if (w.size() == 1 && w[0] == 0x00) readStatus = true;
        }
        std::vector<std::pair<uint8_t, uint8_t>> want = {{0x01, 0x00}, {0x02, 0x03}, {0x05, 0xFF}, {0x06, 0x00}, {0x07, 0xFF}};
        check_true(seq == want, "init_sequence");
        check_true(readStatus, "init_reads_int_status");
    }
    // Lux conversion
    {
        I2CConnectionMock mock; preload(mock, 0x00, 0x00);
        MAX44009Full chip(mock);
        check_true(chip.lux() == 0.0f, "lux_zero");
        mock.setRegister(0x03, {0x2A}); mock.setRegister(0x04, {0x05});
        check_true(near(chip.lux(), 4 * 0xA5 * 0.045f), "lux_exponent_mantissa");
        mock.setRegister(0x03, {0xEF}); mock.setRegister(0x04, {0x0F});
        check_true(near(chip.lux(), 188006.4f, 0.5f), "lux_max_valid");
        mock.setRegister(0x03, {0xF3});
        check_true(near(chip.lux(), 188006.4f, 0.5f), "lux_overrange_returns_max");
        mock.setRegister(0x03, {0x01}); mock.setRegister(0x04, {0xF7});
        check_true(near(chip.lux(), 0x17 * 0.045f), "lux_low_nibble_only");
    }
    // Raw / overrange / resolution
    {
        I2CConnectionMock mock; preload(mock, 0x5B, 0x0C);
        MAX44009Full chip(mock);
        uint8_t e = 0, m = 0;
        bool over = chip.readRaw(e, m);
        check_true(e == 5 && m == 0xBC && !over, "read_raw");
        check_true(near(chip.resolution(), 32 * 0.045f), "resolution");
        check_true(!chip.isOverrange(), "is_overrange_false");
        mock.setRegister(0x03, {0xF0});
        over = chip.readRaw(e, m);
        check_true(over && chip.isOverrange(), "read_raw_overrange");
    }
    // The consistent read repeats once when the high byte changes.
    {
        FlipMock flip;
        flip.setRegister(0x02, {0x03}); flip.setRegister(0x03, {0x10}); flip.setRegister(0x04, {0x01});
        MAX44009Minimal chip(flip);
        flip.highReads = 0;
        float v = chip.lux();
        check_true(near(v, 4 * 0x02 * 0.045f) && flip.highReads == 2, "lux_repeats_on_high_byte_change");
    }
    // Configuration
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Full chip(mock);
        const auto& r = mock.registers();
        chip.setContinuous(true);
        check_true(r.at(0x02) == 0x83, "set_continuous");
        chip.setContinuous(false);
        check_true(r.at(0x02) == 0x03, "set_continuous_off");
        chip.setManual(50.0f, false);
        check_true(r.at(0x02) == 0x44, "set_manual_50ms");
        chip.setManual(12.0f, true);
        check_true(r.at(0x02) == (0x40 | 0x08 | 0x06), "set_manual_nearest_12_5ms_divide");
        chip.setContinuous(true);
        chip.setManual(1000.0f, false);
        check_true(r.at(0x02) == 0xC0, "set_manual_keeps_continuous");
        chip.setAutomatic();
        check_true((r.at(0x02) & 0x40) == 0 && (r.at(0x02) & 0x80), "set_automatic");
        mock.setRegister(0x02, {0xCD});
        MAX44009Full::Config c = chip.getConfig();
        check_true(c.continuous && c.manual && c.divideBy8 && near(c.integrationMs, 25.0f), "get_config");
    }
    // Thresholds
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Full chip(mock);
        const auto& r = mock.registers();
        check_true(chip.setThresholds(0.0f, 188006.4f) && r.at(0x06) == 0x00 && r.at(0x05) == 0xEF, "threshold_extremes");
        check_true(chip.setThresholds(5.0f, 100.0f), "thresholds_accepted");
        check_true(r.at(0x06) == 0x07, "threshold_low_exponent0");
        check_true((r.at(0x05) >> 4) == 4 && (r.at(0x05) & 0x08), "threshold_high_format");
        float lo = 0, hi = 0;
        chip.getThresholds(lo, hi);
        check_true(fabsf(lo - 5.0f) < 0.5f && fabsf(hi - 100.0f) / 100.0f < 0.13f, "threshold_roundtrip");
        check_true(!chip.setThresholds(10.0f, 5.0f), "threshold_order_checked");
    }
    // Threshold delay
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Full chip(mock);
        const auto& r = mock.registers();
        chip.setThresholdDelay(2.0f);
        check_true(r.at(0x07) == 20 && near(chip.getThresholdDelay(), 2.0f), "delay_encoding");
        chip.setThresholdDelay(99.0f);
        check_true(r.at(0x07) == 255, "delay_clamps_high");
        chip.setThresholdDelay(-1.0f);
        check_true(r.at(0x07) == 0, "delay_clamps_low");
    }
    // Interrupt
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Full chip(mock);
        const auto& r = mock.registers();
        chip.enableInterrupt();
        check_true(r.at(0x01) == 0x01, "enable_interrupt");
        chip.disableInterrupt();
        check_true(r.at(0x01) == 0x00, "disable_interrupt");
        mock.setRegister(0x00, {0x01});
        check_true(chip.pollInterrupt() == 1, "poll_interrupt");
        mock.setRegister(0x00, {0x00});
        check_true(chip.pollInterrupt() == 0, "poll_interrupt_clear");

        chip.onInterrupt(onInt);
        mock.setRegister(0x00, {0x01});
        chip.service();
        check_true(g_calls == 1 && g_status == 1, "service_dispatches_callback");
        mock.setRegister(0x00, {0x00});
        chip.service();
        check_true(g_calls == 1, "service_no_event_no_callback");
        chip.offInterrupt();
        mock.setRegister(0x00, {0x01});
        chip.service();
        check_true(g_calls == 1, "off_interrupt_stops_delivery");
    }
    // Minimal constructible
    {
        I2CConnectionMock mock; preload(mock);
        MAX44009Minimal chip(mock);
        check_true(chip.lux() == 0.0f, "minimal_constructible");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
