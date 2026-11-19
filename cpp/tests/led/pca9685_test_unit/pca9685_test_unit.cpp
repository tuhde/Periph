#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Pca9685.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static bool eq(const Bytes& a, std::initializer_list<uint8_t> b) { return a == Bytes(b); }

// Data writes only: the register-pointer writes of reads (one byte) are dropped.
static std::vector<Bytes> data(const I2CConnectionMock& c) {
    std::vector<Bytes> r;
    for (const auto& w : c.writes()) if (w.size() > 1) r.push_back(w);
    return r;
}

int main() {
    I2CConnectionMock c;
    Pca9685Full d(c);
    auto w = data(c);
    check_true(w.size() == 3 && eq(w[0], {0x00, 0x30}) && eq(w[1], {0xFE, 5}) && eq(w[2], {0x00, 0x20}), "init_sequence");

    c.clearWrites();
    check_true(d.set_duty(0, 0.5f), "set_duty");
    w = data(c);
    check_true(w.size() == 1 && eq(w[0], {0x06, 0, 0, 0x00, 0x08}), "duty_half");
    d.set_duty(15, 0.0f);
    check_true(eq(data(c).back(), {0x42, 0, 0, 0, 0x10}), "duty_zero_full_off");
    d.set_duty(1, 1.0f);
    check_true(eq(data(c).back(), {0x0A, 0, 0x10, 0, 0}), "duty_one_full_on");
    check_true(!d.set_duty(16, 0.5f) && !d.set_duty(0, 1.1f) && !d.set_duty(0, -0.1f), "duty_range");

    check_true(d.set_pwm(2, 409, 1228) && eq(data(c).back(), {0x0E, 0x99, 0x01, 0xCC, 0x04}), "set_pwm");
    uint16_t on = 0, off = 0;
    check_true(d.get_pwm(2, on, off) && on == 409 && off == 1228, "get_pwm");
    check_true(!d.set_pwm(0, 4096, 1) && !d.set_pwm(0, 5, 5) && !d.get_pwm(16, on, off), "pwm_range");

    d.set_full_on(3);
    check_true(eq(data(c).back(), {0x12, 0, 0x10, 0, 0}), "full_on");
    d.set_full_off(3);
    check_true(eq(data(c).back(), {0x12, 0, 0, 0, 0x10}), "full_off");
    d.set_all_duty(0.25f);
    check_true(eq(data(c).back(), {0xFA, 0, 0, 0x00, 0x04}), "all_duty");
    d.set_all_pwm(0, 100);
    check_true(eq(data(c).back(), {0xFA, 0, 0, 100, 0}), "all_pwm");

    c.setRegister(0x00, {0x20});
    c.clearWrites();
    check_true(d.set_frequency(50.0f), "set_frequency_ok");
    w = data(c);
    check_true(w.size() == 3 && eq(w[0], {0x00, 0x30}) && eq(w[1], {0xFE, 121}) && eq(w[2], {0x00, 0x20}), "set_frequency");
    check_true(fabsf(d.get_frequency() - 25e6f / (4096.0f * 122.0f)) < 0.01f, "get_frequency");
    d.set_frequency(1e6f);
    w = data(c);
    check_true(eq(w[w.size() - 2], {0xFE, 3}), "freq_clamp_min");
    d.set_frequency(1.0f);
    w = data(c);
    check_true(eq(w[w.size() - 2], {0xFE, 255}), "freq_clamp_max");
    c.setRegister(0x00, {0xA0});
    c.clearWrites();
    d.set_frequency(200.0f);
    w = data(c);
    check_true(eq(w[1], {0xFE, 30}) && eq(w.back(), {0x00, 0xA0}), "set_frequency_restart");
    check_true(!d.set_frequency(0.0f), "freq_range");

    c.setRegister(0xFE, {121});
    d.set_pulse_width(15, 0.0015f);
    check_true(eq(data(c).back(), {0x42, 0, 0, 307 & 0xFF, 307 >> 8}), "pulse_width");

    c.setRegister(0x00, {0x20});
    d.sleep();
    check_true(eq(data(c).back(), {0x00, 0x30}), "sleep");
    c.setRegister(0x00, {0xB0});
    c.clearWrites();
    d.wake();
    w = data(c);
    check_true(w.size() == 2 && eq(w[0], {0x00, 0x20}) && eq(w[1], {0x00, 0xA0}), "wake_restart");
    d.restart();
    check_true(eq(data(c).back(), {0x00, 0xA0}), "restart");

    c.setRegister(0x01, {0x04});
    check_true(d.set_output_mode(true, true, Pca9685Full::OFF_HIGHZ) && eq(data(c).back(), {0x01, 0x12}), "output_mode");
    d.set_output_mode();
    check_true(eq(data(c).back(), {0x01, 0x04}), "output_mode_default");
    check_true(!d.set_output_mode(false, false, 3), "output_mode_range");
    d.set_update_on_ack(true);
    check_true(eq(data(c).back(), {0x01, 0x0C}), "och_on");
    d.set_update_on_ack(false);
    check_true(eq(data(c).back(), {0x01, 0x04}), "och_off");

    c.setRegister(0x00, {0x20});
    c.clearWrites();
    d.use_external_clock();
    w = data(c);
    check_true(w.size() == 3 && eq(w[0], {0x00, 0x30}) && eq(w[1], {0x00, 0x70}) && eq(w[2], {0x00, 0x60}), "extclk");

    c.setRegister(0x00, {0x20});
    c.clearWrites();
    check_true(d.set_allcall(true), "allcall_ok");
    w = data(c);
    check_true(eq(w[0], {0x05, 0xE0}) && eq(w[1], {0x00, 0x21}), "allcall");
    d.set_subaddress(2, 0x71, true);
    w = data(c);
    check_true(eq(w[w.size() - 2], {0x03, 0xE2}) && eq(w.back(), {0x00, 0x25}), "subaddr");
    check_true(!d.set_subaddress(4, 0x71) && !d.set_allcall(true, 0x80), "addr_range");

    c.clearWrites();
    d.reset();
    w = data(c);
    check_true(eq(w[0], {0x00, 0x10}) && eq(w.back(), {0x00, 0x11}), "reset");

    I2CConnectionMock g;
    Pca9685Full::software_reset(g);
    check_true(g.writes().size() == 1 && eq(g.writes()[0], {0x06}), "software_reset");

    c.setRegister(0x00, {0x11, 0x04});
    uint8_t m1 = 0, m2 = 0;
    d.read_mode(m1, m2);
    check_true(m1 == 0x11 && m2 == 0x04, "read_mode");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
