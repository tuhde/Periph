#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Si7021.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tol, const char *label) {
    check_true(fabsf(got - expected) <= tol, label);
}

// The two ID commands (0xFA 0x0F, 0xFC 0xC9) address overlapping 8-bit "registers" in the byte-slot mock,
// so serve them from fixed buffers here.
class IdMock : public I2CConnectionMock {
public:
    uint8_t a[8] = {0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0};
    uint8_t b[6] = {0x15, 0x66, 0, 0x77, 0x88, 0};
protected:
    void _write_read(const uint8_t* data, size_t data_len, uint8_t* buf, size_t buf_len) override {
        if (data_len == 2 && data[0] == 0xFA) { for (size_t i = 0; i < buf_len; i++) buf[i] = a[i]; return; }
        if (data_len == 2 && data[0] == 0xFC) { for (size_t i = 0; i < buf_len; i++) buf[i] = b[i]; return; }
        I2CConnectionMock::_write_read(data, data_len, buf, buf_len);
    }
};

static uint8_t crc8(uint8_t a, uint8_t b) {
    uint8_t d[2] = {a, b};
    uint8_t crc = 0x00;
    for (int i = 0; i < 2; i++) {
        crc ^= d[i];
        for (int k = 0; k < 8; k++) crc = (crc & 0x80) ? (uint8_t)((crc << 1) ^ 0x31) : (uint8_t)(crc << 1);
    }
    return crc;
}

static void queue_frame(I2CConnectionMock& c, uint16_t code, bool corrupt = false) {
    uint8_t m = code >> 8, l = code & 0xFF, crc = crc8(m, l);
    if (corrupt) crc ^= 0xFF;
    c.queueRead({m, l, crc});
}

static uint16_t rh_code(float rh) { return (uint16_t)lroundf((rh + 6.0f) * 65536.0f / 125.0f); }
static uint16_t t_code(float t) { return (uint16_t)lroundf((t + 46.85f) * 65536.0f / 175.72f); }
static void queue_t(I2CConnectionMock& c, float t) { uint16_t k = t_code(t); c.queueRead({(uint8_t)(k >> 8), (uint8_t)(k & 0xFF)}); }

