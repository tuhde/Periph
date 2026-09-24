#include <stdio.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "VL53L1X.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// VL53L1X simulator with explicit 16-bit register indices. GPIO__TIO_HV_STATUS
// (0x0031) is computed: bit 0 is 0 (active-low line asserted) while a result is
// pending. Starting a single-shot or timed ranging makes a result pending; the
// interrupt clear drops it unless timed ranging is running.
class VL53L1XSim : public I2CConnectionMock {
public:
    std::map<uint16_t, uint8_t> regs;
    std::vector<std::vector<uint8_t>> log;
    bool pending = false;
    bool ranging = false;

    VL53L1XSim() {
        set(0x00E5, { 0x01 });
        set(0x010F, { 0xEA, 0xCC, 0x10 });
        set(0x013E, { 0x91 });
        set(0x00DE, { 0x00, 0x50 });
        // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
        set(0x0089, { 9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80 });
    }

    void set(uint16_t reg, std::initializer_list<uint8_t> values) {
        for (uint8_t v : values) regs[reg++] = v;
    }

    uint16_t reg16(uint16_t reg) { return (uint16_t)((regs[reg] << 8) | regs[(uint16_t)(reg + 1)]); }

    std::vector<uint8_t> writesTo(uint16_t reg) const {
        std::vector<uint8_t> out;
        for (const auto& w : log)
            if (w.size() == 3 && (uint16_t)((w[0] << 8) | w[1]) == reg) out.push_back(w[2]);
        return out;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        uint16_t reg = (uint16_t)((data[0] << 8) | data[1]);
        for (size_t i = 2; i < len; i++) regs[(uint16_t)(reg + i - 2)] = data[i];
        if (reg == 0x0087 && len == 3) {
            if (data[2] == 0x10 || data[2] == 0x40) {
                pending = true;
                ranging = data[2] == 0x40;
            } else {
                ranging = false;
            }
        } else if (reg == 0x0086 && data[2] == 0x01) {
            pending = ranging;
        }
    }

    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t buf_len) override {
        uint16_t reg = (uint16_t)((data[0] << 8) | data[1]);
        for (size_t i = 0; i < buf_len; i++) {
            uint16_t r = (uint16_t)(reg + i);
            if (r == 0x0031) buf[i] = pending ? 0x00 : 0x01;
            else {
                auto it = regs.find(r);
                buf[i] = it == regs.end() ? 0 : it->second;
            }
        }
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    bool onEdge(Handler h, uint8_t = kFalling) override { handler = h; return true; }
    void offEdge(Handler) override { handler = nullptr; }
};

static std::vector<uint8_t> g_got;
static void onStatus(uint8_t status) { g_got.push_back(status); }

using DM = VL53L1XFull::DistanceMode;

