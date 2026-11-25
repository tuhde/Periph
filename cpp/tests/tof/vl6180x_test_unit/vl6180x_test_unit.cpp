#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "VL6180X.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// VL6180X simulator with explicit 16-bit register indices: starting a
// measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
class VL6180XSim : public I2CConnectionMock {
public:
    std::map<uint16_t, uint8_t> regs;
    std::vector<std::vector<uint8_t>> log;

    explicit VL6180XSim(uint8_t fresh = 1) : I2CConnectionMock(2) {
        set(0x000, { 0xB4, 0x01, 0x03 });
        set(0x016, { fresh });
        set(0x062, { 42 });
        set(0x050, { 0x01, 0x90 });   // ALS 400 counts
    }

    void set(uint16_t reg, std::initializer_list<uint8_t> values) {
        for (uint8_t v : values) regs[reg++] = v;
    }

    std::vector<uint8_t> writesTo(uint16_t reg) const {
        std::vector<uint8_t> out;
        for (const auto& w : log)
            if (w.size() == 3 && (uint16_t)((w[0] << 8) | w[1]) == reg) out.push_back(w[2]);
        return out;
    }

    uint8_t lastWrite(uint16_t reg) const {
        auto v = writesTo(reg);
        return v.empty() ? 0xEE : v.back();
    }

    bool wrote(uint16_t reg, uint8_t value) const {
        for (uint8_t v : writesTo(reg)) if (v == value) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        uint16_t reg = (uint16_t)((data[0] << 8) | data[1]);
        for (size_t i = 2; i < len; i++) regs[(uint16_t)(reg + i - 2)] = data[i];
        if (len == 3) {
            if (reg == 0x018 && (data[2] & 1)) regs[0x04F] = (uint8_t)((regs[0x04F] & ~0x07) | 4);
            else if (reg == 0x038 && (data[2] & 1)) regs[0x04F] = (uint8_t)((regs[0x04F] & ~0x38) | (4 << 3));
            else if (reg == 0x015) regs[0x04F] = 0;
            else if (reg == 0x02E) regs[0x02E] = 0;
        }
    }

    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t buf_len) override {
        uint16_t reg = (uint16_t)((data[0] << 8) | data[1]);
        for (size_t i = 0; i < buf_len; i++) {
            auto it = regs.find((uint16_t)(reg + i));
            buf[i] = it == regs.end() ? 0 : it->second;
        }
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    bool onEdge(Handler h, uint8_t = kFalling) override { handler = h; return true; }
    void offEdge(Handler) override { handler = nullptr; }
    bool isHigh() override { return false; }
};

static std::vector<uint8_t> g_got;
static void onStatus(uint8_t status) { g_got.push_back(status); }

