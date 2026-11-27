#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "InputPin.h"
#include "Rrh46410.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static uint8_t cs(const Bytes& d) {
    uint8_t s = 0;
    for (uint8_t b : d) s = (uint8_t)(s + b);
    return (uint8_t)~s;
}

static bool near(float a, float b) { return fabsf(a - b) < 1e-4f; }

// Simulates the RRH46410 request/response protocol with checksums.
class RRH46410Sim : public I2CConnectionMock {
public:
    Bytes response;
    bool haveResponse = false;
    bool allChecksumsOk = true;
    uint8_t mode = 0, counter = 0, gpio = 0x0C, gpioDir = 0, humidity = 0;
    bool hasResult = false, badChecksum = false;
    int statusOverride = -1;
    uint16_t pid = 0x2310;

    void sample(uint8_t n = 1) { hasResult = true; counter = (uint8_t)(counter + n); }

protected:
    void _write(const uint8_t* data, size_t len) override {
        I2CConnectionMock::_write(data, len);
        if (!ok()) return;
        Bytes d(data, data + len);
        uint8_t sum = 0;
        for (uint8_t b : d) sum = (uint8_t)(sum + b);
        if (sum != 0xFF) allChecksumsOk = false;
        uint8_t cmd = d[0];
        Bytes p(d.begin() + 1, d.end() - 1);
        uint8_t status = 0;
        Bytes out;
        switch (cmd) {
        case 0x00: out = {(uint8_t)(pid >> 8), (uint8_t)pid, 1, 2, 3}; break;
        case 0x01: out = {1, 2, 3, 4, 5, 6}; break;
        case 0x10: out = {mode}; break;
        case 0x11: mode = p[0]; counter = 0; hasResult = false; break;
        case 0x12: humidity = p[0]; break;
        case 0x18:
            if (!hasResult) status = 0x10;
            if (mode == 0x05) out = {counter, 0x01, 0xF4, 0x0B, 0xB8};
            else out = {counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3};
            break;
        case 0x20: gpioDir = p[0]; break;
        case 0x21: out = {gpio}; break;
        case 0x22: gpio |= p[0]; break;
        case 0x23: gpio &= (uint8_t)~p[0]; break;
        case 0x8F: mode = 0; haveResponse = false; return;
        default: status = 0x80;
        }
        if (statusOverride >= 0) status = (uint8_t)statusOverride;
        response = {status};
        response.insert(response.end(), out.begin(), out.end());
        uint8_t c = cs(response);
        if (badChecksum) c ^= 1;
        response.push_back(c);
        haveResponse = true;
    }

    void _read(uint8_t* buf, size_t len) override {
        memset(buf, 0xFF, len);
        if (!haveResponse) return;
        for (size_t i = 0; i < len && i < response.size(); i++) buf[i] = response[i];
    }
};

class FakePin : public InputPin {
public:
    Handler handler = nullptr;
    uint8_t trigger = 0xFF;
    bool onEdge(Handler h, uint8_t t) override { handler = h; trigger = t; return true; }
    void offEdge(Handler h) override { if (handler == h) handler = nullptr; }
    bool isHigh() override { return true; }
};

static int hits = 0;
static void onSample() { hits++; }

