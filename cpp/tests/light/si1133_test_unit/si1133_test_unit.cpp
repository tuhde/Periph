#include <stdio.h>
#include <stdint.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Si1133.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Simulates the command engine: RESPONSE0 counter, parameter table, FORCE packing HOSTOUT.
class Si1133Mock : public I2CConnectionMock {
public:
    uint8_t partId = 0x33;
    uint8_t params[64] = {0};
    uint8_t counter = 0x0F, error = 0, resp1 = 0, irqEnable = 0, irqStatus = 0;
    uint8_t hostout[26] = {0};
    int32_t samples[6] = {0x012345, -5, 0xABCD, 0, 0, 0};
    std::vector<uint8_t> commands;
    bool ignoreCommands = false;

    uint8_t resp0() const {
        return error ? (uint8_t)(0x30 | (error & 0x0F)) : (uint8_t)(0x20 | counter);
    }

    void command(uint8_t cmd, uint8_t hostin) {
        commands.push_back(cmd);
        if (ignoreCommands) return;
        if (cmd == 0x00) { counter = 0; error = 0; return; }
        if (cmd == 0x01) {
            for (uint8_t& p : params) p = 0;
            counter = 0x0F; error = 0; irqEnable = 0;
            return;
        }
        if ((cmd & 0xC0) == 0x80) {
            uint8_t p = cmd & 0x3F;
            if (p > 0x2B) { error = 0x11; return; }
            params[p] = hostin; resp1 = hostin;
        } else if ((cmd & 0xC0) == 0x40) {
            resp1 = params[cmd & 0x3F];
        } else if (cmd == 0x11) {
            size_t pos = 0;
            for (uint8_t& b : hostout) b = 0;
            for (int ch = 0; ch < 6; ch++) {
                if (!(params[1] & (1 << ch))) continue;
                uint32_t v = (uint32_t)samples[ch];
                if (params[0x04 + 4 * ch] & 0x40) {
                    hostout[pos++] = (uint8_t)(v >> 16); hostout[pos++] = (uint8_t)(v >> 8);
                    hostout[pos++] = (uint8_t)v;
                } else {
                    hostout[pos++] = (uint8_t)(v >> 8); hostout[pos++] = (uint8_t)v;
                }
            }
            irqStatus |= params[1] & irqEnable;
        } else if (cmd != 0x12 && cmd != 0x13) {
            error = 0x10; return;
        }
        counter = (counter + 1) & 0x0F;
    }