int main() {
    // --- Initialization ---
    {
        VL6180XSim sim;
        VL6180XMinimal chip(sim);
        check_true(sim.log.empty(), "ctor_no_bus_access");
        check_true(chip.init(), "init_ok");
        check_true(sim.wrote(0x0207, 0x01), "private_0207");
        check_true(sim.wrote(0x00DB, 0xCE), "private_00DB");
        check_true(sim.lastWrite(0x016) == 0x00, "fresh_flag_cleared");
        check_true(sim.lastWrite(0x011) == 0x10, "gpio1_mode");
        check_true(sim.lastWrite(0x10A) == 0x30, "averaging_period");
        check_true(sim.lastWrite(0x03F) == 0x46, "als_gain_1");
        check_true(sim.lastWrite(0x031) == 0xFF, "vhv_repeat_rate");
        check_true(sim.regs[0x040] == 0x00 && sim.regs[0x041] == 0x63, "integration_100ms");
        check_true(sim.lastWrite(0x014) == 0x24, "interrupt_config");
        check_true(sim.lastWrite(0x01B) == 0x09, "range_period");

        VL6180XSim sim2(0);
        VL6180XMinimal chip2(sim2);
        chip2.init();
        check_true(sim2.writesTo(0x0207).empty(), "no_private_when_not_fresh");
        check_true(sim2.lastWrite(0x014) == 0x24, "recommended_still_applied");

        // --- Single-shot range ---
        uint8_t mm = 0;
        check_true(chip.read_range(mm) && mm == 42, "read_range");
        check_true(sim.wrote(0x018, 0x01), "range_start");
        check_true(sim.lastWrite(0x015) == 0x07, "interrupt_cleared");
        sim.set(0x04D, { 0x70 });
        check_true(!chip.read_range(mm) && chip.last_error() == 7, "range_error_fails");
        sim.set(0x04D, { 0x00 });
    }

    // --- Full ---
    {
        VL6180XSim sim;
        VL6180XFull full(sim);
        full.init();
        uint8_t mm = 0, err = 0;
        sim.set(0x04D, { 0x60 });
        check_true(full.read_range_status(mm, err) && mm == 42 && err == 6, "read_range_status");
        sim.set(0x04D, { 0x00 });

        uint16_t raw = 0;
        check_true(full.read_als_raw(raw) && raw == 400, "read_als_raw");
        check_true(fabsf(full.read_lux() - 0.32f * 400 / 1.01f) < 1e-3f, "lux_gain_1");
        check_true(full.set_als_gain(0), "gain_0");
        check_true(sim.lastWrite(0x03F) == 0x40, "gain_written");
        check_true(full.set_als_integration_time(50), "integration_50");
        check_true(sim.regs[0x040] == 0x00 && sim.regs[0x041] == 0x31, "integration_code");
        check_true(fabsf(full.read_lux() - 0.32f * (400 / 20.0f) * 2) < 1e-3f, "lux_gain_20_50ms");
        auto hold = sim.writesTo(0x017);
        check_true(hold.size() >= 2 && hold[hold.size() - 2] == 1 && hold.back() == 0, "grouped_hold");
        check_true(!full.set_als_gain(8), "bad_gain");
        check_true(!full.set_als_integration_time(513), "bad_integration");
        check_true(full.set_als_scaler(4) && sim.lastWrite(0x120) == 4, "scaler");
        check_true(!full.set_als_scaler(17), "bad_scaler");

        check_true(full.start_continuous_range(50), "start_continuous_range");
        check_true(sim.lastWrite(0x01B) == 0x04 && sim.lastWrite(0x018) == 0x03, "continuous_range_regs");
        sim.regs[0x04F] = 4;
        check_true(full.read_range_continuous(mm) && mm == 42, "read_range_continuous");
        full.stop_continuous_range();
        check_true(sim.lastWrite(0x018) == 0x01, "stop_continuous_range");
        check_true(!full.start_continuous_range(5), "bad_period_low");
        check_true(!full.start_continuous_range(2560), "bad_period_high");

        check_true(full.start_continuous_als(100), "start_continuous_als");
        check_true(sim.lastWrite(0x03E) == 0x09 && sim.lastWrite(0x038) == 0x03, "continuous_als_regs");
        full.stop_continuous_als();
        check_true(sim.lastWrite(0x038) == 0x01, "stop_continuous_als");
        full.set_interleaved(true);
        check_true(sim.lastWrite(0x2A3) == 1, "interleaved_on");
        full.set_interleaved(false);
        check_true(sim.lastWrite(0x2A3) == 0, "interleaved_off");

        check_true(full.set_range_thresholds(30, 80), "range_thresholds");
        check_true(sim.lastWrite(0x019) == 80 && sim.lastWrite(0x01A) == 30, "range_threshold_regs");
        check_true(!full.set_range_thresholds(90, 80), "bad_range_thresholds");
        check_true(full.set_als_thresholds(100, 5000), "als_thresholds");
        check_true(sim.regs[0x03A] == 0x13 && sim.regs[0x03B] == 0x88 && sim.regs[0x03C] == 0 && sim.regs[0x03D] == 100, "als_threshold_regs");
        check_true(full.set_max_convergence_time(20) && sim.lastWrite(0x01C) == 20, "convergence");
        check_true(!full.set_max_convergence_time(64), "bad_convergence");
        full.set_averaging_period(100);
        check_true(sim.lastWrite(0x10A) == 100, "averaging");

        full.set_range_offset(-5);
        check_true(sim.lastWrite(0x024) == 0xFB, "offset_written");
        check_true(full.get_range_offset() == -5, "offset_read");
        check_true(full.set_crosstalk_rate(0.5f), "crosstalk");
        check_true(sim.regs[0x01E] == 0x00 && sim.regs[0x01F] == 0x40, "crosstalk_9_7");
        check_true(!full.set_crosstalk_rate(-1.0f), "bad_crosstalk");
        check_true(full.recalibrate_vhv() && sim.lastWrite(0x02E) == 0x01, "recalibrate_vhv");

        check_true(full.set_address(0x30) && sim.lastWrite(0x212) == 0x30, "address");
        check_true(!full.set_address(0x80), "bad_address");
        check_true(full.model_id() == 0xB4, "model_id");
        uint8_t major = 0, minor = 0;
        full.revision(major, minor);
        check_true(major == 1 && minor == 3, "revision");
        sim.set(0x052, { 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0 });
        uint16_t hist[8];
        full.read_history(hist);
        check_true(hist[0] == 1 && hist[6] == 7 && hist[7] == 256, "history");

        // --- Interrupts ---
        sim.regs[0x014] = 0x24;
        check_true(full.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW) && sim.lastWrite(0x014) == 0x23, "range_mode");
        check_true(full.enable_interrupt(VL6180XFull::SOURCE_ALS, VL6180XFull::MODE_LEVEL_LOW) && sim.lastWrite(0x014) == 0x0B, "als_mode");
        check_true(full.disable_interrupt(VL6180XFull::SOURCE_RANGE) && sim.lastWrite(0x014) == 0x08, "range_disabled");
        check_true(!full.enable_interrupt(VL6180XFull::SOURCE_ERROR, 1), "bad_source");
        check_true(!full.enable_interrupt(VL6180XFull::SOURCE_RANGE, 5), "bad_mode");

        sim.regs[0x04F] = 0x04;
        check_true(full.poll_interrupt() == VL6180XFull::SOURCE_RANGE && sim.regs[0x04F] == 0, "poll_range");
        sim.regs[0x04F] = 0x24;
        check_true(full.poll_interrupt() == (VL6180XFull::SOURCE_RANGE | VL6180XFull::SOURCE_ALS), "poll_range_als");
        sim.regs[0x04F] = 0x40;
        check_true(full.poll_interrupt() == VL6180XFull::SOURCE_ERROR, "poll_error");
        check_true(full.poll_interrupt() == 0, "poll_none");

        FakePin pin;
        full.on_interrupt(onStatus, &pin);
        check_true(pin.handler != nullptr, "on_interrupt_subscribed");
        sim.regs[0x04F] = 0x04;
        pin.handler();
        check_true(g_got.size() == 1 && g_got[0] == VL6180XFull::SOURCE_RANGE, "callback_status");
        full.off_interrupt();
        check_true(pin.handler == nullptr, "off_interrupt");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