int main() {
    // --- Initialization -------------------------------------------------------
    VL53L1XSim mock;
    VL53L1XMinimal sensor(mock);
    check_true(mock.log[0][0] == 0x00 && mock.log[0][1] == 0x2D, "init_16bit_index");
    bool blockOk = true;
    for (int i = 0; i < 91; i++) {
        const auto& w = mock.log[i];
        blockOk = blockOk && w.size() == 3 && (uint16_t)((w[0] << 8) | w[1]) == 0x2D + i;
    }
    check_true(blockOk, "init_config_block_byte_by_byte");
    check_true(mock.writesTo(0x0046)[0] == 0x20 && mock.writesTo(0x0081)[0] == 0x9B, "init_config_values");
    check_true(mock.regs[0x002E] == 0x01 && mock.regs[0x002F] == 0x01, "init_2v8_mode");
    check_true(mock.regs[0x0030] == 0x11, "init_gpio_active_low");
    auto starts = mock.writesTo(0x0087);
    check_true(starts.size() >= 2 && starts[starts.size() - 2] == 0x40 && starts.back() == 0x00, "init_settling_ranging");
    check_true(mock.regs[0x0008] == 0x09 && mock.regs[0x000B] == 0x00, "init_vhv_bounds");
    check_true(!mock.ranging, "init_leaves_idle");

    // --- Single-shot ranging --------------------------------------------------
    mock.log.clear();
    check_true(sensor.distance() == 250, "distance_mm");
    check_true(sensor.rangeValid(), "range_valid");
    check_true(mock.log[0] == std::vector<uint8_t>({ 0x00, 0x86, 0x01 }) &&
               mock.log[1] == std::vector<uint8_t>({ 0x00, 0x87, 0x10 }), "distance_sequence");
    check_true(mock.log.back() == std::vector<uint8_t>({ 0x00, 0x86, 0x01 }), "distance_clears_interrupt");
    mock.regs[0x0089] = 4;
    check_true(sensor.distance() == 250 && !sensor.rangeValid(), "distance_invalid_raw");

    // --- Full: measurement record ---------------------------------------------
    VL53L1XSim fm;
    VL53L1XFull full(fm);
    full.distance();
    VL53L1XFull::Measurement m = full.readMeasurement();
    check_true(m.distanceMm == 250, "measurement_distance");
    check_true(m.rangeStatus == 0 && full.rangeStatus() == 0, "measurement_status");
    check_true(m.signalRateMcps == 5.0f, "measurement_signal_rate");
    check_true(m.ambientRateMcps == 0.5f, "measurement_ambient_rate");
    check_true(m.effectiveSpadCount == 10.0f, "measurement_spads");
    fm.regs[0x0089] = 0x1F;
    check_true(full.readMeasurement().rangeStatus == 255, "status_out_of_table");
    fm.regs[0x0089] = 9;

    // --- Timing budget and distance mode --------------------------------------
    check_true(full.timingBudget() == 100000, "default_budget_100ms");
    check_true(full.distanceMode() == DM::Long, "default_mode_long");
    check_true(full.setTimingBudget(33000) && fm.reg16(0x005E) == 0x0060 && fm.reg16(0x0061) == 0x006E, "budget_long_33");
    check_true(full.timingBudget() == 33000, "budget_roundtrip");
    check_true(!full.setTimingBudget(15000), "budget_rejects_15_long");
    check_true(!full.setTimingBudget(40000), "budget_rejects_other");
    check_true(full.setDistanceMode(DM::Short), "mode_short_ok");
    check_true(fm.regs[0x004B] == 0x14 && fm.regs[0x0060] == 0x07 && fm.regs[0x0063] == 0x05 &&
               fm.regs[0x0069] == 0x38 && fm.reg16(0x0078) == 0x0705 && fm.reg16(0x007A) == 0x0606, "mode_short_regs");
    check_true(fm.reg16(0x005E) == 0x00D6 && full.timingBudget() == 33000, "mode_short_keeps_budget");
    check_true(full.setTimingBudget(15000) && fm.reg16(0x005E) == 0x001D && fm.reg16(0x0061) == 0x0027, "budget_short_15");
    check_true(!full.setDistanceMode(DM::Long), "mode_long_rejects_15ms");
    full.setTimingBudget(100000);
    full.setDistanceMode(DM::Long);
    check_true(fm.regs[0x004B] == 0x0A && fm.reg16(0x0078) == 0x0F0D && fm.reg16(0x005E) == 0x01CC &&
               fm.reg16(0x0061) == 0x01EA, "mode_long_regs");
    check_true(!full.setDistanceMode(DM::Unknown), "mode_rejects_unknown");

    // --- Inter-measurement and continuous ranging -----------------------------
    full.setInterMeasurement(200);
    check_true(((uint32_t)fm.reg16(0x006C) << 16 | fm.reg16(0x006E)) == (0x50u * 200 * 1075) / 1000,
               "inter_measurement_encode");
    check_true(full.interMeasurement() == 200, "inter_measurement_roundtrip");
    check_true(!full.setInterMeasurement(0), "inter_measurement_rejects_zero");
    fm.log.clear();
    full.startContinuous();
    check_true(full.interMeasurement() == 100, "continuous_period_is_budget");
    check_true(fm.log.back() == std::vector<uint8_t>({ 0x00, 0x87, 0x40 }), "continuous_start");
    check_true(full.dataReady(), "data_ready");
    check_true(full.readContinuous() == 250, "read_continuous");
    check_true(full.dataReady(), "continuous_stays_ready");
    full.stopContinuous();
    check_true(fm.log.back() == std::vector<uint8_t>({ 0x00, 0x87, 0x00 }), "stop_continuous");
    full.startContinuous(50);
    check_true(full.interMeasurement() == 100, "continuous_period_clamped");
    full.stopContinuous();
    full.startContinuous(500);
    check_true(full.interMeasurement() == 500, "continuous_period_kept");
    full.stopContinuous();
    check_true(!full.startContinuous(60001), "continuous_rejects_range");

    // --- Signal / sigma -------------------------------------------------------
    check_true(full.signalRateLimit() == 1.0f, "signal_rate_default");
    full.setSignalRateLimit(0.25f);
    check_true(fm.reg16(0x0066) == 32, "signal_rate_encode");
    check_true(!full.setSignalRateLimit(-1.0f), "signal_rate_rejects_negative");
    check_true(full.sigmaThreshold() == 90, "sigma_default");
    full.setSigmaThreshold(45);
    check_true(fm.reg16(0x0064) == 180 && full.sigmaThreshold() == 45, "sigma_encode");
    check_true(!full.setSigmaThreshold(16384), "sigma_rejects_range");

    // --- ROI ------------------------------------------------------------------
    uint8_t w = 0, h = 0;
    full.roi(w, h);
    check_true(w == 16 && h == 16 && full.roiCenter() == 199, "roi_default");
    full.setRoiCenter(167);
    full.setRoi(8, 8);
    check_true(fm.regs[0x0080] == 0x77 && full.roiCenter() == 167, "roi_small_keeps_center");
    full.setRoi(8, 16);
    full.roi(w, h);
    check_true(w == 8 && h == 16 && full.roiCenter() == 199, "roi_large_recenters");
    check_true(!full.setRoi(3, 8), "roi_rejects_small");
    check_true(full.opticalCenter() == 0x91, "optical_center");

    // --- Offset and crosstalk -------------------------------------------------
    full.setOffset(-10.25f);
    check_true(fm.reg16(0x001E) == ((-41) & 0x1FFF) && fm.reg16(0x0020) == 0 && fm.reg16(0x0022) == 0, "offset_encode");
    check_true(full.offset() == -10.25f, "offset_decode");
    full.setOffset(700.5f);
    check_true(full.offset() == 700.5f, "offset_positive");
    check_true(!full.setOffset(1024.0f), "offset_rejects_range");
    full.setCrosstalkCompensation(0.01f);
    check_true(fm.reg16(0x0016) == 5120 && fm.reg16(0x0018) == 0 && fm.reg16(0x001A) == 0, "crosstalk_encode");
    check_true(full.crosstalkCompensation() == 0.01f, "crosstalk_decode");
    check_true(!full.setCrosstalkCompensation(0.128f), "crosstalk_rejects_range");

    // --- Calibration ----------------------------------------------------------
    float off = 0.0f;
    check_true(full.calibrateOffset(260, off) && off == 10.0f && full.offset() == 10.0f, "calibrate_offset_value");
    check_true(!fm.ranging, "calibrate_offset_stops");
    float xt = 0.0f;
    full.calibrateCrosstalk(500, xt);
    check_true(xt == 0.127f, "calibrate_crosstalk_clamped");
    fm.set(0x0098, { 0x00, 0x20 });
    full.calibrateCrosstalk(500, xt);
    check_true(xt > 0.01249f && xt < 0.01251f && fm.reg16(0x0016) == 6400, "calibrate_crosstalk_value");
    check_true(!full.calibrateCrosstalk(0, xt), "calibrate_crosstalk_rejects_zero");

    // --- Temperature update ---------------------------------------------------
    fm.log.clear();
    check_true(full.recalibrate(), "recalibrate_ok");
    check_true(fm.writesTo(0x0008) == std::vector<uint8_t>({ 0x81, 0x09 }) &&
               fm.writesTo(0x000B) == std::vector<uint8_t>({ 0x92, 0x00 }) &&
               fm.writesTo(0x0087) == std::vector<uint8_t>({ 0x40, 0x00 }), "recalibrate_sequence");

    // --- Thresholds, address, identification ----------------------------------
    full.setInterruptThresholds(100, 801);
    check_true(fm.reg16(0x0074) == 100 && fm.reg16(0x0072) == 801, "thresholds_encode");
    uint16_t lo = 0, hi = 0;
    full.interruptThresholds(lo, hi);
    check_true(lo == 100 && hi == 801, "thresholds_decode");
    check_true(!full.setInterruptThresholds(500, 100), "thresholds_reject_order");
    full.setAddress(0x30);
    check_true(fm.regs[0x0001] == 0x30, "set_address");
    check_true(!full.setAddress(0x78), "address_rejects_range");
    check_true(full.modelId() == 0xEA, "model_id");
    check_true(full.moduleType() == 0xCC, "module_type");
    check_true(full.revisionId() == 0x10, "revision_id");

    // --- Interrupt API --------------------------------------------------------
    full.enableInterrupt(VL53L1XFull::SOURCE_OUT_OF_WINDOW);
    check_true(fm.regs[0x0046] == 0x02, "enable_out_of_window");
    full.enableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);
    check_true(fm.regs[0x0046] == 0x03, "enable_in_window");
    full.disableInterrupt(VL53L1XFull::SOURCE_LEVEL_LOW);
    check_true(fm.regs[0x0046] == 0x03, "disable_inactive_source_ignored");
    full.disableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);
    check_true(fm.regs[0x0046] == 0x20, "disable_reverts_to_new_sample");
    full.disableInterrupt(VL53L1XFull::SOURCE_NEW_SAMPLE_READY);
    check_true(fm.regs[0x0046] == 0x20, "disable_new_sample_noop");
    fm.pending = false;
    check_true(full.pollInterrupt() == 0, "poll_interrupt_none");
    full.enableInterrupt(VL53L1XFull::SOURCE_OUT_OF_WINDOW);
    fm.pending = true;
    check_true(full.pollInterrupt() == VL53L1XFull::SOURCE_OUT_OF_WINDOW, "poll_interrupt_value");
    check_true(!fm.pending, "poll_interrupt_clears");
    full.enableInterrupt(VL53L1XFull::SOURCE_NEW_SAMPLE_READY);
    fm.pending = true;
    FakePin pin;
    full.onInterrupt(onStatus, &pin);
    if (pin.handler) pin.handler();
    full.offInterrupt();
    check_true(g_got == std::vector<uint8_t>({ VL53L1XFull::SOURCE_NEW_SAMPLE_READY }) && pin.handler == nullptr,
               "on_interrupt_callback");
    check_true(!full.enableInterrupt(6), "enable_rejects_range");

    printf("Passed: %d, Failed: %d\n", passed, failed);
    printf("===DONE===\n");
    return failed == 0 ? 0 : 1;
}
