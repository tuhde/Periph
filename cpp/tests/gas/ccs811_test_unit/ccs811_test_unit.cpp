#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Ccs811.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol) { return fabsf(a - b) < tol; }

// ALG_RESULT_DATA: eCO2 500 ppm, TVOC 15 ppb, STATUS 0x98, ERROR_ID 0, RAW 0x8C88
static const std::vector<uint8_t> RESULT = {0x01, 0xF4, 0x00, 0x0F, 0x98, 0x00, 0x8C, 0x88};

// Each register is its own mailbox; STATUS tracks FW_MODE and DATA_READY.
class Ccs811Mock : public I2CConnectionMock {
public:
    std::map<uint8_t, std::vector<uint8_t>> mail;
    bool appValid = true, fwMode = false, dataReady = true, readyAfterStart = true;

    Ccs811Mock() {
        mail[0x20] = {0x81}; mail[0x21] = {0x12};
        mail[0x23] = {0x10, 0x00}; mail[0x24] = {0x11, 0x02};
        mail[0x02] = RESULT; mail[0x03] = {0x8C, 0x88};
        mail[0x06] = {0x06, 0x40, 0x03, 0x20};
        mail[0x11] = {0x84, 0x7B}; mail[0xE0] = {0x00};
    }

    uint8_t statusByte() const {
        return (uint8_t)((fwMode ? 0x80 : 0) | (appValid ? 0x10 : 0) | (dataReady ? 0x08 : 0));
    }

    std::vector<uint8_t> last(uint8_t reg) const {
        std::vector<uint8_t> v;
        for (const auto& w : writes())
            if (w.size() >= 2 && w[0] == reg) v.assign(w.begin() + 1, w.end());
        return v;
    }

    bool wrote(const std::vector<uint8_t>& frame, size_t from = 0) const {
        for (size_t i = from; i < writes().size(); i++) if (writes()[i] == frame) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (data[0] == 0xF4 && len == 1) { if (readyAfterStart) fwMode = true; }
        else if (data[0] == 0xFF) fwMode = false;
        else if (len >= 2) mail[data[0]].assign(data + 1, data + len);
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        uint8_t reg = data[0];
        if (reg == 0x00) { buf[0] = statusByte(); return; }
        const std::vector<uint8_t>& v = mail[reg];
        for (size_t i = 0; i < buf_len; i++) buf[i] = i < v.size() ? v[i] : 0;
        if (reg == 0x02) dataReady = false;
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = 0xFF;
    bool onEdge(Handler h, uint8_t t) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (h == handler) handler = nullptr; }
    bool isHigh() override { return true; }
};

static std::vector<uint8_t> seen;
static void onInt(uint8_t status) { seen.push_back(status); }

typedef std::vector<uint8_t> V;

