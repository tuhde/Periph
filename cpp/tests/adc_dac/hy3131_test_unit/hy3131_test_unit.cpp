#include <stdio.h>
#include <math.h>
#include <map>
#include <vector>
#include "Connection.h"
#include "HY3131.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

/** Models the HY3131 framing: (addr<<1)|R/W command, one blank clock on reads, auto-increment.
 *  INTF reads back `intf` once, then clears. */
class HY3131Chip : public Connection {
public:
    std::map<uint8_t, uint8_t> mem;
    std::vector<std::vector<uint8_t>> log;
    uint8_t intf = 0x07;

    void set(uint8_t reg, std::initializer_list<uint8_t> vals) {
        uint8_t i = 0;
        for (uint8_t v : vals) mem[reg + i++] = v;
    }
    uint8_t get(uint8_t reg) const {
        auto it = mem.find(reg);
        return it == mem.end() ? 0 : it->second;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        log.emplace_back(data, data + len);
        uint8_t addr = data[0] >> 1;
        for (size_t i = 1; i < len; i++) mem[addr + i - 1] = data[i];
    }
    void _read(uint8_t* buf, size_t len) override { for (size_t i = 0; i < len; i++) buf[i] = 0; }
    void _write_read(const uint8_t* data, size_t, uint8_t* buf, size_t buf_len) override {
        uint8_t addr = data[0] >> 1;
        std::vector<uint8_t> vals(buf_len);
        for (size_t i = 0; i < buf_len; i++) {
            uint8_t a = static_cast<uint8_t>(addr + i);
            if (a == 0x1E) { vals[i] = intf; intf = 0; }
            else vals[i] = get(a);
        }
        for (size_t i = 0; i < buf_len; i++) {
            buf[i] = i == 0 ? vals[0] >> 1 : static_cast<uint8_t>(((vals[i - 1] & 1) << 7) | (vals[i] >> 1));
        }
    }
};

int main() {
    // --- init defaults ---
    HY3131Chip c;
    HY3131Minimal adc(c, 1.2f);
    check_true(c.log[0] == std::vector<uint8_t>({static_cast<uint8_t>(0x37 << 1), 0x60}), "init_reset_first");
    check_true(c.get(0x33) == 0x28, "init_osc");
    check_true(c.get(0x31) == 0xC0 && c.get(0x30) == 0x00, "init_reference");
    check_true(c.get(0x24) == 0xA3 && c.get(0x28) == 0x11, "init_mux");
    check_true(c.get(0x22) == 0x07 && c.get(0x23) == 0x80, "init_enable_ad1");

    // --- register framing ---
    c.set(0x70, {0xA5, 0x5A});
    uint8_t rb[2];
    adc.read_register(0x70, rb, 2);
    check_true(rb[0] == 0xA5 && rb[1] == 0x5A, "read_register_shift");
    const uint8_t wd[3] = {1, 2, 3};
    adc.write_register(0x60, wd, 3);
    check_true(c.get(0x60) == 1 && c.get(0x61) == 2 && c.get(0x62) == 3, "write_register_autoincrement");

    // --- read_raw / read_voltage ---
    c.set(0x00, {0x00, 0x00, 0x40});
    c.intf = 0x04;
    check_true(adc.read_raw() == 0x400000, "read_raw");
    c.intf = 0x04;
    check_true(fabsf(adc.read_voltage() - 0.5f * 1.2f / 0.9f) < 1e-5f, "read_voltage");
    c.set(0x00, {0x00, 0x00, 0x80});
    c.intf = 0x04;
    check_true(adc.read_raw() == -8388608, "read_raw_negative");
    c.intf = 0x04;
    check_true(adc.data_ready(), "data_ready_true");
    c.set(0x00, {0x01, 0x00, 0x00});
    check_true(adc.read_raw() == 1, "latched_flag_used");

    // --- Full ---
    HY3131Chip c2;
    HY3131Full f(c2, 1.2f);
    f.configure_ad1(4, 2, 3, 1, 1, 3);
    check_true(c2.get(0x22) == ((1 << 5) | (2 << 3) | 4), "configure_ad1_r22");
    check_true(c2.get(0x23) == 0x93, "configure_ad1_r23");
    check_true(((c2.get(0x25) >> 4) & 3) == 3, "configure_ad1_gain");
    c2.set(0x00, {0x00, 0x00, 0x40});
    c2.intf = 0x04;
    check_true(fabsf(f.read_voltage() - (0.5f * 0.3333333333f - 0.25f) * 1.2f / 3.6f) < 1e-5f, "configure_ad1_voltage");

    f.set_ad1_input(0x8, 0x5, 1);
    f.set_ad1_reference(2, 6);
    check_true(c2.get(0x24) == 0x85 && ((c2.get(0x33) >> 2) & 3) == 1 && c2.get(0x28) == 0x26, "muxes");

    f.enable_ad2(3, 2, 0, false, false, 1, true);
    check_true(c2.get(0x26) == 0x83, "enable_ad2_r26");
    check_true((c2.get(0x34) & 0x84) == 0x84, "enable_ad2_sdo23");
    check_true(((c2.get(0x25) >> 6) & 3) == 2, "enable_ad2_gain");
    c2.set(0x03, {0xFF, 0xFF, 0xFF});
    c2.intf = 0x02;
    check_true(f.read_ad2_raw() == -1, "read_ad2_sign");

    f.enable_lpf(3);
    f.enable_rms();
    check_true(c2.get(0x29) == (0xC0 | (3 << 3)), "lpf_rms_bits");
    c2.set(0x09, {0x00, 0x90, 0x01, 0x00, 0x00});
    c2.intf = 0x10;
    check_true(fabsf(f.read_rms() - 320.0f) < 1e-3f, "read_rms");

    f.enable_peak_hold(2);
    check_true((c2.get(0x29) & 0x07) == 0x06, "peak_hold_bits");
    c2.set(0x0E, {0x00, 0x00, 0xFE, 0x00, 0x00, 0x01});
    int32_t lo = 0, hi = 0;
    f.read_peak(lo, hi);
    check_true(lo == -131072 && hi == 65536, "read_peak");

    f.start_counter(0x1234);
    check_true(c2.get(0x1C) == 0x34 && c2.get(0x1D) == 0x12, "counter_preload");
    check_true((c2.get(0x20) & 0x0A) == 0x0A, "counter_start");
    c2.set(0x14, {0x01});
    c2.intf = 0x01;
    uint32_t cta, ctb, ctc;
    bool ov = false;
    check_true(f.read_counter(cta, ctb, ctc, ov) && ov, "read_counter");

    c2.intf = 0x14;
    check_true(f.read_flags() == 0x14, "read_flags");
    f.enable_interrupts(0xFF);
    check_true(c2.get(0x1F) == 0x1F, "enable_interrupts_masked");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