// First byte of every logged write.
static std::vector<uint8_t> cmds(const I2CConnectionMock& c) {
    std::vector<uint8_t> out;
    for (const auto& w : c.writes()) out.push_back(w[0]);
    return out;
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

static bool last_write_is(const I2CConnectionMock& c, uint8_t a, uint8_t b) {
    const auto& w = c.writes().back();
    return w.size() == 2 && w[0] == a && w[1] == b;
}

int main() {
    {
        I2CConnectionMock c;
        Si7021Full s(c);
        check_true(c.writes().empty(), "init_no_bus_access");

        queue_frame(c, rh_code(50.0f));
        queue_t(c, 25.0f);
        float t = 0, rh = 0;
        check_true(s.read(t, rh), "read_ok");
        check_true(eq(cmds(c), {0xF5, 0xE0}), "read_commands");
        check_close(rh, 50.0f, 0.02f, "read_humidity");
        check_close(t, 25.0f, 0.02f, "read_temperature");

        // NACKed read header: polls until ready
        c.failNextReads(3);
        queue_frame(c, rh_code(40.0f));
        queue_t(c, 20.0f);
        check_true(s.read(t, rh), "read_after_nacks");
        check_close(rh, 40.0f, 0.02f, "read_after_nacks_rh");

        // chip never ready
        c.failNextReads(1000);
        check_true(!s.read(t, rh), "timeout_fails");
        c.failNextReads(0);

        // CRC mismatch
        queue_frame(c, 0x6000, true);
        check_true(!s.read(t, rh), "crc_mismatch_fails");

        // clamping
        queue_frame(c, 0x0000); queue_t(c, 0.0f);
        s.read(t, rh);
        check_close(rh, 0.0f, 1e-6f, "clamp_low");
        queue_frame(c, 0xFFFC); queue_t(c, 0.0f);
        s.read(t, rh);
        check_close(rh, 100.0f, 1e-6f, "clamp_high");

        // single values and raw codes
        c.clearWrites();
        queue_frame(c, rh_code(30.0f));
        check_close(s.read_humidity(), 30.0f, 0.02f, "read_humidity_only");
        queue_frame(c, t_code(-10.0f));
        check_close(s.read_temperature(), -10.0f, 0.02f, "read_temperature_only");
        queue_t(c, 20.0f);
        check_close(s.read_temperature_previous(), 20.0f, 0.02f, "read_temperature_previous");
        check_true(eq(cmds(c), {0xF5, 0xF3, 0xE0}), "single_commands");
        uint16_t raw = 0;
        queue_frame(c, 0x1234);
        check_true(s.read_raw_humidity(raw) && raw == 0x1234, "read_raw_humidity");
        queue_frame(c, 0x4321);
        check_true(s.read_raw_temperature(raw) && raw == 0x4321, "read_raw_temperature");

        // hold master
        c.clearWrites();
        s.set_clock_stretching(true);
        queue_frame(c, rh_code(50.0f)); queue_t(c, 25.0f);
        s.read(t, rh);
        queue_frame(c, 0x6666);
        s.read_temperature();
        check_true(eq(cmds(c), {0xE5, 0xE0, 0xE3}), "hold_master_commands");
        s.set_clock_stretching(false);

        // user register
        c.setRegister(0xE7, {0x3A});
        c.clearWrites();
        check_true(s.set_resolution(11, 11), "set_resolution_11_11");
        check_true(last_write_is(c, 0xE6, 0xBB), "set_resolution_11_11_write");
        s.set_resolution(8, 12);
        check_true(last_write_is(c, 0xE6, 0x3B), "set_resolution_8_12_write");
        s.set_resolution(10, 13);
        check_true(last_write_is(c, 0xE6, 0xBA), "set_resolution_10_13_write");
        check_true(!s.set_resolution(12, 12), "set_resolution_invalid");
        uint8_t rb = 0, tb = 0;
        c.setRegister(0xE7, {0xBB});
        s.get_resolution(rb, tb);
        check_true(rb == 11 && tb == 11, "get_resolution_11_11");
        c.setRegister(0xE7, {0x3B});
        s.get_resolution(rb, tb);
        check_true(rb == 8 && tb == 12, "get_resolution_8_12");
        c.setRegister(0xE7, {0x3A});
        s.get_resolution(rb, tb);
        check_true(rb == 12 && tb == 14, "get_resolution_default");
        s.set_heater(true);
        check_true(last_write_is(c, 0xE6, 0x3E), "heater_on_write");
        c.setRegister(0xE7, {0x3E});
        check_true(s.is_heater_on(), "is_heater_on");
        s.set_heater(false);
        check_true(last_write_is(c, 0xE6, 0x3A), "heater_off_write");
        c.setRegister(0xE7, {0x3A});
        check_true(!s.is_heater_on() && !s.vdd_low(), "heater_off_vdd_ok");
        c.setRegister(0xE7, {0x7A});
        check_true(s.vdd_low(), "vdd_low");
        c.setRegister(0xE7, {0x3A});
        check_true(s.read_user_register() == 0x3A, "read_user_register");
        s.write_user_register(0x00);
        check_true(last_write_is(c, 0xE6, 0x3A), "write_user_register_keeps_reserved");
        check_close(s.conversion_time(), 0.017f, 1e-6f, "conversion_time");

        // reset and identification
        c.clearWrites();
        s.software_reset();
        check_true(eq(cmds(c), {0xFE}), "software_reset");
        c.setRegister(0x84, {0xFF});
        check_true(s.firmware_revision() == 0xFF, "firmware_revision");
        IdMock ids;
        Si7021Full si(ids);
        check_true(si.serial_number() == 0x1122334415667788ULL, "serial_number");
        check_true(si.device_type() == 0x15, "device_type");

        // dew point 25 degC / 50 %RH -> 13.86 degC
        queue_frame(c, rh_code(50.0f)); queue_t(c, 25.0f);
        check_close(s.dew_point(), 13.86f, 0.05f, "dew_point");
    }
    {
        I2CConnectionMock c;
        Si7021Minimal m(c);
        float t, rh;
        queue_frame(c, rh_code(50.0f)); queue_t(c, 25.0f);
        check_true(m.read(t, rh), "minimal_class_read");
    }
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