int main() {
    Ccs811Mock c;
    CCS811Full s(c);
    check_true(c.writes().empty(), "constructor_no_bus");
    check_true(s.init(), "init_ok");
    check_true(c.wrote({0xF4}), "init_app_start");
    check_true(c.last(0x01) == V{0x10}, "init_mode_1");

    uint16_t eco2 = 0, tvoc = 0;
    check_true(s.read(eco2, tvoc) && eco2 == 500 && tvoc == 15, "read");
    check_true(!c.dataReady, "read_clears_ready");
    c.dataReady = true;
    check_true(s.read_eco2(eco2) && eco2 == 500, "read_eco2");
    c.dataReady = true;
    check_true(s.read_tvoc(tvoc) && tvoc == 15, "read_tvoc");
    bool ready = true;
    check_true(s.data_ready(ready) && !ready, "data_ready_false");
    c.dataReady = true;
    check_true(s.data_ready(ready) && ready, "data_ready_true");

    uint8_t cur = 0; float volt = 0;
    check_true(s.read_raw(cur, volt) && cur == 35 && near(volt, 136 * 1.65f / 1023, 1e-5f), "read_raw");

    s.enable_interrupt(CCS811Full::SOURCE_DATA_READY);
    check_true(c.last(0x01) == V{0x18}, "int_enable_data_ready");
    s.set_mode(2);
    check_true(c.last(0x01) == V{0x28}, "set_mode_keeps_int");
    s.enable_interrupt(CCS811Full::SOURCE_THRESHOLD);
    check_true(c.last(0x01) == V{0x2C}, "int_enable_threshold");
    s.disable_interrupt(CCS811Full::SOURCE_THRESHOLD);
    check_true(c.last(0x01) == V{0x20}, "int_disable_threshold");
    s.enable_interrupt(CCS811Full::SOURCE_THRESHOLD);
    s.disable_interrupt(CCS811Full::SOURCE_DATA_READY);
    check_true(c.last(0x01) == V{0x24}, "int_disable_data_ready");
    s.disable_interrupt(CCS811Full::SOURCE_THRESHOLD);
    s.set_mode(4);
    check_true(c.last(0x01) == V{0x40}, "set_mode_4");
    s.set_mode(0);
    check_true(c.last(0x01) == V{0x00}, "set_mode_idle");
    check_true(!s.set_mode(5), "set_mode_invalid");
    s.set_mode(1);

    check_true(s.set_environment(48.5f, 22.5f) && c.last(0x05) == V({0x61, 0x00, 0x5F, 0x00}), "set_environment");
    check_true(s.set_environment(50, 25) && c.last(0x05) == V({0x64, 0x00, 0x64, 0x00}), "set_environment_default");
    check_true(!s.set_environment(-1, 25) && !s.set_environment(101, 25)
               && !s.set_environment(50, -26) && !s.set_environment(50, 103), "set_environment_invalid");

    uint16_t vr = 0, vn = 0;
    check_true(s.read_ntc(vr, vn) && vr == 1600 && vn == 800, "read_ntc");
    float r = 0;
    check_true(s.ntc_resistance(100000, r) && near(r, 50000.0f, 0.01f), "ntc_resistance");
    c.mail[0x06] = {0x00, 0x00, 0x03, 0x20};
    check_true(!s.ntc_resistance(100000, r), "ntc_resistance_no_ref");
    c.mail[0x06] = {0x06, 0x40, 0x03, 0x20};

    check_true(s.set_thresholds(1000, 2000, 50) && c.last(0x10) == V({0x03, 0xE8, 0x07, 0xD0, 0x32}), "set_thresholds");
    check_true(s.set_thresholds() && c.last(0x10) == V({0x05, 0xDC, 0x09, 0xC4, 0x32}), "set_thresholds_default");
    check_true(!s.set_thresholds(2000, 1000, 50), "set_thresholds_invalid");

    uint16_t base = 0;
    check_true(s.get_baseline(base) && base == 0x847B, "get_baseline");
    check_true(s.set_baseline(0x1234) && c.last(0x11) == V({0x12, 0x34}), "set_baseline");
    check_true(s.get_baseline(base) && base == 0x1234, "get_baseline_roundtrip");

    c.dataReady = false;
    uint8_t st = 0;
    check_true(s.status(st) && st == 0x90, "status");
    c.mail[0xE0] = {0x10};
    uint8_t err = 0;
    check_true(s.error(err) && err == 0x10, "error");
    c.mail[0xE0] = {0x00};
    uint8_t id = 0, ver = 0;
    check_true(s.hardware_version(id, ver) && id == 0x81 && ver == 0x12, "hardware_version");
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    check_true(s.firmware_version(boot, app) && boot.major == 1 && boot.minor == 0 && boot.trivial == 0
               && app.major == 1 && app.minor == 1 && app.trivial == 2, "firmware_version");

    c.dataReady = true;
    check_true(s.poll_interrupt() == 0x98, "poll_interrupt");
    check_true(c.dataReady, "poll_interrupt_keeps_ready");

    FakePin pin;
    s.on_interrupt(onInt, &pin);
    check_true(pin.trigger == InputPin::kFalling, "on_interrupt_falling_edge");
    pin.handler();
    check_true(seen.size() == 1 && seen[0] == 0x98, "on_interrupt_dispatch");
    c.dataReady = false;
    pin.handler();
    check_true(seen.size() == 1, "on_interrupt_no_spurious");
    s.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");

    c.dataReady = true;
    c.mail[0x02] = {0x01, 0xF4, 0x00, 0x0F, 0x99, 0x08};
    check_true(!s.read(eco2, tvoc), "read_error_flag");
    c.mail[0x02] = RESULT;

    size_t n = c.writes().size();
    s.set_mode(3);
    check_true(s.soft_reset(), "soft_reset_ok");
    check_true(c.wrote({0xFF, 0x11, 0xE5, 0x72, 0x8A}, n), "soft_reset_key");
    check_true(c.wrote({0xF4}, n), "soft_reset_app_start");
    check_true(c.last(0x01) == V{0x10}, "soft_reset_mode_1");
    c.dataReady = true;
    check_true(s.read(eco2, tvoc) && eco2 == 500, "read_after_reset");

    Ccs811Mock mc;
    CCS811Minimal mn(mc);
    check_true(mn.init() && mn.read(eco2, tvoc) && eco2 == 500 && tvoc == 15, "minimal_read");

    Ccs811Mock ac;
    ac.fwMode = true;
    CCS811Minimal ma(ac);
    check_true(ma.init() && !ac.wrote({0xF4}), "init_app_running");

    Ccs811Mock bad;
    bad.mail[0x20] = {0x55};
    CCS811Minimal mb(bad);
    check_true(!mb.init(), "init_bad_id");

    Ccs811Mock noapp;
    noapp.appValid = false;
    CCS811Minimal mna(noapp);
    check_true(!mna.init(), "init_no_app");

    Ccs811Mock stuck;
    stuck.readyAfterStart = false;
    CCS811Minimal ms(stuck);
    check_true(!ms.init(), "init_app_start_failed");

    Ccs811Mock nack;
    CCS811Full fn(nack);
    fn.init();
    nack.failNextWrites(1);
    check_true(!fn.read(eco2, tvoc), "read_reports_bus_error");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
