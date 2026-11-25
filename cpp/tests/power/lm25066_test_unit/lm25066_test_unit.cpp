#include <stdio.h>
#include <string.h>
#include <math.h>
#include <map>
#include <vector>
#include "I2CConnectionMock.h"
#include "LM25066.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol = 1e-3f) { return fabsf(a - b) < tol; }

// One byte string per PMBus command; writes do not overlap neighbours.
class CmdMock : public I2CConnectionMock {
public:
    std::map<uint8_t, std::vector<uint8_t>> cmds;

    void word(uint8_t cmd, uint16_t v) { cmds[cmd] = {(uint8_t)(v & 0xFF), (uint8_t)(v >> 8)}; }
    void byte(uint8_t cmd, uint8_t v) { cmds[cmd] = {v}; }
    void block(uint8_t cmd, std::initializer_list<uint16_t> words) {
        std::vector<uint8_t> d = {12};
        for (uint16_t w : words) { d.push_back(w & 0xFF); d.push_back(w >> 8); }
        cmds[cmd] = d;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (len > 1) cmds[data[0]] = std::vector<uint8_t>(data + 1, data + len);
    }
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        auto it = cmds.find(data[0]);
        for (size_t i = 0; i < buf_len; i++) buf[i] = (it != cmds.end() && i < it->second.size()) ? it->second[i] : 0;
    }
};

static bool last_write_is(CmdMock& c, std::vector<uint8_t> expect) {
    return !c.writes().empty() && c.writes().back() == expect;
}

