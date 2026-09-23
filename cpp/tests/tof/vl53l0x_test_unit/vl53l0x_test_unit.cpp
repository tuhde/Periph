#include <stdio.h>
#include <stdint.h>
#include <map>
#include <utility>
#include <vector>
#include "I2CConnectionMock.h"
#include "VL53L0X.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Page-aware VL53L0X simulator on top of the shared mock. Registers written
// while 0xFF != 0 go to a separate per-page store, so the private-bank tuning
// writes don't clobber page-0 registers. Starting a ranging (or calibration)
// raises RESULT_INTERRUPT_STATUS; the interrupt clear drops it unless
// continuous mode is active. The SPAD-info handshake (page 7, 0x83) completes
// immediately.
class VL53L0XSim : public I2CConnectionMock {
public:
    std::map<uint8_t, uint8_t> regs;
    std::map<std::pair<uint8_t, uint8_t>, uint8_t> pages;
    std::vector<std::pair<uint8_t, std::vector<uint8_t>>> log;
    uint8_t page = 0;
    bool continuous = false;

    VL53L0XSim() {
        set(0xC0, { 0xEE, 0xAA, 0x10 });
        set(0x89, { 0x00 });
        set(0x60, { 0x00 });
        set(0x84, { 0x11 });
        set(0xB0, { 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF });
        set(0xF8, { 0x00, 0x10 });
        // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
        set(0x14, { 11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA });
        pages[{ 1, 0x91 }] = 0x3C;
        pages[{ 7, 0x92 }] = 0x85;
    }

    void set(uint8_t reg, std::initializer_list<uint8_t> values) {
        for (uint8_t v : values) regs[reg++] = v;
    }

    uint16_t reg16(uint8_t reg) { return (uint16_t)((regs[reg] << 8) | regs[(uint8_t)(reg + 1)]); }

    std::vector<std::vector<uint8_t>> page0Writes(uint8_t reg) const {
        std::vector<std::vector<uint8_t>> out;
        for (const auto& e : log) if (e.first == 0 && e.second.size() >= 2 && e.second[0] == reg) out.push_back(e.second);
        return out;
    }

    bool logged(std::vector<uint8_t> w) const {
        for (const auto& e : log) if (e.second == w) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        uint8_t reg = data[0];
        if (reg == 0xFF) page = data[1];
        log.emplace_back(page, std::vector<uint8_t>(data, data + len));
        if (page != 0 && reg != 0xFF) {
            for (size_t i = 1; i < len; i++) pages[{ page, (uint8_t)(reg + i - 1) }] = data[i];
            if (page == 7 && reg == 0x83 && data[1] == 0x00) pages[{ 7, 0x83 }] = 0x01;
            return;
        }
        for (size_t i = 1; i < len; i++) regs[(uint8_t)(reg + i - 1)] = data[i];
        if (reg == 0x00 && len == 2) {
            uint8_t value = data[1];
            if (value & 0x06) {
                continuous = true;
                regs[0x13] = 0x04;
            } else if (value & 0x01) {
                if (continuous) continuous = false;
                else regs[0x13] = 0x04;
            }
            regs[0x00] = 0x00;
        } else if (reg == 0x0B && data[1] == 0x01 && !continuous) {
            regs[0x13] = 0x00;
        }
    }

    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t buf_len) override {
        for (size_t i = 0; i < buf_len; i++) {
            uint8_t r = (uint8_t)(data[0] + i);
            if (page != 0) {
                auto it = pages.find({ page, r });
                buf[i] = it == pages.end() ? 0 : it->second;
            } else {
                auto it = regs.find(r);
                buf[i] = it == regs.end() ? 0 : it->second;
            }
        }
    }
};

using VT = VL53L0XFull::VcselPeriodType;

static bool near(uint32_t a, uint32_t b, uint32_t tol) { return (a > b ? a - b : b - a) < tol; }

