#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <deque>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Spl06.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool near(float a, float b, float tol) { return fabsf(a - b) < tol; }

// Float compensation on 24-bit values: 0.05 Pa / 0.001 °C is well inside the chip's 0.06 Pa resolution.
static const float T_EXP = 25.0054779f;
static const float P_EXP = 98263.861f;

// Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
static const std::initializer_list<uint8_t> COEF = {
    0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
    0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA};

// MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops queued FIFO entries.
class Spl06Mock : public I2CConnectionMock {
public:
    uint8_t flags = 0xF0;
    bool fifoMode = false;
    std::deque<std::vector<uint8_t>> fifo;

    Spl06Mock() {
        setRegister(0x0D, {0x10});
        setRegister(0x08, {0xF0});
        setRegister(0x10, COEF);
        setRegister(0x00, {0xFE, 0x94, 0xB7, 0x02, 0x5C, 0x28});  // praw -93001, traw 154664
    }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (len >= 2 && data[0] == 0x08) setRegister(0x08, { (uint8_t)((data[1] & 0x07) | flags) });
    }

    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        if (fifoMode && data[0] == 0x0B) setRegister(0x0B, { (uint8_t)(fifo.empty() ? 0x01 : 0x00) });
        if (fifoMode && data[0] == 0x00 && buf_len == 3 && !fifo.empty()) {
            for (size_t i = 0; i < 3; i++) buf[i] = fifo.front()[i];
            fifo.pop_front();
            return;
        }
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
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

static int lastWriteTo(Spl06Mock& c, uint8_t reg) {
    int v = -1;
    for (const auto& w : c.writes()) if (w.size() >= 2 && w[0] == reg) v = w[1];
    return v;
}

static size_t countWritesTo(Spl06Mock& c, uint8_t reg) {
    size_t n = 0;
    for (const auto& w : c.writes()) if (w.size() >= 2 && w[0] == reg) n++;
    return n;
}

static std::vector<uint8_t> seen;
static void onInt(uint8_t status) { seen.push_back(status); }

