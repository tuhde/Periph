#include <stdio.h>
#include <string.h>
#include <math.h>
#include <map>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Si1132.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

// Models the Si1132 sequencer: RESPONSE counter, parameter RAM, forced measurement.
class ChipMock : public I2CConnectionMock {
public:
    using I2CConnectionMock::write;
    std::map<uint8_t, uint8_t> params;
    std::vector<uint8_t> cmds;
    uint16_t vis = 1234, ir = 567, aux = 250;
    bool stuck = false;
    int cmd_error = -1;

    ChipMock(uint8_t seq_id = 0x08, uint8_t part_id = 0x32) {
        setBytes(0x00, part_id);
        setBytes(0x02, seq_id);
    }

    void setBytes(uint16_t r, uint8_t v) { setRegister(r, {v}); }
    uint8_t reg(uint16_t r) const {
        auto it = registers().find(r);
        return it == registers().end() ? 0 : it->second;
    }
    bool wrote(std::initializer_list<uint8_t> bytes) const {
        std::vector<uint8_t> b(bytes);
        for (const auto& w : writes()) if (w == b) return true;
        return false;
    }

    void write(uint32_t r, const uint8_t* data, size_t len) override {
        I2CConnectionMock::write(r, data, len);
        if (len != 1) return;
        if (r == 0x21) setBytes(0x21, (uint8_t)(reg(0x21) & ~data[0]));
        else if (r == 0x18) { cmds.push_back(data[0]); command(data[0]); }
    }

    void count() { setBytes(0x20, (uint8_t)((reg(0x20) + 1) & 0x0F)); }

    void command(uint8_t cmd) {
        if (cmd_error >= 0 && cmd != 0x00) { setBytes(0x20, (uint8_t)cmd_error); return; }
        if ((cmd & 0xE0) == 0xA0) {
            params[cmd & 0x1F] = reg(0x17);
            setBytes(0x2E, params[cmd & 0x1F]);
            count();
        } else if ((cmd & 0xE0) == 0x80) {
            setBytes(0x2E, params[cmd & 0x1F]);
            count();
        } else if (cmd == 0x00) {
            setBytes(0x20, 0);
        } else if (cmd == 0x06) {
            if (!stuck) measure();
            count();
        } else if (cmd == 0x12) {
            for (int i = 0; i < 12; i++) setBytes(0x22 + i, (uint8_t)(0x10 + i));
            count();
        } else if (cmd != 0x01) {
            count();
        }
    }

    void measure() {
        uint16_t a = aux;
        if (params[0x01] == 0x40) a = params[0x0F] == 0x65 ? 0x4000 : 0x2000;
        setBytes(0x22, vis & 0xFF); setBytes(0x23, vis >> 8);
        setBytes(0x24, ir & 0xFF);  setBytes(0x25, ir >> 8);
        setBytes(0x2C, a & 0xFF);   setBytes(0x2D, a >> 8);
        setBytes(0x21, (uint8_t)(reg(0x21) | 0x01));
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = 99;
    bool onEdge(Handler h, uint8_t t = kFalling) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (handler == h) handler = nullptr; }
    bool isHigh() override { return false; }
};

static int cb_count = 0;
static uint8_t cb_status = 0;
static void on_int(uint8_t s) { cb_count++; cb_status = s; }