int main() {
    // --- init ---
    {
        RRH46410Sim sim;
        RRH46410Minimal m(sim);
        check_true(m.init(), "init_ok");
        check_true(sim.writes()[0] == Bytes({0x00, 0xFF}), "init_product_id_request");
        check_true(sim.writes()[1] == Bytes({0x11, 0x01, cs({0x11, 0x01})}), "init_set_mode_request");
        check_true(sim.allChecksumsOk, "init_request_checksums");
        check_true(sim.mode == 0x01, "init_mode_iaq");
    }
    {
        RRH46410Sim sim;
        sim.pid = 0x1234;
        RRH46410Minimal m(sim);
        check_true(!m.init() && m.last_error() == RRH46410Minimal::ERR_PRODUCT_ID, "init_wrong_pid");
    }
    {
        RRH46410Sim sim;
        sim.failNextWrites(3);
        RRH46410Minimal m(sim);
        check_true(m.init() && sim.mode == 0x01, "init_retries_until_ready");
    }
    {
        RRH46410Sim sim;
        sim.failNextWrites(1000);
        RRH46410Minimal m(sim);
        check_true(!m.init() && m.last_error() == RRH46410Minimal::ERR_TIMEOUT, "init_timeout");
    }

    // --- read ---
    {
        RRH46410Sim sim;
        RRH46410Minimal m(sim);
        m.init();
        sim.sample(5);
        RRH46410Result r;
        check_true(m.read(r), "read_ok");
        check_true(near(r.iaq, 2.5f), "read_iaq");
        check_true(near(r.tvoc_mg_m3, 0.5f), "read_tvoc");
        check_true(near(r.etoh_ppm, 3.0f), "read_etoh");
        check_true(r.eco2_ppm == 600, "read_eco2");
        check_true(r.relative_iaq == 30, "read_rel_iaq");
        check_true(r.sample == 5 && !r.warmed_up && !r.pbaq, "read_sample_not_warmed_up");
        check_true(sim.writes().back() == Bytes({0x18, 0xE7}), "read_request");
        sim.sample(95);
        check_true(m.read(r) && r.warmed_up && r.sample == 100, "read_warmed_up_after_100");
        sim.sample(200);
        check_true(m.read(r) && r.sample == 44 && r.warmed_up, "read_counter_wrap");

        sim.sample(1);
        sim.badChecksum = true;
        check_true(!m.read(r) && m.last_error() == RRH46410Minimal::ERR_CHECKSUM, "bad_checksum");
        sim.badChecksum = false;
        sim.statusOverride = 0x0A;
        check_true(!m.read(r) && m.last_error() == 0x0A, "damage_status");
        sim.statusOverride = -1;
    }

    // --- Full ---
    {
        RRH46410Sim sim;
        RRH46410Full f(sim);
        f.init();
        uint16_t pid = 0;
        uint8_t fw[3] = {0};
        check_true(f.product_id(pid, fw) && pid == 0x2310 && fw[0] == 1 && fw[1] == 2 && fw[2] == 3, "product_id");
        uint8_t tn[6];
        check_true(f.tracking_number(tn) && tn[0] == 1 && tn[5] == 6, "tracking_number");
        check_true(f.get_mode() == RRH46410Full::MODE_IAQ_2ND_GEN, "get_mode");
        RRH46410Result r;
        check_true(!f.read_results(r) && f.last_error() == RRH46410Full::ERR_NOT_READY, "read_results_not_ready");
        check_true(f.status().not_complete, "status_not_complete");
        sim.sample(3);
        check_true(f.read_results(r) && r.sample == 3 && r.eco2_ppm == 600, "read_results");
        check_true(f.status().raw == 0 && f.status().mode_error == 0, "status_ok");
        check_true(!f.wait_for_sample(10), "wait_for_sample_false");
        sim.sample();
        check_true(f.poll_interrupt(), "poll_interrupt_true");
        check_true(f.wait_for_sample(100) && f.last_result().sample == 4, "wait_for_sample_true");
        check_true(!f.poll_interrupt(), "poll_interrupt_false");

        check_true(f.set_humidity(50) && sim.humidity == 128, "set_humidity_code");
        check_true(f.set_humidity(100) && sim.humidity == 255, "set_humidity_100");
        check_true(!f.set_humidity(101) && f.last_error() == RRH46410Full::ERR_ARG, "set_humidity_range");

        check_true(f.set_mode(RRH46410Full::MODE_PBAQ) && sim.mode == 0x05, "set_mode_pbaq");
        check_true(f.sample_period() == 5.0f && f.warmup_samples() == 60, "pbaq_timing");
        check_true(f.sample_period(RRH46410Full::MODE_ULP) == 90.0f && f.warmup_samples(RRH46410Full::MODE_ULP) == 10, "ulp_timing");
        check_true(f.sample_period(RRH46410Full::MODE_IAQ_2ND_GEN) == 3.0f && f.warmup_samples(RRH46410Full::MODE_IAQ_2ND_GEN) == 100, "iaq_timing");
        sim.sample(2);
        check_true(f.read_results(r) && r.pbaq && near(r.tvoc_mg_m3, 0.5f) && near(r.etoh_ppm, 3.0f) && r.eco2_ppm == 0, "pbaq_decode");
        check_true(!f.set_mode(0x04) && f.last_error() == RRH46410Full::ERR_ARG, "set_mode_invalid");

        check_true(strcmp(RRH46410Full::air_quality_label(1.5f), "very good") == 0, "label_very_good");
        check_true(strcmp(RRH46410Full::air_quality_label(2.0f), "good") == 0, "label_good");
        check_true(strcmp(RRH46410Full::air_quality_label(3.9f), "medium") == 0, "label_medium");
        check_true(strcmp(RRH46410Full::air_quality_label(4.0f), "poor") == 0, "label_poor");
        check_true(strcmp(RRH46410Full::air_quality_label(5.0f), "bad") == 0, "label_bad");

        check_true(f.config_gpio(0x03) && sim.gpioDir == 0x03, "config_gpio");
        check_true(f.set_gpio(0x01) && (f.get_gpio() & 0x01), "set_gpio");
        check_true(f.clear_gpio(0x01) && !(f.get_gpio() & 0x01), "clear_gpio");
        check_true(!f.set_gpio(4) && f.last_error() == RRH46410Full::ERR_ARG, "gpio_mask_range");

        check_true(f.suspend() && sim.mode == 0, "suspend");
        check_true(!f.clean_sensor() && f.last_error() == RRH46410Full::ERR_ARG && sim.mode == 0, "clean_requires_confirm");

        uint8_t out[1];
        check_true(f.send_command(0x21, nullptr, 0, out, 1) == 1 && out[0] == sim.gpio, "send_command");
        f.send_command(0x55);
        check_true(f.status().invalid_command, "send_command_invalid_status");

        f.set_mode(RRH46410Full::MODE_IAQ_2ND_GEN);
        check_true(f.reset(), "reset_ok");
        bool sent = false;
        for (const auto& w : sim.writes()) if (w == Bytes({0x8F, 0x70})) sent = true;
        check_true(sent, "reset_request");
        check_true(f.sample_period() == 0.0f && sim.mode == 0, "reset_suspend");
    }

    // --- interrupt ---
    {
        RRH46410Sim sim;
        RRH46410Full f(sim);
        f.init();
        FakePin pin;
        f.on_interrupt(onSample, &pin);
        check_true(pin.handler != nullptr && pin.trigger == InputPin::kRising, "on_interrupt_rising");
        pin.handler();
        check_true(hits == 1, "on_interrupt_callback");
        f.off_interrupt();
        check_true(pin.handler == nullptr, "off_interrupt");
        f.on_interrupt(onSample);
        check_true(hits == 1, "on_interrupt_no_pin_noop");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