int main() {
    Spl06Mock c;
    SPL06Full s(c);
    check_true(c.writes().empty(), "constructor_no_bus_access");
    check_true(s.init(), "init");
    check_true(lastWriteTo(c, 0x07) == 0x80, "init_tmp_cfg");
    check_true(lastWriteTo(c, 0x06) == 0x04, "init_prs_cfg");
    check_true(lastWriteTo(c, 0x09) == 0x04, "init_cfg_reg");

    SPL06Coefficients co;
    s.coefficients(co);
    check_true(co.c0 == 204 && co.c1 == -261, "coef_c0_c1");
    check_true(co.c00 == 80469 && co.c10 == -54769, "coef_c00_c10");
    check_true(co.c01 == -2659 && co.c11 == 1258 && co.c20 == -10529, "coef_c01_c11_c20");
    check_true(co.c21 == 94 && co.c30 == -1350, "coef_c21_c30");

    float t = 0, p = 0;
    check_true(s.read(t, p), "read_ok");
    check_true(near(t, T_EXP, 1e-3f), "read_temperature");
    check_true(near(p, P_EXP, 0.05f), "read_pressure");
    std::vector<int> meas;
    for (const auto& w : c.writes()) if (w.size() >= 2 && w[0] == 0x08) meas.push_back(w[1]);
    check_true(meas.size() >= 2 && meas[meas.size() - 2] == 0x02 && meas.back() == 0x01,
               "read_temperature_then_pressure");
    check_true(s.read_pressure(p) && near(p, P_EXP, 0.05f), "read_pressure_single");
    check_true(s.read_temperature(t) && near(t, T_EXP, 1e-3f), "read_temperature_single");
    check_true(lastWriteTo(c, 0x08) == 0x02, "read_temperature_command");
    int32_t praw = 0, traw = 0;
    check_true(s.read_raw(praw, traw) && praw == -93001 && traw == 154664, "read_raw");

    check_true(s.compensate(-93001, 154664, 16, 1, t, p) && near(p, P_EXP, 0.05f) && near(t, T_EXP, 1e-3f),
               "compensate");
    check_true(s.compensate(-93001, 154664, 8, 2, t, p) && near(p, 80852.283f, 0.05f) && near(t, 76.33516f, 1e-3f),
               "compensate_other_scale");
    check_true(!s.compensate(0, 0, 3, 1, t, p), "compensate_invalid");

    float alt = 0;
    check_true(s.altitude(alt) && near(alt, 258.03f, 0.05f), "altitude");
    check_true(s.altitude(alt, 98263.861f) && near(alt, 0.0f, 0.05f), "altitude_reference");

    check_true(s.set_pressure_config(128, 8) && lastWriteTo(c, 0x06) == 0x37, "prs_cfg");
    check_true(lastWriteTo(c, 0x09) & 0x04, "p_shift_set");
    check_true(s.set_pressure_config(8, 1) && lastWriteTo(c, 0x06) == 0x03, "prs_cfg_8x");
    check_true((lastWriteTo(c, 0x09) & 0x04) == 0, "p_shift_cleared");
    check_true(s.set_temperature_config(32, 4) && lastWriteTo(c, 0x07) == 0xA5, "tmp_cfg");
    check_true(lastWriteTo(c, 0x09) & 0x08, "t_shift_set");
    check_true(s.set_temperature_config(2, 1) && lastWriteTo(c, 0x07) == 0x81, "tmp_cfg_ext_kept");
    check_true((lastWriteTo(c, 0x09) & 0x08) == 0, "t_shift_cleared");
    check_true(s.read(t, p) && near(p, 80852.283f, 0.05f), "read_uses_new_scale");
    check_true(!s.set_pressure_config(3, 1) && !s.set_pressure_config(16, 3), "pressure_config_invalid");
    check_true(!s.set_temperature_config(0), "temperature_config_invalid");
    s.set_pressure_config();
    s.set_temperature_config();

    check_true(s.start_continuous() && lastWriteTo(c, 0x08) == 0x07, "continuous_both");
    check_true(s.start_continuous(true, false) && lastWriteTo(c, 0x08) == 0x05, "continuous_pressure");
    check_true(s.start_continuous(false, true) && lastWriteTo(c, 0x08) == 0x06, "continuous_temperature");
    check_true(!s.start_continuous(false, false), "continuous_none_rejected");
    s.start_continuous();
    size_t n = countWritesTo(c, 0x08);
    check_true(s.read_continuous(t, p) && near(p, P_EXP, 0.05f) && near(t, T_EXP, 1e-3f), "read_continuous");
    check_true(countWritesTo(c, 0x08) == n, "read_continuous_no_command");
    check_true(s.read(t, p) && near(p, P_EXP, 0.05f) && countWritesTo(c, 0x08) == n, "read_in_background");
    bool pr = false, tr = false;
    check_true(s.data_ready(pr, tr) && pr && tr, "data_ready");
    c.setRegister(0x08, {0xD7});
    check_true(s.data_ready(pr, tr) && pr && !tr, "data_ready_pressure_only");
    c.setRegister(0x08, {0xF7});
    check_true(s.stop() && lastWriteTo(c, 0x08) == 0x00, "stop");

    check_true(s.enable_fifo() && (lastWriteTo(c, 0x09) & 0x02), "fifo_enable");
    c.fifoMode = true;
    c.fifo.push_back({0x02, 0x5C, 0x28});
    c.fifo.push_back({0xFE, 0x94, 0xB7});
    SPL06Full::FifoEntry entries[32];
    uint8_t cnt = s.read_fifo(entries, 32);
    check_true(cnt == 2, "fifo_count");
    check_true(entries[0].kind == SPL06Full::KIND_TEMPERATURE && near(entries[0].value, T_EXP, 1e-3f),
               "fifo_temperature");
    check_true(entries[1].kind == SPL06Full::KIND_PRESSURE && near(entries[1].value, 98264.047f, 0.05f),
               "fifo_pressure");
    check_true(s.read_fifo(entries, 32) == 0, "fifo_empty_read");
    c.fifo.push_back({0x02, 0x5C, 0x28});
    c.fifo.push_back({0x02, 0x5C, 0x28});
    check_true(s.read_fifo(entries, 1) == 1, "fifo_capacity_respected");
    c.fifo.clear();
    c.fifoMode = false;
    bool empty = true, full = false;
    c.setRegister(0x0B, {0x02});
    check_true(s.fifo_status(empty, full) && !empty && full, "fifo_status_full");
    c.setRegister(0x0B, {0x01});
    check_true(s.fifo_status(empty, full) && empty && !full, "fifo_status_empty");
    check_true(s.flush_fifo() && lastWriteTo(c, 0x0C) == 0x80, "fifo_flush");
    check_true(s.disable_fifo() && (lastWriteTo(c, 0x09) & 0x02) == 0, "fifo_disable");

    check_true(s.enable_interrupt(SPL06Full::SOURCE_PRESSURE) && (lastWriteTo(c, 0x09) & 0x20), "int_enable_pressure");
    s.enable_interrupt(SPL06Full::SOURCE_TEMPERATURE | SPL06Full::SOURCE_FIFO_FULL);
    check_true((lastWriteTo(c, 0x09) & 0x70) == 0x70, "int_enable_combined");
    check_true(lastWriteTo(c, 0x09) & 0x04, "int_keeps_p_shift");
    s.disable_interrupt(SPL06Full::SOURCE_PRESSURE | SPL06Full::SOURCE_FIFO_FULL);
    check_true((lastWriteTo(c, 0x09) & 0x70) == 0x10, "int_disable");
    c.setRegister(0x0A, {0xFA});
    check_true(s.poll_interrupt() == 0x02, "poll_interrupt_masked");

    FakePin pin;
    s.on_interrupt(onInt, &pin);
    check_true(pin.handler != nullptr && pin.trigger == InputPin::kFalling, "on_interrupt_falling_edge");
    c.setRegister(0x0A, {0x01});
    pin.handler();
    check_true(seen.size() == 1 && seen[0] == 0x01, "on_interrupt_dispatch");
    c.setRegister(0x0A, {0x00});
    pin.handler();
    check_true(seen.size() == 1, "on_interrupt_no_spurious");
    s.off_interrupt();
    check_true(pin.handler == nullptr, "off_interrupt");
    s.disable_interrupt(SPL06Full::SOURCE_TEMPERATURE);

    s.set_pressure_config(64, 2);
    check_true(s.reset(), "reset_ok");
    check_true(lastWriteTo(c, 0x0C) == 0x09, "reset_command");
    check_true(lastWriteTo(c, 0x06) == 0x04 && lastWriteTo(c, 0x07) == 0x80 && lastWriteTo(c, 0x09) == 0x04,
               "reset_defaults");
    check_true(s.read(t, p) && near(p, P_EXP, 0.05f), "read_after_reset");

    Spl06Mock cm;
    SPL06Minimal m(cm);
    check_true(m.init() && m.read(t, p) && near(p, P_EXP, 0.05f) && near(t, T_EXP, 1e-3f), "minimal_read");

    Spl06Mock bad;
    bad.setRegister(0x0D, {0x58});
    SPL06Minimal mb(bad);
    check_true(!mb.init(), "init_bad_id");

    Spl06Mock slow;
    slow.setRegister(0x08, {0x40});
    SPL06Minimal ms(slow);
    check_true(!ms.init(), "init_coef_not_ready");

    Spl06Mock nack;
    SPL06Full fn(nack);
    fn.init();
    nack.failNextWrites(1);
    check_true(!fn.read(t, p), "read_reports_bus_error");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