int main() {
    // --- Initialization -------------------------------------------------------
    VL53L0XSim mock;
    VL53L0XMinimal sensor(mock);
    check_true((mock.regs[0x89] & 0x01) == 0x01, "init_2v8_mode");
    check_true(mock.regs[0x88] == 0x00, "init_i2c_standard_mode");
    check_true(mock.page0Writes(0x60)[0] == std::vector<uint8_t>({ 0x60, 0x12 }), "init_signal_checks_disabled");
    check_true(mock.page0Writes(0x44)[0] == std::vector<uint8_t>({ 0x44, 0x00, 0x20 }), "init_signal_rate_limit");
    check_true(mock.regs[0xB0] == 0x00 && mock.regs[0xB1] == 0xF0 && mock.regs[0xB2] == 0x01 &&
               mock.regs[0xB3] == 0x00 && mock.regs[0xB4] == 0x00 && mock.regs[0xB5] == 0x00,
               "init_spad_map_aperture_5");
    check_true(mock.regs[0xB6] == 0xB4, "init_ref_en_start_select");
    check_true(mock.pages[{ 1, 0x4E }] == 0x2C && mock.pages[{ 1, 0x4F }] == 0x00, "init_dynamic_spad_page1");
    check_true(mock.regs[0x46] == 0x25 && mock.regs[0x70] == 0x04, "init_tuning_page0");
    check_true(mock.pages[{ 1, 0x46 }] == 0x05, "init_tuning_page1");
    check_true(mock.regs[0x0A] == 0x04, "init_gpio_new_sample");
    check_true(mock.regs[0x84] == 0x01, "init_gpio_active_low");
    check_true(mock.regs[0x01] == 0xE8, "init_sequence_config");
    auto starts = mock.page0Writes(0x00);
    size_t n = starts.size();
    check_true(n >= 4 && starts[n - 4][1] == 0x41 && starts[n - 3][1] == 0x00 &&
               starts[n - 2][1] == 0x01 && starts[n - 1][1] == 0x00, "init_vhv_then_phase_calibration");
    check_true(mock.page == 0, "init_returns_to_page0");
    auto seq = mock.page0Writes(0x01);
    size_t s = seq.size();
    check_true(s >= 4 && seq[s - 4][1] == 0xE8 && seq[s - 3][1] == 0x01 &&
               seq[s - 2][1] == 0x02 && seq[s - 1][1] == 0xE8, "init_sequence_order");

    // --- Single-shot ranging --------------------------------------------------
    mock.log.clear();
    check_true(sensor.distance() == 250, "distance_mm");
    check_true(sensor.rangeValid(), "range_valid");
    const std::vector<std::vector<uint8_t>> preamble = {
        { 0x80, 0x01 }, { 0xFF, 0x01 }, { 0x00, 0x00 }, { 0x91, 0x3C },
        { 0x00, 0x01 }, { 0xFF, 0x00 }, { 0x80, 0x00 } };
    bool preOk = mock.log.size() >= 8;
    for (size_t i = 0; preOk && i < 7; i++) preOk = mock.log[i].second == preamble[i];
    check_true(preOk, "distance_stop_variable_preamble");
    check_true(mock.log[7].second == std::vector<uint8_t>({ 0x00, 0x01 }), "distance_start");
    check_true(mock.log.back().second == std::vector<uint8_t>({ 0x0B, 0x01 }), "distance_clears_interrupt");

    mock.set(0x14, { 4 << 3 });
    mock.set(0x1E, { 0x1F, 0xFF });
    check_true(sensor.distance() == 8191, "distance_out_of_range_raw");
    check_true(!sensor.rangeValid(), "range_invalid");

    // --- Full: measurement record ---------------------------------------------
    VL53L0XSim fm;
    VL53L0XFull full(fm);
    full.distance();
    VL53L0XFull::Measurement m = full.readMeasurement();
    check_true(m.distanceMm == 250, "measurement_distance");
    check_true(m.rangeStatus == 11 && full.rangeStatus() == 11, "measurement_status");
    check_true(m.signalRateMcps == 5.0f, "measurement_signal_rate");
    check_true(m.ambientRateMcps == 0.5f, "measurement_ambient_rate");
    check_true(m.effectiveSpadCount == 10.0f, "measurement_spads");

    // --- Continuous ranging ---------------------------------------------------
    fm.log.clear();
    full.startContinuous();
    check_true(fm.log.back().second == std::vector<uint8_t>({ 0x00, 0x02 }), "continuous_back_to_back");
    check_true(fm.logged({ 0x91, 0x3C }), "continuous_stop_variable");
    check_true(full.dataReady(), "data_ready");
    check_true(full.readContinuous() == 250, "read_continuous");
    full.stopContinuous();
    const std::vector<std::vector<uint8_t>> stop = {
        { 0x00, 0x01 }, { 0xFF, 0x01 }, { 0x00, 0x00 }, { 0x91, 0x00 }, { 0x00, 0x01 }, { 0xFF, 0x00 } };
    bool stopOk = fm.log.size() >= 6;
    for (size_t i = 0; stopOk && i < 6; i++) stopOk = fm.log[fm.log.size() - 6 + i].second == stop[i];
    check_true(stopOk, "stop_continuous_sequence");
    full.startContinuous(100);
    check_true(fm.regs[0x04] == 0x00 && fm.regs[0x05] == 0x00 && fm.regs[0x06] == 0x06 && fm.regs[0x07] == 0x40,
               "timed_period");
    check_true(fm.log.back().second == std::vector<uint8_t>({ 0x00, 0x04 }), "timed_start");
    full.stopContinuous();

    // --- Timing budget --------------------------------------------------------
    uint32_t budget = full.timingBudget();
    check_true(budget >= 32000 && budget <= 34000, "default_budget_about_33ms");
    check_true(full.setTimingBudget(50000) && near(full.timingBudget(), 50000, 50), "budget_roundtrip");
    check_true(!full.setTimingBudget(19999), "budget_rejects_below_min");

    // --- Signal rate ----------------------------------------------------------
    full.setSignalRateLimit(0.1f);
    check_true(fm.reg16(0x44) == 13, "signal_rate_encode");
    check_true(full.signalRateLimit() == 13.0f / 128.0f, "signal_rate_decode");
    check_true(!full.setSignalRateLimit(-1.0f), "signal_rate_rejects_negative");

    // --- VCSEL periods --------------------------------------------------------
    check_true(full.vcselPulsePeriod(VT::PreRange) == 14, "vcsel_pre_default");
    check_true(full.vcselPulsePeriod(VT::FinalRange) == 10, "vcsel_final_default");
    full.setVcselPulsePeriod(VT::PreRange, 18);
    check_true(full.vcselPulsePeriod(VT::PreRange) == 18 && fm.regs[0x57] == 0x50 && fm.regs[0x56] == 0x08,
               "vcsel_pre_18");
    full.setVcselPulsePeriod(VT::FinalRange, 14);
    check_true(full.vcselPulsePeriod(VT::FinalRange) == 14 && fm.regs[0x48] == 0x48 && fm.regs[0x32] == 0x03 &&
               fm.regs[0x30] == 0x07 && fm.pages[{ 1, 0x30 }] == 0x20, "vcsel_final_14");
    check_true(near(full.timingBudget(), 50000, 300), "vcsel_keeps_budget");
    check_true(fm.regs[0x01] == 0xE8, "vcsel_restores_sequence");
    check_true(!full.setVcselPulsePeriod(VT::PreRange, 13), "vcsel_rejects_odd");

    full.setProfile(VL53L0XFull::Profile::HighSpeed);
    check_true(full.vcselPulsePeriod(VT::PreRange) == 14 && full.vcselPulsePeriod(VT::FinalRange) == 10 &&
               near(full.timingBudget(), 20000, 50) && fm.reg16(0x44) == 32, "profile_high_speed");
    full.setProfile(VL53L0XFull::Profile::LongRange);
    check_true(full.vcselPulsePeriod(VT::PreRange) == 18 && full.vcselPulsePeriod(VT::FinalRange) == 14 &&
               fm.reg16(0x44) == 13, "profile_long_range");

    // --- Offset and crosstalk -------------------------------------------------
    full.setOffset(-10.25f);
    check_true(fm.reg16(0x28) == ((-41) & 0x0FFF), "offset_encode");
    check_true(full.offset() == -10.25f, "offset_decode");
    full.setOffset(12.5f);
    check_true(full.offset() == 12.5f, "offset_positive");
    check_true(!full.setOffset(512.0f), "offset_rejects_range");
    full.setCrosstalkCompensation(0.5f);
    check_true(fm.reg16(0x20) == 4096, "crosstalk_encode");
    full.setCrosstalkCompensation(0.0f);
    check_true(fm.reg16(0x20) == 0, "crosstalk_off");
    check_true(!full.setCrosstalkCompensation(8.0f), "crosstalk_rejects_range");

    // --- Recalibrate ----------------------------------------------------------
    fm.log.clear();
    check_true(full.recalibrate() && fm.logged({ 0x00, 0x41 }) && fm.logged({ 0x01, 0x02 }) &&
               fm.regs[0x01] == 0xE8, "recalibrate_vhv_and_phase");

    // --- Thresholds, address, identification ----------------------------------
    full.setInterruptThresholds(100, 801);
    check_true(fm.reg16(0x0E) == 50 && fm.reg16(0x0C) == 400, "thresholds_encode");
    uint16_t lo = 0, hi = 0;
    full.interruptThresholds(lo, hi);
    check_true(lo == 100 && hi == 800, "thresholds_decode");
    check_true(!full.setInterruptThresholds(500, 100), "thresholds_reject_order");
    full.setAddress(0x30);
    check_true(fm.regs[0x8A] == 0x30, "set_address");
    check_true(!full.setAddress(0x78), "address_rejects_range");
    check_true(full.modelId() == 0xEE, "model_id");
    check_true(full.revisionId() == 0x10, "revision_id");

    // --- Interrupt API --------------------------------------------------------
    full.enableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);
    check_true(fm.regs[0x0A] == 0x03, "enable_interrupt");
    full.disableInterrupt(VL53L0XFull::SOURCE_LEVEL_LOW);
    check_true(fm.regs[0x0A] == 0x03, "disable_inactive_source_ignored");
    full.disableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);
    check_true(fm.regs[0x0A] == 0x00, "disable_active_source");
    full.enableInterrupt(VL53L0XFull::SOURCE_NEW_SAMPLE_READY);
    fm.regs[0x13] = 0x03 | 0x08;
    check_true(full.pollInterrupt() == VL53L0XFull::SOURCE_OUT_OF_WINDOW, "poll_interrupt_value");
    check_true(fm.regs[0x13] == 0x00, "poll_interrupt_clears");
    check_true(full.pollInterrupt() == 0, "poll_interrupt_none");
    check_true(!full.enableInterrupt(5), "enable_rejects_range");

    printf("Passed: %d, Failed: %d\n", passed, failed);
    printf("===DONE===\n");
    return failed == 0 ? 0 : 1;
}
