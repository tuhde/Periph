#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "TSL2561.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float rel = 1e-4f) { return fabsf(a - b) <= rel * fmaxf(1.0f, fabsf(b)); }

static uint8_t reg(const I2CConnectionMock& m, uint16_t r) {
    auto it = m.registers().find(r);
    return it == m.registers().end() ? 0 : it->second;
}

static void setCounts(I2CConnectionMock& m, uint16_t ch0, uint16_t ch1) {
    m.setRegister(0x9C, {(uint8_t)(ch0 & 0xFF), (uint8_t)(ch0 >> 8), (uint8_t)(ch1 & 0xFF), (uint8_t)(ch1 >> 8)});
}

static bool wrote(const I2CConnectionMock& m, std::vector<uint8_t> w) {
    for (const auto& x : m.writes()) if (x == w) return true;
    return false;
}

// Independent reference of the datasheet formula (T/FN/CL or CS).
static float ref(float ch0, float ch1, float gain, float scale, bool cs) {
    static const float T[4][3] = {{0.50f, 0.0304f, 0.062f}, {0.61f, 0.0224f, 0.031f}, {0.80f, 0.0128f, 0.0153f}, {1.30f, 0.00146f, 0.00112f}};
    static const float C[4][3] = {{0.52f, 0.0315f, 0.0593f}, {0.65f, 0.0229f, 0.0291f}, {0.80f, 0.0157f, 0.0180f}, {1.30f, 0.00338f, 0.00260f}};
    const float (*k)[3] = cs ? C : T;
    float c0 = ch0 * (16.0f / gain) / scale, c1 = ch1 * (16.0f / gain) / scale, r = c1 / c0;
    if (r <= k[0][0]) return k[0][1] * c0 - k[0][2] * c0 * powf(r, 1.4f);
    for (int i = 1; i < 4; i++) if (r <= k[i][0]) return k[i][1] * c0 - k[i][2] * c1;
    return 0.0f;
}

// Counts follow the TIMING register: the light level is fixed, counts scale with gain and time.
class RangeMock : public I2CConnectionMock {
public:
    explicit RangeMock(float base) : _base(base) {}
protected:
    void _write_read(const uint8_t* data, size_t dlen, uint8_t* buf, size_t blen) override {
        if (dlen > 0 && data[0] == 0x9C) {
            uint8_t t = reg(*this, 0x81);
            float gain = (t & 0x10) ? 16.0f : 1.0f;
            static const float scale[3] = {0.034f, 0.252f, 1.0f};
            static const float limit[3] = {5047, 37177, 65535};
            int i = t & 0x03;
            float c0 = fminf(limit[i], floorf(_base * gain * scale[i]));
            float c1 = fminf(limit[i], floorf(_base * 0.2f * gain * scale[i]));
            setCounts(*this, (uint16_t)c0, (uint16_t)c1);
        }
        I2CConnectionMock::_write_read(data, dlen, buf, blen);
    }
private:
    float _base;
};

static int g_ch0 = -1;
static void onInt(uint16_t ch0) { g_ch0 = ch0; }