int main() {
    CmdMock conn;
    LM25066Minimal m(conn, 5.0f, false);
    check_true(conn.writes().empty(), "init_no_writes");

    conn.word(0x88, 2000);
    check_true(near(m.voltage_in(), 201800.0f / 22070.0f), "vin");
    conn.word(0x8B, 0xF000 | 1500);
    check_true(near(m.voltage_out(), 151800.0f / 22070.0f), "vout");
    conn.word(0xD1, 648);
    check_true(near(m.current(), 70000.0f / 68305.0f), "current_cl_gnd");
    conn.word(0xD2, 1000);
    check_true(near(m.power(), 103300.0f / (736.0f * 5.0f)), "power_cl_gnd");
    conn.word(0x8D, 0x0190);
    check_true(near(m.temperature(), 25.0f), "temp_25");
    conn.word(0x8D, 0xFF00);
    check_true(near(m.temperature(), -16.0f), "temp_negative");

    LM25066Minimal mh(conn, 5.0f, true);
    conn.word(0xD1, 648);
    check_true(near(mh.current(), (64800.0f + 3100.0f) / (6854.0f * 5.0f)), "current_cl_vdd");
    conn.word(0xD2, 1000);
    check_true(near(mh.power(), (100000.0f + 1900.0f) / (369.0f * 5.0f)), "power_cl_vdd");

    CmdMock c2;
    LM25066Full f(c2, 1.0f, false);
    c2.word(0xD0, 3546 - 3);
    check_true(near(f.voltage_aux(), 1.0f), "vaux");

    c2.block(0xDA, {0x0460, 648, 1500, 2000, 1000, 0x0190});
    LM25066Block b = f.read_block();
    check_true(f.valid() && b.diagnostic == 0x0460, "block_diag");
    check_true(near(b.current, 70000.0f / 13661.0f), "block_current");
    check_true(near(b.voltage_out, 151800.0f / 22070.0f), "block_vout");
    check_true(near(b.voltage_in, 201800.0f / 22070.0f), "block_vin");
    check_true(near(b.power, 103300.0f / 736.0f), "block_power");
    check_true(near(b.temperature, 25.0f), "block_temp");
    c2.block(0xE2, {0, 0, 0, 0, 0, 0});
    check_true(near(f.read_average().voltage_in, 1800.0f / 22070.0f), "avg_block");
    c2.block(0xE0, {1, 0, 0, 0, 0, 0});
    check_true(f.black_box().diagnostic == 1, "black_box");
    c2.cmds[0xDA] = std::vector<uint8_t>(13, 0);
    c2.cmds[0xDA][0] = 5;
    f.read_block();
    check_true(!f.valid(), "block_count_checked");

    c2.word(0xDC, 2000);
    check_true(near(f.voltage_in_avg(), 201800.0f / 22070.0f), "vin_avg");
    c2.word(0xD5, 1000);
    check_true(near(f.power_peak(), 103300.0f / 736.0f), "power_peak");
    c2.clearWrites();
    f.clear_power_peak();
    check_true(last_write_is(c2, {0xD6}), "clear_peak");

    check_true(f.set_averaging(6) && last_write_is(c2, {0xDB, 6}), "set_avg");
    check_true(!f.set_averaging(13), "avg_range");
    check_true(f.get_averaging() == 6, "get_avg");

    f.set_vin_uv_warn(10.0f);
    check_true(last_write_is(c2, {0x58, 0x8D, 0x08}), "vin_uv_warn_write");
    f.set_vin_uv_warn(NAN);
    check_true(last_write_is(c2, {0x58, 0x00, 0x00}), "vin_uv_disable");
    f.set_vin_ov_warn(NAN);
    check_true(last_write_is(c2, {0x57, 0xFF, 0x0F}), "vin_ov_disable");
    f.set_ot_fault(150.0f);
    check_true(last_write_is(c2, {0x4F, 0x60, 0x09}), "ot_fault_150");
    f.set_ot_warn(125.0f);
    check_true(last_write_is(c2, {0x51, 0xD0, 0x07}), "ot_warn_125");
    f.set_pin_warn(1e9f);
    check_true(last_write_is(c2, {0xD4, 0xFE, 0x0F}), "clamp");
    f.set_iin_warn(1.02f);
    uint16_t y = (uint16_t)roundf((13661.0f * 1.02f - 5200.0f) / 100.0f);
    check_true(last_write_is(c2, {0xD3, (uint8_t)(y & 0xFF), (uint8_t)(y >> 8)}), "iin_warn");

    c2.word(0x57, 0x0FFF); c2.word(0x58, 0); c2.word(0x43, 0); c2.word(0x4F, 0x0960);
    c2.word(0x51, 0x07D0); c2.word(0xD3, 0x0FFF); c2.word(0xD4, 0x0FFF);
    LM25066Limits lim = f.get_limits();
    check_true(isnan(lim.vin_ov_warn) && isnan(lim.vin_uv_warn) && isnan(lim.iin_warn) &&
               isnan(lim.pin_warn) && isnan(lim.vout_uv_warn), "limits_disabled");
    check_true(near(lim.ot_fault, 150.0f) && near(lim.ot_warn, 125.0f), "limits_ot");

    c2.byte(0x78, 0x49); c2.word(0x79, 0x3849); c2.byte(0x7A, 0x20); c2.byte(0x7C, 0x10);
    c2.byte(0x7D, 0x80); c2.byte(0x7E, 0x02); c2.byte(0x80, 0x10); c2.word(0xE1, 0x0460);
    check_true(f.status_byte() == 0x49 && f.status_word() == 0x3849, "status");
    check_true(f.status_vout() == 0x20 && f.status_input() == 0x10 && f.status_temperature() == 0x80 &&
               f.status_cml() == 0x02 && f.status_mfr() == 0x10, "status_groups");
    check_true(f.diagnostic_word() == 0x0460 && f.pollInterrupt() == 0x0460, "diagnostic");

    f.clear_faults();
    check_true(last_write_is(c2, {0x03}), "clear_faults");
    f.operation_on();
    check_true(last_write_is(c2, {0x01, 0x80}), "operation_on");
    f.operation_off();
    check_true(last_write_is(c2, {0x01, 0x00}), "operation_off");
    c2.byte(0x01, 0x80); c2.byte(0x78, 0x00);
    check_true(f.is_on(), "is_on");
    c2.byte(0x78, 0x40);
    check_true(!f.is_on(), "is_off_status");

    check_true(f.configure_device(7, 1, 0) && last_write_is(c2, {0xD9, 0xE0 | 0x04 | 0x10 | 0x02}), "device_setup");
    c2.word(0xD1, 648);
    check_true(near(f.current(), 67900.0f / 6854.0f), "coeff_updated");
    check_true(!f.configure_device(8), "retry_range");
    c2.byte(0xD9, 0x16);
    check_true(f.device_setup() == 0x16, "get_device_setup");

    check_true(f.set_gate_mask(0x04) && last_write_is(c2, {0xD7, 0x04}), "gate_mask");
    check_true(!f.set_gate_mask(0x01), "gate_mask_invalid");
    check_true(f.get_gate_mask() == 0x04, "get_gate_mask");

    c2.byte(0x19, 0xB0);
    check_true(f.capability() == 0xB0, "capability");
    c2.cmds[0x99] = {3, 'N', 'S', 'C'};
    c2.cmds[0x9A] = {8, 'L', 'M', '2', '5', '0', '6', '6', 0};
    c2.cmds[0x9B] = {2, 'A', 'A'};
    char s[16];
    f.manufacturer_id(s, sizeof s);
    check_true(strcmp(s, "NSC") == 0, "mfr_id");
    f.model(s, sizeof s);
    check_true(strcmp(s, "LM25066") == 0, "model");
    f.revision(s, sizeof s);
    check_true(strcmp(s, "AA") == 0, "revision");

    c2.word(0xD8, 0x0820);
    f.enable_interrupt(LM25066Full::SOURCE_VIN_UV_FAULT);
    check_true(last_write_is(c2, {0xD8, 0x00, 0x08}), "enable_irq");
    f.disable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN);
    check_true(last_write_is(c2, {0xD8, 0x00, 0x28}), "disable_irq");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
