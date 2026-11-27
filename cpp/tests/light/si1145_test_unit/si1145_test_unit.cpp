#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <vector>
#include <algorithm>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Si1145.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Simulates the command engine: RESPONSE counter, parameter RAM, forced measurements filling
// the data registers and IRQ_STATUS.
class Si1145Sim : public I2CConnectionMock {
public:
    uint8_t regs[0x40] = {0};
    uint8_t params[32] = {0};
    uint16_t vis = 260, ir = 300, ps[3] = {1500, 1600, 1700}, uv = 345, temp = 0x1111, vdd = 0x2222;
    std::vector<uint8_t> commands;
    bool ignoreCommands = false;
    int failParam = -1;

    explicit Si1145Sim(uint8_t partId = 0x45, uint8_t seqId = 0x08) {
        regs[0x00] = partId;
        regs[0x02] = seqId;
        params[0x00] = 0x60;
        params[0x02] = 0x21;
        params[0x0C] = 0x04;
        params[0x0F] = 0x65;
    }

    uint8_t last() const { return commands.empty() ? 0xFF : commands.back(); }
    bool sent(uint8_t cmd) const { return std::find(commands.begin(), commands.end(), cmd) != commands.end(); }

    void put16(uint8_t reg, uint16_t v) { regs[reg] = (uint8_t)v; regs[reg + 1] = (uint8_t)(v >> 8); }

    void measure(bool p, bool a) {
        uint8_t ch = params[0x01], status = 0;
        if (a && (ch & 0xF0)) {
            if (ch & 0x10) put16(0x22, vis);
            if (ch & 0x20) put16(0x24, ir);
            if (ch & 0x80) put16(0x2C, uv);
            else if (ch & 0x40) put16(0x2C, params[0x0F] == 0x65 ? temp : vdd);
            status |= 0x01;
        }
        if (p) {
            for (int i = 0; i < 3; i++)
                if (ch & (1 << i)) { put16((uint8_t)(0x26 + 2 * i), ps[i]); status |= (uint8_t)(0x04 << i); }
        }
        regs[0x21] |= status & regs[0x04];
    }

