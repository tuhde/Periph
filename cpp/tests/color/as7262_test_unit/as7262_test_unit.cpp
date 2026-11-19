#include <stdio.h>
#include <string.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "As7262.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

// AS7262 simulator: STATUS/WRITE/READ hardware registers over virtual registers.
class Sim : public I2CConnectionMock {
public:
    uint8_t v[0x2C];
    int pending = -1;
    int out = -1;
    bool stall = false;
    std::vector<std::pair<uint8_t, uint8_t>> vwrites;

    Sim() {
        memset(v, 0, sizeof(v));
        v[0] = 0x40; v[1] = 0x3E; v[2] = 0x41; v[3] = 0x21; v[4] = 0x08; v[5] = 0xFF; v[6] = 25;
    }

    void read(uint32_t reg, uint8_t* buf, size_t len) override {
        _setOk(true);
        if (reg == 0x00) {
            buf[0] = stall ? 0x02 : (out >= 0 ? 0x01 : 0x00);
        } else if (reg == 0x02) {
            buf[0] = (uint8_t)(out >= 0 ? out : 0);
            out = -1;
        } else {
            for (size_t i = 0; i < len; i++) buf[i] = 0;
        }
    }

    void write(uint32_t reg, const uint8_t* data, size_t len) override {
        (void)reg; (void)len;
        _setOk(true);
        uint8_t b = data[0];
        if (pending >= 0) {
            int r = pending;
            pending = -1;
            vwrites.push_back({(uint8_t)r, b});
            if (r == 4) {
                if (b & 0x80) { v[4] = 0x08; v[5] = 0xFF; v[7] = 0; return; }
                v[4] = b | (v[4] & 0x02);
                if (((b >> 2) & 3) == 3) v[4] |= 0x02;
            } else {
                v[r] = b;
            }
        } else if (b & 0x80) {
            pending = b & 0x7F;
        } else {
            out = v[b];
            if (b >= 0x08) v[4] &= ~0x02;
        }
    }

    bool wrote(uint8_t r, uint8_t val) const {
        for (auto& w : vwrites) if (w.first == r && w.second == val) return true;
        return false;
    }

    void load_cal(const float* f) {
        for (int i = 0; i < 6; i++) {
            uint32_t bits;
            memcpy(&bits, &f[i], 4);
            for (int k = 0; k < 4; k++) v[0x14 + 4 * i + k] = (bits >> (24 - 8 * k)) & 0xFF;
        }
    }
};

int main() {
    {
        Sim c;
        AS7262Full s(c);
        check_true(c.vwrites.empty(), "constructor_no_bus_access");
        check_true(s.begin(), "begin");
        check_true(c.v[5] == 0x3B, "init_int_t");
        check_true(((c.v[4] >> 4) & 3) == 2, "init_gain_16x");
        check_true(c.v[7] == 0, "init_leds_off");
        check_true(c.wrote(4, 0x80), "init_reset_written");
    }
    {
        Sim c;
        AS7262Minimal s(c);
        s.begin();
        const float vals[6] = {1.0f, 2.5f, 3.0f, 4.0f, 5.0f, 6.5f};
        c.load_cal(vals);
        AS7262Channels ch = {};
        check_true(s.read(ch), "read_ok");
        check_close(ch.violet, 1.0f, 1e-4f, "read_violet");
        check_close(ch.blue, 2.5f, 1e-4f, "read_blue");
        check_close(ch.red, 6.5f, 1e-4f, "read_red");
        check_true(c.wrote(4, 0x2C), "read_starts_one_shot");
        check_close(s.temperature(), 25.0f, 1e-4f, "temperature");
    }
    {
        Sim c;
        AS7262Full s(c);
        s.begin();
        for (int i = 0; i < 12; i++) c.v[0x08 + i] = (i % 2 == 0) ? 0x01 : 0x02;
        AS7262RawChannels raw = {};
        check_true(s.read_raw(raw) && raw.violet == 0x0102, "read_raw");
        uint16_t u = 0;
        check_true(s.read_channel_raw(5, u) && u == 0x0102, "read_channel_raw");
        check_true(!s.read_channel_raw(6, u), "read_channel_raw_range");
        const float vals[6] = {0, 0, 0, 0, 0, 7.25f};
        c.load_cal(vals);
        check_close(s.read_channel(5), 7.25f, 1e-4f, "read_channel_cal");
        check_true(isnan(s.read_channel(6)), "read_channel_range");

        check_true(s.set_gain(64), "set_gain_64");
        check_true(((c.v[4] >> 4) & 3) == 3 && s.get_gain() == 64.0f, "gain_64");
        check_true(s.set_gain(3.7f) && s.get_gain() == 3.7f, "gain_3_7");
        check_true(s.set_gain(1) && s.get_gain() == 1.0f, "gain_1x");
        check_true(!s.set_gain(5), "gain_invalid");
        check_true(s.set_integration_time(50.0f) && c.v[5] == 18, "int_time_50");
        check_close(s.get_integration_time(), 50.4f, 1e-3f, "int_time_readback");
        check_true(!s.set_integration_time(1.0f), "int_time_low");
        check_true(!s.set_integration_time(800.0f), "int_time_high");
        check_true(s.set_mode(2) && s.get_mode() == 2, "mode_2");
        check_true(s.set_continuous(0) && s.get_mode() == 0, "continuous_0");
        check_true(!s.set_continuous(3), "continuous_3_rejected");
        check_true(!s.set_mode(4), "mode_range");
        check_true(s.set_interrupt(true) && (c.v[4] & 0x40), "interrupt_on");
        check_true(s.set_interrupt(false) && !(c.v[4] & 0x40), "interrupt_off");

        s.set_led(true, 25.0f);
        check_true(c.v[7] == 0x18, "led_drv_25");
        s.set_indicator_led(true, 4.0f);
        check_true(c.v[7] == (0x18 | 0x05), "led_ind_4");
        s.set_led(false, 100.0f);
        check_true(c.v[7] == (0x30 | 0x05), "led_drv_off_keeps_ind");
        check_true(!s.set_led(true, 200.0f), "led_drv_too_high");
        check_true(!s.set_indicator_led(true, 9.0f), "led_ind_too_high");

        uint8_t a = 0, b = 0, d = 0;
        check_true(s.hardware_version(a, b) && a == 0x40 && b == 0x3E, "hardware_version");
        check_true(s.device_type() == 0x40, "device_type");
        check_true(s.firmware_version(a, b, d) && a == 2 && b == (uint8_t)(((0x21 & 0x0F) << 2) | 1) && d == 1, "firmware_version");
        check_true(s.write_virtual(0x05, 0x10), "write_virtual");
        uint8_t rb = 0;
        check_true(s.read_virtual(0x05, rb) && rb == 0x10, "read_virtual");
        check_true(s.soft_reset() && c.v[5] == 0xFF, "soft_reset_defaults");

        check_true(s.measure(), "measure");
        check_true(s.data_ready(), "data_ready");
        check_true(s.poll_interrupt() == 1, "poll_interrupt");
    }
    {
        Sim c;
        AS7262Full s(c);
        s.begin();
        c.stall = true;
        uint8_t rb = 0;
        check_true(!s.read_virtual(0x00, rb), "handshake_timeout");
    }
    {
        Sim c;
        c.v[0] = 0;
        AS7262Minimal s(c);
        check_true(!s.begin(), "begin_not_found");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