int main() {
    TSL2561Minimal::waitPercent = 0;   // skip the integration waits

    // Init sequence
    {
        I2CConnectionMock mock;
        TSL2561Full chip(mock);
        check_true(mock.writes().empty(), "constructor_no_bus_traffic");
        check_true(chip.init(), "init_ok");
        std::vector<std::vector<uint8_t>> data;
        for (const auto& w : mock.writes()) if (w.size() > 1) data.push_back(w);
        check_true(data == std::vector<std::vector<uint8_t>>({{0x80, 0x03}, {0x81, 0x02}}), "init_sequence");
        check_true(wrote(mock, {0x80}), "init_reads_control");
    }
    {
        I2CConnectionMock mock;
        mock.failNextReads(0);
        TSL2561Minimal chip(mock);
        mock.failNextWrites(1);
        check_true(!chip.init() && chip.lastError() == TSL2561Minimal::ERR_BUS, "init_bus_error");
    }
    // Lux formula
    {
        I2CConnectionMock mock;
        TSL2561Full chip(mock);
        chip.init();
        setCounts(mock, 1000, 200);
        float lx = chip.readLux();
        check_true(near(lx, ref(1000, 200, 1, 1.0f, false)) && fabsf(lx - 382.2f) < 0.5f, "read_lux_segment1");
        setCounts(mock, 1000, 550);
        check_true(near(chip.readLux(), ref(1000, 550, 1, 1.0f, false)), "read_lux_segment2");
        setCounts(mock, 1000, 700);
        check_true(near(chip.readLux(), ref(1000, 700, 1, 1.0f, false)), "read_lux_segment3");
        setCounts(mock, 1000, 1000);
        check_true(near(chip.readLux(), ref(1000, 1000, 1, 1.0f, false)), "read_lux_segment4");
        setCounts(mock, 1000, 1400);
        check_true(chip.readLux() == 0.0f, "read_lux_ratio_above_1_30");
        setCounts(mock, 0, 0);
        check_true(chip.readLux() == 0.0f, "read_lux_dark");
        check_true(near(TSL2561Minimal::calculateLux(500, 100, 16, 101), ref(500, 100, 16, 0.252f, false)), "calculate_gain16_101ms");
        check_true(near(TSL2561Minimal::calculateLux(500, 300, 1, 14), ref(500, 300, 1, 0.034f, false)), "calculate_14ms");
        bool csOk = true;
        for (uint16_t c1 : {100, 600, 700, 1000})
            csOk = csOk && near(TSL2561Minimal::calculateLux(1000, c1, 1, 402, TSL2561Minimal::PACKAGE_CS), ref(1000, c1, 1, 1.0f, true));
        check_true(csOk, "calculate_cs_all_segments");
    }
    // Raw block read and saturation
    {
        I2CConnectionMock mock;
        TSL2561Full chip(mock);
        chip.init();
        setCounts(mock, 0x1234, 0x0567);
        uint16_t c0 = 0, c1 = 0;
        check_true(chip.readRaw(c0, c1) && c0 == 0x1234 && c1 == 0x0567, "read_raw");
        check_true(mock.writes().back() == std::vector<uint8_t>({0x9C}), "read_raw_block_cmd");
        float lx = 0;
        check_true(chip.readLuxRaw(lx, c0, c1) && near(lx, ref(0x1234, 0x0567, 1, 1.0f, false)), "read_lux_raw");
        setCounts(mock, 65535, 100);
        check_true(std::isnan(chip.readLux()) && chip.lastError() == TSL2561Minimal::ERR_SATURATED, "saturated_nan");
        chip.setIntegrationTime(101);
        setCounts(mock, 37177, 10);
        check_true(std::isnan(chip.readLux()), "saturated_101ms");
        setCounts(mock, 37176, 10);
        check_true(chip.readLux() > 0 && chip.lastError() == TSL2561Minimal::ERR_NONE, "below_limit_ok");
    }
    // Gain / integration time / package / manual
    {
        I2CConnectionMock mock;
        TSL2561Full chip(mock);
        chip.init();
        chip.setGain(16);
        check_true(reg(mock, 0x81) == 0x12 && chip.getGain() == 16, "set_gain_16");
        chip.setIntegrationTime(14);
        check_true(reg(mock, 0x81) == 0x10 && chip.getIntegrationTime() == 14, "set_integration_14");
        chip.setGain(1);
        chip.setIntegrationTime(101);
        check_true(reg(mock, 0x81) == 0x01 && chip.getIntegrationTime() == 101, "set_integration_101");
        check_true(!chip.setGain(4) && chip.lastError() == TSL2561Minimal::ERR_ARG, "set_gain_rejects");
        check_true(!chip.setIntegrationTime(50) && chip.lastError() == TSL2561Minimal::ERR_ARG, "set_integration_rejects");
        chip.setIntegrationTime(402);
        setCounts(mock, 1000, 100);
        chip.setPackage(TSL2561Minimal::PACKAGE_CS);
        check_true(near(chip.readLux(), ref(1000, 100, 1, 1.0f, true)), "set_package_cs");
        chip.setGain(16);
        chip.startManual();
        check_true(reg(mock, 0x81) == 0x1B, "start_manual");
        chip.stopManual();
        check_true(reg(mock, 0x81) == 0x13 && chip.getIntegrationTime() == 0, "stop_manual");
    }
    // Auto-range
    {
        RangeMock dim(20);
        TSL2561Full chip(dim);
        chip.init();
        float lx = chip.autoRange();
        check_true(chip.getGain() == 16 && chip.getIntegrationTime() == 402 && lx > 0, "auto_range_dim_16x");
        RangeMock bright(148000);
        TSL2561Full chip2(bright);
        chip2.init();
        lx = chip2.autoRange();
        check_true(chip2.getGain() == 1 && chip2.getIntegrationTime() == 14 && lx > 0, "auto_range_bright_14ms");
        RangeMock mid(5000);
        TSL2561Full chip3(mid);
        chip3.init();
        lx = chip3.autoRange();
        check_true(chip3.getGain() == 1 && chip3.getIntegrationTime() == 402, "auto_range_mid_keeps_1x_402");
        check_true(near(lx, TSL2561Minimal::calculateLux(5000, 1000), 1e-3f), "auto_range_lux_consistent");
    }
    // Power, thresholds, interrupt
    {
        I2CConnectionMock mock;
        mock.setRegister(0x8A, {0x15});
        TSL2561Full chip(mock);
        chip.init();
        check_true(chip.isPowered(), "is_powered");
        chip.powerDown();
        check_true(reg(mock, 0x80) == 0x00 && !chip.isPowered(), "power_down");
        chip.powerUp();
        check_true(reg(mock, 0x80) == 0x03, "power_up");
        chip.setThresholds(0x0102, 0x0A0B);
        check_true(wrote(mock, {0xA2, 0x02, 0x01}) && wrote(mock, {0xA4, 0x0B, 0x0A}), "set_thresholds_word");
        uint16_t lo = 0, hi = 0;
        check_true(chip.getThresholds(lo, hi) && lo == 0x0102 && hi == 0x0A0B, "get_thresholds");
        chip.configureInterrupt(1, 2);
        check_true(reg(mock, 0x86) == 0x12, "configure_interrupt");
        check_true(!chip.configureInterrupt(3, 0) && !chip.configureInterrupt(1, 16), "configure_interrupt_rejects");
        check_true(chip.readId() == 0x15 && (chip.readId() >> 4) == 1, "read_id");
        chip.setThresholds(100, 1000);
        setCounts(mock, 500, 0);
        check_true(!chip.pollInterrupt(), "poll_inside_window");
        setCounts(mock, 100, 0);
        check_true(chip.pollInterrupt(), "poll_at_low");
        setCounts(mock, 1001, 0);
        check_true(chip.pollInterrupt(), "poll_above_high");
        chip.clearInterrupt();
        check_true(mock.writes().back() == std::vector<uint8_t>({0xC0}), "clear_interrupt");
        chip.onInterrupt(onInt);
        setCounts(mock, 4242, 0);
        chip.service();
        check_true(g_ch0 == 4242, "service_dispatches_ch0");
        g_ch0 = -1;
        setCounts(mock, 500, 0);
        chip.service();
        check_true(g_ch0 == -1, "service_inside_window_silent");
        chip.offInterrupt();
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