    bool wrote(const std::vector<uint8_t>& frame) const {
        for (const auto& w : writes()) if (w == frame) return true;
        return false;
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (data[0] == 0x0A && len >= 3) command(data[2], data[1]);
        else if (data[0] == 0x0B && len >= 2) command(data[1], 0);
        else if (data[0] == 0x0F && len >= 2) irqEnable = data[1];
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
        uint8_t reg = data[0];
        for (size_t i = 0; i < buf_len; i++) buf[i] = 0;
        if (reg == 0x00) {
            const uint8_t id[3] = {partId, 0x03, 0x10};
            for (size_t i = 0; i < buf_len && i < 3; i++) buf[i] = id[i];
        } else if (reg == 0x10) buf[0] = resp1;
        else if (reg == 0x11) buf[0] = resp0();
        else if (reg == 0x12) { buf[0] = irqStatus; irqStatus = 0; }
        else if (reg >= 0x13) {
            for (size_t i = 0; i < buf_len && reg - 0x13 + i < 26; i++) buf[i] = hostout[reg - 0x13 + i];
        }
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

int main() {
    Si1133Mock c;
    Si1133Full s(c);
    check_true(c.writes().empty(), "constructor_no_bus");
    check_true(s.init(), "init");
    check_true(!c.commands.empty() && c.commands[0] == 0x01, "init_reset_sw");
    check_true(c.params[0x02] == 0x18 && c.params[0x03] == 0x09 && c.params[0x04] == 0x40, "init_channel0_uv");
    check_true(c.params[0x06] == 0x0B && c.params[0x07] == 0x07 && c.params[0x08] == 0x40, "init_channel1_white");
    check_true(c.params[0x01] == 0x03, "init_chan_list");
    check_true(c.irqEnable == 0x3F, "init_irq_enable");
    check_true(c.wrote({0x0A, 0x03, 0x81}), "param_set_one_write");

    int32_t uv = 0, white = 0;
    check_true(s.read(uv, white), "read");
    check_true(uv == 0x012345, "read_uv");
    check_true(white == -5, "read_white_negative");
    check_true(c.commands.back() == 0x11, "read_force");

    check_true(s.configure_channel(2, Si1133Full::MUX_LARGE_IR, 3, 2, 1, true, false, 1), "configure_channel");
    check_true(c.params[0x0A] == 0x22 && c.params[0x0B] == 0xA3 && c.params[0x0C] == 0x08, "configure_values");
    check_true(s.enable_channels(0x07) && c.params[0x01] == 0x07, "enable_channels");
    int32_t v[6];
    size_t n = 0;
    check_true(s.read_channels(v, n) && n == 3 && v[0] == 0x012345 && v[1] == -5 && v[2] == 0xABCD, "read_channels");
    check_true(s.read_channels(v, n, false) && n == 3 && v[2] == 0xABCD, "read_channels_no_force");
    check_true(s.read(uv, white) && uv == 0x012345 && white == -5, "read_still_works");
    check_true(!s.configure_channel(6, 0), "configure_invalid_channel");
    check_true(!s.configure_channel(0, 0x18, 12), "configure_invalid_gain");
    check_true(!s.enable_channels(0x40), "enable_invalid");

    c.samples[5] = 1234;
    int32_t x = 0;
    check_true(s.read_uv(x) && x == 1234 && c.params[0x16] == 0x18 && c.params[0x17] == 0x09, "read_uv_scratch");
    check_true(c.params[0x01] == 0x07, "read_uv_restores");
    check_true(s.read_white(x) && x == 1234 && c.params[0x16] == 0x0B, "read_white_scratch");
    check_true(s.read_ir(x, Si1133Full::MUX_MEDIUM_IR) && c.params[0x16] == 0x01, "read_ir_medium");
    check_true(s.read_ir(x) && c.params[0x16] == 0x00, "read_ir_default");
    check_true(!s.read_ir(x, 0x0B), "read_ir_invalid");

    check_true(s.force() && c.commands.back() == 0x11, "force");

    check_true(s.start_autonomous(1.0f, 2, 3, 4), "start_autonomous");
    check_true(((c.params[0x1A] << 8) | c.params[0x1B]) == 1250, "measrate");
    check_true(c.params[0x1C] == 2 && c.params[0x1D] == 3 && c.params[0x1E] == 4, "meascount");
    check_true(c.params[0x05] == 0x40 && c.params[0x09] == 0x40 && c.params[0x0D] == 0x40, "counter_index");
    check_true(c.commands.back() == 0x13, "start");
    check_true(s.set_channel_counter(2, 3) && c.params[0x0D] == 0xC0, "set_channel_counter");
    check_true(s.pause() && c.commands.back() == 0x12, "pause");
    check_true(!s.start_autonomous(0.0f), "autonomous_invalid");

    check_true(s.set_burst(4) && c.params[0x2B] == 0x84, "burst");
    check_true(s.set_burst(0) && c.params[0x2B] == 0, "burst_off");
    check_true(!s.set_burst(128), "burst_invalid");

    check_true(s.set_threshold(1, 0x1234) && c.params[0x27] == 0x12 && c.params[0x28] == 0x34, "threshold");
    check_true(s.set_channel_threshold(0, 2) && c.params[0x04] == 0x42, "channel_threshold");
    check_true(!s.set_threshold(3, 0), "threshold_invalid");

    uint8_t p = 0;
    check_true(s.write_parameter(0x1C, 0x55) && c.params[0x1C] == 0x55, "write_parameter");
    check_true(s.read_parameter(0x1C, p) && p == 0x55 && c.commands.back() == 0x5C, "read_parameter");

    uint8_t state = 0, counter = 0;
    bool err = true;
    check_true(s.read_response(state, err, counter) && state == 0x20 && !err && counter == c.counter, "read_response");

    check_true(!s.write_parameter(0x30, 1), "param_error");
    check_true(c.commands.back() == 0x00 && c.error == 0, "param_error_cleared");

    uint8_t m = 0;
    c.irqStatus = 0x05;
    check_true(s.data_ready(m) && m == 0x05, "data_ready");
    check_true(s.data_ready(m) && m == 0, "data_ready_cleared");

    check_true(s.disable_interrupt(Si1133Full::SOURCE_CHANNEL1) && c.irqEnable == 0x3D, "disable_interrupt");
    check_true(s.enable_interrupt(Si1133Full::SOURCE_CHANNEL1) && c.irqEnable == 0x3F, "enable_interrupt");
    c.irqStatus = 0x02;
    check_true(s.poll_interrupt() == 0x02, "poll_interrupt");

    FakePin pin;
    s.on_interrupt(onInt, &pin);
    check_true(pin.handler != nullptr && pin.trigger == InputPin::kFalling, "on_interrupt_subscribes");
    c.irqStatus = 0x01;
    pin.handler();
    check_true(seen.size() == 1 && seen[0] == 0x01, "on_interrupt_callback");
    s.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");

    uint8_t part = 0, hw = 0, rev = 0;
    check_true(s.read_id(part, hw, rev) && part == 0x33 && hw == 0x03 && rev == 0x10, "read_id");

    check_true(s.set_i2c_address(0x30) && c.params[0x00] == 0x30, "set_i2c_address");
    check_true(!s.set_i2c_address(0x80), "set_i2c_address_invalid");

    check_true(s.reset() && c.params[0x01] == 0x03 && c.params[0x02] == 0x18, "reset_defaults");
    check_true(s.read(uv, white) && uv == 0x012345 && white == -5, "reset_read");

    Si1133Mock stuck;
    Si1133Minimal s2(stuck);
    check_true(s2.init(), "init_second");
    stuck.ignoreCommands = true;
    check_true(!s2.read(uv, white), "command_timeout");

    Si1133Mock bad;
    bad.partId = 0x32;
    Si1133Minimal s3(bad);
    check_true(!s3.init(), "init_wrong_part");

    Si1133Mock nack;
    Si1133Full fn(nack);
    fn.init();
    nack.failNextWrites(1);
    check_true(!fn.read(uv, white), "read_reports_bus_error");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