    void command(uint8_t cmd) {
        commands.push_back(cmd);
        if (ignoreCommands) return;
        uint8_t resp = regs[0x20];
        if (cmd == 0x00) { regs[0x20] = 0; return; }
        if (cmd == 0x01) { regs[0x20] = 0; regs[0x07] = 0; return; }
        if (resp & 0x80) return;
        if ((cmd & 0xE0) == 0xA0) {
            uint8_t off = cmd & 0x1F;
            if (off == failParam) { regs[0x20] = 0x80; return; }
            params[off] = regs[0x17];
            regs[0x2E] = regs[0x17];
        } else if ((cmd & 0xE0) == 0x80) {
            regs[0x2E] = params[cmd & 0x1F];
        } else if (cmd >= 0x05 && cmd <= 0x07) {
            measure(cmd != 0x06, cmd != 0x05);
        } else if (cmd == 0x12) {
            for (int i = 0; i < 12; i++) regs[0x22 + i] = (uint8_t)(0x10 + i);
        } else if (!(cmd == 0x02 || cmd == 0x09 || cmd == 0x0A || cmd == 0x0B ||
                     cmd == 0x0D || cmd == 0x0E || cmd == 0x0F)) {
            regs[0x20] = 0x80; return;
        }
        regs[0x20] = (uint8_t)((resp + 1) & 0x0F);
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        uint8_t reg = data[0];
        for (size_t i = 1; i < len; i++) {
            uint8_t r = (uint8_t)(reg + i - 1);
            if (r == 0x18) command(data[i]);
            else if (r == 0x21) regs[0x21] &= (uint8_t)~data[i];
            else regs[r] = data[i];
        }
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        for (size_t i = 0; i < buf_len; i++) buf[i] = regs[(data[0] + i) & 0x3F];
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

static bool near(float a, float b) { return fabsf(a - b) < 1e-4f; }

int main() {
    Si1145Sim c;
    Si1145Full s(c);
    check_true(c.writes().empty(), "constructor_no_bus");
    check_true(s.init(), "init");
    check_true(c.regs[0x07] == 0x17, "init_hw_key");
    check_true(c.regs[0x13] == 0x7B && c.regs[0x14] == 0x6B && c.regs[0x15] == 0x01 && c.regs[0x16] == 0x00, "init_ucoef");
    check_true(c.params[0x01] == 0xB1, "init_chlist");
    check_true(c.params[0x02] == 0x01, "init_psled12");
    check_true(c.regs[0x0F] == 0x03, "init_ps_led21");
    check_true(c.regs[0x04] == 0x05, "init_irq_enable");
    {
        Si1145Sim bad(0x32);
        Si1145Minimal m(bad);
        check_true(!m.init(), "init_bad_part_id");
    }

    Si1145Reading r;
    check_true(s.read(r), "read_ok");
    check_true(r.proximity == 1500, "read_proximity");
    check_true(near(r.uv_index, 3.45f), "read_uv_index");
    check_true(r.visible == 260 && r.infrared == 300, "read_visible_infrared");
    check_true(c.sent(0x07), "read_psals_force");
    check_true(c.regs[0x21] == 0, "read_irq_cleared");

    uint16_t v = 0;
    float f = 0;
    c.ps[0] = 42;
    check_true(s.read_proximity(v) && v == 42 && c.last() == 0x05, "read_proximity_ps1");
    check_true(!s.read_proximity(v, 2), "read_proximity_ps2_unsupported");
    c.uv = 1000;
    check_true(s.read_uv_index(f) && near(f, 10.0f) && c.last() == 0x06, "read_uv_index_single");
    check_true(s.read_visible(v) && v == 260, "read_visible_single");
    check_true(s.read_infrared(v) && v == 300, "read_infrared_single");
    Si1145Raw raw;
    check_true(s.read_raw(raw) && raw.visible == 260 && raw.infrared == 300 && raw.ps1 == 42 &&
               raw.ps2 == 0 && raw.aux == 1000, "read_raw");
    check_true(s.read_temperature_raw(v) && v == 0x1111 && c.params[0x0F] == 0x65, "read_temperature_raw");
    check_true(c.params[0x01] == 0xB1, "read_aux_restores_chlist");
    check_true(s.read_vdd_raw(v) && v == 0x2222 && c.params[0x0F] == 0x75, "read_vdd_raw");

    check_true(s.set_channels(false, true, false, true) && c.params[0x01] == 0x11, "set_channels");
    check_true(!s.read_uv_index(f), "read_uv_disabled");
    check_true(!s.read_infrared(v), "read_infrared_disabled");
    check_true(!s.set_channels(true, false, false, false, false, false, true), "set_channels_uv_aux");
    check_true(!s.set_channels(false, false, false, true, true), "set_channels_ps2_si1145");
    check_true(s.set_channels(false, false, false, false, false, false, true) && c.params[0x01] == 0x40, "set_channels_aux");
    check_true(s.set_channels(true, true, true, true) && c.params[0x01] == 0xB1, "set_channels_default");

    check_true(s.set_led_current(1, 15) && c.regs[0x0F] == 0x0F, "set_led_current");
    check_true(!s.set_led_current(2, 1), "set_led_current_led2_si1145");
    check_true(!s.set_led_current(1, 16), "set_led_current_level");
    check_true(s.set_ps_led(1, 0) && c.params[0x02] == 0x00, "set_ps_led_off");
    check_true(s.set_ps_led(1, 1) && c.params[0x02] == 0x01, "set_ps_led");
    check_true(!s.set_ps_led(1, 2), "set_ps_led_led2_si1145");
    check_true(s.set_ps_photodiode(1, false) && c.params[0x07] == 0x00, "set_ps_photodiode_small");
    check_true(s.set_ps_photodiode(1, true) && c.params[0x07] == 0x03, "set_ps_photodiode_large");
    {
        Si1145Sim c47(0x47);
        Si1145Full s47(c47);
        s47.init();
        check_true(s47.set_led_current(2, 5) && s47.set_led_current(3, 9) &&
                   c47.regs[0x0F] == 0x53 && c47.regs[0x10] == 0x09, "si1147_led_current");
        check_true(s47.set_ps_led(2, 2) && s47.set_ps_led(3, 4) &&
                   c47.params[0x02] == 0x21 && c47.params[0x03] == 0x04, "si1147_ps_led");
        check_true(s47.set_channels(false, false, false, true, true, true) &&
                   s47.read_proximity(v, 3) && v == 1700, "si1147_read_ps3");
    }

    check_true(s.set_gain(3, 2, 1), "set_gain");
    check_true(c.params[0x11] == 3 && c.params[0x10] == 0x40, "set_gain_vis");
    check_true(c.params[0x1E] == 2 && c.params[0x1D] == 0x50, "set_gain_ir");
    check_true(c.params[0x0B] == 1 && c.params[0x0A] == 0x60, "set_gain_ps");
    check_true(!s.set_gain(8), "set_gain_range");
    check_true(s.set_gain() && c.params[0x10] == 0x70 && c.params[0x11] == 0, "set_gain_default");
    check_true(s.set_high_range(true, true, true) && c.params[0x12] == 0x20 && c.params[0x1F] == 0x20 &&
               c.params[0x0C] == 0x24, "set_high_range");
    check_true(s.set_high_range(false, false, false) && c.params[0x0C] == 0x04, "set_high_range_off");
    check_true(s.set_alignment(true, false, true) && c.params[0x06] == 0x10 && c.params[0x05] == 0x10, "set_alignment");
    const uint8_t uc[4] = {1, 2, 3, 4};
    check_true(s.set_uv_coefficients(uc) && c.regs[0x13] == 1 && c.regs[0x16] == 4, "set_uv_coefficients");

    uint8_t cal[12];
    check_true(s.read_calibration(cal) && cal[0] == 0x10 && cal[11] == 0x1B && c.last() == 0x12, "read_calibration");

    check_true(s.start_autonomous(0.1f) && c.regs[0x08] == (3200 & 0xFF) && c.regs[0x09] == (3200 >> 8), "start_autonomous_rate");
    check_true(c.last() == 0x0F, "start_autonomous_cmd");
    check_true(s.start_autonomous(0.01f, false) && c.last() == 0x0E && c.regs[0x08] == 64, "start_autonomous_als");
    check_true(s.start_autonomous(0.01f, true, false) && c.last() == 0x0D, "start_autonomous_ps");
    check_true(!s.start_autonomous(3.0f), "start_autonomous_range");
    check_true(!s.start_autonomous(0.1f, false, false), "start_autonomous_none");
    check_true(s.pause() && c.last() == 0x0B, "pause");
    {
        Si1145Sim cs(0x45, 0x01);
        Si1145Full ss(cs);
        ss.init();
        check_true(ss.start_autonomous(0.1f) && cs.regs[0x0A] == (3200 & 0xFF) && cs.regs[0x08] == (3200 >> 8),
                   "start_autonomous_swapped");
    }
    check_true(s.force() && c.last() == 0x07, "force_psals");
    check_true(s.force(true, false) && c.last() == 0x05, "force_ps");
    check_true(s.force(false, true) && c.last() == 0x06, "force_als");

    uint8_t counter = 0xFF, err = 0xFF;
    check_true(s.read_response(counter, err) && err == 0 && counter == c.regs[0x20], "read_response");
    c.failParam = 0x0B;
    check_true(!s.set_gain(0, 0, 1), "param_error");
    check_true(c.last() == 0x00 && c.regs[0x20] == 0, "param_error_nop");
    c.failParam = -1;
    c.regs[0x20] = 0x8C;
    check_true(s.read_response(counter, err) && err == 0x8C && counter == 0, "read_response_error");
    check_true(s.set_gain() && (c.regs[0x20] & 0x80) == 0 && c.params[0x0B] == 0, "pending_error_cleared");

    check_true(s.enable_interrupt(Si1145Full::SOURCE_CMD) && c.regs[0x03] == 0x01 && c.regs[0x04] == 0x25, "enable_interrupt");
    check_true(s.disable_interrupt(Si1145Full::SOURCE_CMD) && c.regs[0x04] == 0x05, "disable_interrupt");
    c.regs[0x21] = 0x05;
    check_true(s.poll_interrupt() == 0x05, "poll_interrupt");
    check_true(c.regs[0x21] == 0 && s.poll_interrupt() == 0, "poll_interrupt_cleared");

    FakePin pin;
    s.on_interrupt(onInt, &pin);
    check_true(pin.handler != nullptr && pin.trigger == InputPin::kFalling, "on_interrupt_falling");
    c.regs[0x21] = 0x04;
    pin.handler();
    check_true(seen.size() == 1 && seen[0] == 0x04, "on_interrupt");
    pin.handler();
    check_true(seen.size() == 1, "on_interrupt_no_duplicate");
    s.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");

    s.disable_interrupt(0x3D);
    check_true(s.read(r) && r.proximity == 42, "read_without_irq");

    uint8_t part = 0, rev = 0xFF, seq = 0;
    check_true(s.read_id(part, rev, seq) && part == 0x45 && rev == 0 && seq == 0x08, "read_id");
    check_true(s.set_i2c_address(0x30) && c.params[0x00] == 0x30 && c.last() == 0x02, "set_i2c_address");
    check_true(!s.set_i2c_address(0x80), "set_i2c_address_invalid");

    c.regs[0x0F] = 0x0F;
    check_true(s.reset() && c.sent(0x01), "reset_cmd");
    check_true(c.regs[0x07] == 0x17 && c.params[0x01] == 0xB1 && c.regs[0x0F] == 0x03, "reset_defaults");
    check_true(s.read(r) && r.proximity == 42, "reset_read");

    {
        Si1145Sim stuck;
        Si1145Full s2(stuck);
        s2.init();
        stuck.ignoreCommands = true;
        check_true(!s2.read(r), "measure_timeout");
        check_true(!s2.pause(), "command_timeout");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
