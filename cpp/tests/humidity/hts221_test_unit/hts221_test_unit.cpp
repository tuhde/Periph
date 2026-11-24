#include <stdio.h>
#include <string.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Hts221.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

// Mock that clears CTRL_REG2.BOOT like the real chip (unless stuck).
class ChipMock : public I2CConnectionMock {
public:
    using I2CConnectionMock::write;
    bool stuck_boot = false;


    void write(uint32_t reg, const uint8_t* data, size_t len) override {
        I2CConnectionMock::write(reg, data, len);
        if (!stuck_boot && reg == 0x21 && len == 1) {
            setRegister(0x21, {(uint8_t)(data[0] & ~0x80)});
        }
    }

    uint8_t reg(uint16_t r) const {
        auto it = registers().find(r);
        return it == registers().end() ? 0 : it->second;
    }

    bool wrote(uint8_t r, uint8_t v) const {
        for (const auto& w : writes()) if (w.size() == 2 && w[0] == r && w[1] == v) return true;
        return false;
    }

    // Calibration block at 0xB0 (sub-address with auto-increment):
    // H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
    // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
    void preload() {
        setRegister(0xB0, {40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01});
        setRegister(0x0F, {0xBC});
        setRegister(0x27, {0x03});
        // Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
        setRegister(0xA8, {0x00, 0x50, 0x90, 0x01});
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
static void on_drdy(uint8_t s) { cb_count++; cb_status = s; }

int main() {
    {
        ChipMock c; c.preload();
        HTS221Minimal s(c);
        check_true(c.writes().empty(), "constructor_no_bus_access");
        check_true(s.init(), "init");
        check_true(c.wrote(0x20, 0x84), "init_writes_ctrl_reg1");
        check_true(c.writes()[0].size() == 1 && c.writes()[0][0] == 0xB0, "init_reads_calibration");
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_close(t, 15.0f, 1e-3f, "read_temperature");
        check_close(rh, 30.0f, 1e-3f, "read_humidity");
        check_true(c.wrote(0x21, 0x01), "read_starts_one_shot");
    }
    {
        ChipMock c; c.preload();
        c.setRegister(0xB6, {0x00, 0x00, 0, 0, 0x00, 0x10});   // H0_T0_OUT=0, H1_T0_OUT=0x1000
        HTS221Minimal s(c);
        s.init();
        c.setRegister(0xA8, {0xFF, 0x7F, 0, 0});
        float t, rh;
        s.read(t, rh);
        check_close(rh, 100.0f, 1e-4f, "humidity_clamped_100");
        c.setRegister(0xB6, {0x00, 0x40, 0, 0, 0x00, 0x60});
        s.init();
        c.setRegister(0xA8, {0x00, 0x80, 0, 0});
        s.read(t, rh);
        check_close(rh, 0.0f, 1e-4f, "humidity_clamped_0");
    }
    {
        ChipMock c; c.preload();
        c.setRegister(0x27, {0x00});
        HTS221Minimal s(c);
        s.init();
        float t, rh;
        check_true(!s.read(t, rh), "read_timeout");
    }
    {
        ChipMock c; c.preload();
        c.failNextWrites(1);
        HTS221Minimal s(c);
        check_true(!s.init(), "init_bus_error");
    }
    {
        ChipMock c; c.preload();
        c.setRegister(0xB5, {0x0D});   // T0 bits 9:8 = 01, T1 bits 9:8 = 11
        HTS221Full s(c);
        s.init();
        HTS221Calibration cal;
        s.read_calibration(cal);
        check_close(cal.t0_degc, (256 + 80) / 8.0f, 1e-6f, "calib_t0_msb");
        check_close(cal.t1_degc, (768 + 160) / 8.0f, 1e-6f, "calib_t1_msb");
    }
    {
        ChipMock c; c.preload();
        HTS221Full s(c);
        s.init();
        check_true(true, "inherits_minimal");
        check_close(s.read_humidity(), 30.0f, 1e-3f, "read_humidity_full");
        check_close(s.read_temperature(), 15.0f, 1e-3f, "read_temperature_full");
        int16_t raw = 0;
        check_true(s.read_raw_humidity(raw) && raw == 0x5000, "read_raw_humidity");
        check_true(s.read_raw_temperature(raw) && raw == 400, "read_raw_temperature");
        c.setRegister(0xA8, {0x00, 0xF0, 0, 0});
        check_true(s.read_raw_humidity(raw) && raw == -4096, "raw_negative");
        c.setRegister(0xA8, {0x00, 0x50, 0x90, 0x01});
        float t = 0, rh = 0;
        check_true(s.read_continuous(t, rh), "read_continuous");
        check_close(t, 15.0f, 1e-3f, "continuous_temperature");
        check_close(rh, 30.0f, 1e-3f, "continuous_humidity");
        HTS221Calibration cal;
        s.read_calibration(cal);
        check_true(cal.h0_rh == 20.0f && cal.h1_rh == 40.0f && cal.t0_degc == 10.0f && cal.t1_degc == 20.0f, "calib_points");
        check_true(cal.h0_t0_out == 0x4000 && cal.h1_t0_out == 0x6000 && cal.t0_out == 300 && cal.t1_out == 500, "calib_out");
        check_true(s.who_am_i() == 0xBC, "who_am_i");
        check_true(s.status() == 0x03, "status");
        check_true(s.data_ready(), "data_ready");
        check_true(s.poll_interrupt() == 0x03, "poll_interrupt");

        float hz = -1;
        check_true(s.set_output_data_rate(12.5f) && c.reg(0x20) == 0x87, "odr_12_5");
        check_true(s.get_output_data_rate(hz) && hz == 12.5f, "get_odr");
        check_true(s.set_output_data_rate(7) && c.reg(0x20) == 0x86, "odr_7");
        check_true(s.set_output_data_rate(1) && c.reg(0x20) == 0x85, "odr_1");
        check_true(s.set_output_data_rate(0) && c.reg(0x20) == 0x84, "odr_0");
        check_true(!s.set_output_data_rate(5), "odr_invalid");

        uint16_t ts = 0, hs = 0;
        check_true(s.set_averaging(256, 512) && c.reg(0x10) == 0x3F, "averaging_max");
        check_true(s.get_averaging(ts, hs) && ts == 256 && hs == 512, "get_averaging");
        check_true(s.set_averaging(2, 4) && c.reg(0x10) == 0x00, "averaging_min");
        check_true(s.set_averaging(16, 32) && c.reg(0x10) == 0x1B, "averaging_default");
        check_true(s.get_averaging(ts, hs) && ts == 16 && hs == 32, "get_averaging_default");
        check_true(!s.set_averaging(3, 32), "averaging_invalid_t");
        check_true(!s.set_averaging(16, 2), "averaging_invalid_h_low");
        check_true(!s.set_averaging(1, 32), "averaging_invalid_t_low");
        check_true(!s.set_averaging(16, 1024), "averaging_invalid_h_high");
        check_true(c.reg(0x10) == 0x1B, "averaging_invalid_not_written");

        check_true(s.set_block_data_update(false) && c.reg(0x20) == 0x80, "bdu_off");
        check_true(s.set_block_data_update(true) && c.reg(0x20) == 0x84, "bdu_on");
        check_true(s.power_down() && c.reg(0x20) == 0x04, "power_down");
        check_true(s.power_up() && c.reg(0x20) == 0x84, "power_up");

        check_true(s.set_heater(true) && s.is_heater_on() && (c.reg(0x21) & 0x02), "heater_on");
        check_true(s.set_heater(false) && !s.is_heater_on(), "heater_off");

        check_true(s.configure_drdy(true) && c.reg(0x22) == 0x04, "drdy_default");
        check_true(s.configure_drdy(true, false, true) && c.reg(0x22) == 0xC4, "drdy_low_open_drain");
        check_true(s.configure_drdy(false) && c.reg(0x22) == 0x00, "drdy_off");

        c.setRegister(0xB0, {60});
        check_true(s.reboot(), "reboot");
        s.read_calibration(cal);
        check_true(cal.h0_rh == 30.0f, "reboot_rereads_calibration");
        c.setRegister(0xB0, {40});
        s.reboot();

        float g = logf(0.30f) + 17.62f * 15.0f / (243.12f + 15.0f);
        check_close(s.dew_point(), 243.12f * g / (17.62f - g), 1e-2f, "dew_point");
    }
    {
        ChipMock c; c.preload();
        c.stuck_boot = true;
        HTS221Full s(c);
        s.init();
        check_true(!s.reboot(), "reboot_timeout");
    }
    {
        ChipMock c; c.preload();
        c.failNextWrites(1000);
        HTS221Full s(c);
        check_true(isnan(s.read_humidity()), "read_humidity_nan_on_error");
    }
    {
        ChipMock c; c.preload();
        FakePin pin;
        HTS221Full s(c);
        s.init();
        check_true(!s.on_interrupt(on_drdy), "interrupt_needs_pin");
        check_true(s.on_interrupt(on_drdy, &pin), "interrupt_subscribed");
        check_true(c.reg(0x22) == 0x04, "interrupt_drdy_enabled");
        check_true(pin.trigger == InputPin::kRising && pin.handler != nullptr, "interrupt_rising");
        pin.handler();
        check_true(cb_count == 1 && cb_status == 0x03, "interrupt_callback");
        s.off_interrupt();
        check_true(pin.handler == nullptr, "interrupt_unsubscribed");
        check_true(c.reg(0x22) == 0x00, "interrupt_drdy_disabled");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