int main() {
    {
        ChipMock c;
        Si1132Minimal s(c);
        check_true(c.writes().empty(), "constructor_no_bus_access");
        check_true(s.init(), "init");
        check_true(c.wrote({0x07, 0x17}), "init_hw_key");
        check_true(c.wrote({0x13, 0x7B, 0x6B, 0x01, 0x00}), "init_ucoef");
        check_true(c.params[0x01] == 0xB0, "init_chlist");
        check_true(c.reg(0x04) == 0x01, "init_irq_enable");
        float uv = 0; uint16_t v = 0, i = 0;
        check_true(s.read(uv, v, i), "read_ok");
        check_close(uv, 2.5f, 1e-4f, "read_uv_index");
        check_true(v == 1234, "read_visible");
        check_true(i == 567, "read_infrared");
        check_true(c.reg(0x21) == 0, "read_clears_status");
    }
    {
        ChipMock c(0x08, 0x45);
        Si1132Minimal s(c);
        check_true(!s.init(), "wrong_part_id");
    }
    {
        ChipMock c; c.stuck = true;
        Si1132Minimal s(c);
        s.init();
        float uv; uint16_t v, i;
        check_true(!s.read(uv, v, i), "measurement_timeout");
    }
    {
        ChipMock c;
        Si1132Full f(c);
        f.init();
        c.cmd_error = 0x80;
        check_true(!f.set_gain(1, 1), "response_error");
        check_true(c.cmds.back() == 0x00, "response_error_nop");
    }
    {
        ChipMock c;
        Si1132Full f(c);
        f.init();
        check_close(f.read_uv_index(), 2.5f, 1e-4f, "read_uv_index_full");
        uint16_t a = 0, b = 0, d = 0;
        check_true(f.read_visible(a) && a == 1234, "read_visible_full");
        check_true(f.read_infrared(a) && a == 567, "read_infrared_full");
        check_true(f.read_raw(a, b, d) && a == 1234 && b == 567 && d == 250, "read_raw");
        check_true(f.read_temperature_raw(a) && a == 0x4000, "read_temperature_raw");
        check_true(c.params[0x0F] == 0x65, "temperature_mux");
        check_true(c.params[0x01] == 0xB0, "temperature_restores_chlist");
        check_true(f.read_vdd_raw(a) && a == 0x2000, "read_vdd_raw");
        check_true(c.params[0x0F] == 0x75, "vdd_mux");

        check_true(f.set_channels(true, false, true) && c.params[0x01] == 0xA0, "set_channels");
        check_true(f.set_channels(false, true, false, true) && c.params[0x01] == 0x50, "set_channels_aux");
        check_true(!f.set_channels(false, false, false), "set_channels_none");
        check_true(!f.set_channels(true, true, true, true), "set_channels_uv_aux");
        f.set_channels(true, true, true);

        check_true(f.set_gain(3, 5), "set_gain");
        check_true(c.params[0x11] == 3 && c.params[0x10] == 0x40, "gain_vis");
        check_true(c.params[0x1E] == 5 && c.params[0x1D] == 0x20, "gain_ir");
        check_true(!f.set_gain(8, 0), "gain_invalid");

        check_true(f.set_high_range(true, false), "set_high_range");
        check_true(c.params[0x12] == 0x20 && c.params[0x1F] == 0x00, "high_range");
        check_true(f.set_alignment(true, true) && c.params[0x06] == 0x30, "alignment");

        const uint8_t uc[4] = {1, 2, 3, 4};
        check_true(f.set_uv_coefficients(uc) && c.wrote({0x13, 1, 2, 3, 4}), "uv_coefficients");

        uint8_t cal[12];
        check_true(f.read_calibration(cal), "read_calibration");
        check_true(cal[0] == 0x10 && cal[11] == 0x1B, "read_calibration_data");

        check_true(f.start_autonomous(0.5f), "autonomous");
        check_true(c.reg(0x08) == 0x80 && c.reg(0x09) == 0x3E, "autonomous_rate");
        check_true(c.cmds.back() == 0x0E, "autonomous_command");
        check_true(f.start_autonomous(0.0f) && c.cmds.back() == 0x0A, "autonomous_stop");
        check_true(!f.start_autonomous(3.0f), "autonomous_invalid");

        check_true(f.pause() && c.cmds.back() == 0x0A, "pause");
        check_true(f.force() && c.cmds.back() == 0x06, "force");
        check_true(f.measurement_ready(), "measurement_ready");
        c.setBytes(0x21, 0);
        check_true(!f.measurement_ready(), "measurement_not_ready");

        uint8_t cnt = 0, err = 0;
        c.setBytes(0x20, 0x05);
        check_true(f.read_response(cnt, err) && cnt == 5 && err == 0, "read_response_counter");
        c.setBytes(0x20, 0x8C);
        check_true(f.read_response(cnt, err) && cnt == 0 && err == 0x8C, "read_response_error");

        check_true(f.set_i2c_address(0x61), "set_i2c_address");
        check_true(c.params[0x00] == 0x61 && c.cmds.back() == 0x02, "i2c_address_params");

        check_true(f.reset(), "reset");
        check_true(c.wrote({0x18, 0x01}), "reset_command");
        check_true(c.writes().back() == std::vector<uint8_t>({0x07, 0x17}), "reset_rewrites_hw_key");

        check_true(f.enable_interrupt() && c.reg(0x03) == 0x01 && c.reg(0x04) == 0x01, "enable_interrupt");
        check_true(f.disable_interrupt() && c.reg(0x03) == 0x00, "disable_interrupt");
        c.setBytes(0x21, 0x01);
        check_true(f.poll_interrupt() == 0x01, "poll_interrupt");
        check_true(c.reg(0x21) == 0, "poll_interrupt_clears");
        check_true(f.poll_interrupt() == 0, "poll_interrupt_none");
    }
    {
        ChipMock c(0x01);
        Si1132Full f(c);
        f.init();
        f.start_autonomous(0.5f);
        check_true(c.reg(0x0A) == 0x80 && c.reg(0x08) == 0x3E, "autonomous_erratum");
    }
    {
        ChipMock c;
        Si1132Full f(c);
        f.init();
        check_true(!f.on_interrupt(on_int), "on_interrupt_no_pin");
        FakePin pin;
        check_true(f.on_interrupt(on_int, &pin), "on_interrupt_subscribes");
        check_true(pin.trigger == InputPin::kFalling, "on_interrupt_falling");
        check_true(c.reg(0x03) == 0x01, "on_interrupt_enables");
        c.setBytes(0x21, 0x01);
        pin.handler();
        check_true(cb_count == 1 && cb_status == 0x01, "on_interrupt_callback");
        f.off_interrupt();
        check_true(pin.handler == nullptr, "off_interrupt_unsubscribes");
        check_true(c.reg(0x03) == 0x00, "off_interrupt_releases");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
