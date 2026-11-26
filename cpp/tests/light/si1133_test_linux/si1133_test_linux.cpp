#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Si1133.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x55
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((l), (c))
#define NEAR(v, lo, hi, l) check_true((l), ((v) >= (lo) && (v) <= (hi)))
#define TEST_DELAY_MS(ms) usleep((ms) * 1000)

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    Si1133Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check PART_ID, reset, UV + white channels, () → bool
    uint8_t part = 0, hw = 0, rev = 0;
    CHECK(sensor.read_id(part, hw, rev) && part == 0x33, "part_id");
    CHECK((hw & 0x1F) == 0x03, "hw_id_impl");
    uint8_t state = 0, counter = 0;
    bool err = true;
    CHECK(sensor.read_response(state, err, counter) && !err, "response_no_error");
    int32_t uv = 0, white = 0;
    CHECK(sensor.read(uv, white), "read_ok");             // Forced measurement, (uv&, white&) → bool
    NEAR(uv, -8388608L, 8388607L, "read_uv_range");
    CHECK(white > 0, "read_white_positive_in_light");
    int32_t x = 0;
    CHECK(sensor.read_uv(x), "read_uv_scratch");
    CHECK(sensor.read_white(x) && x > 0, "read_white_scratch");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_SMALL_IR), "read_ir_small");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_LARGE_IR), "read_ir_large");
    CHECK(sensor.configure_channel(2, Si1133Full::MUX_LARGE_IR, 5, 0, 0, false, false, 0), "configure_channel");
    CHECK(sensor.enable_channels(0x07), "enable_channels");
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    CHECK(sensor.read_channels(v, n) && n == 3, "read_channels_count");
    NEAR(v[2], 0L, 65535L, "read_channels_16bit");
    uint8_t p = 0;
    CHECK(sensor.write_parameter(0x1C, 0x05) && sensor.read_parameter(0x1C, p) && p == 0x05, "parameter_roundtrip");
    CHECK(sensor.read_parameter(0x01, p) && p == 0x07, "chan_list_readback");
    CHECK(sensor.set_threshold(0, 1000) && sensor.set_channel_threshold(0, 1), "threshold");
    uint8_t hi = 0, lo = 0;
    CHECK(sensor.read_parameter(0x25, hi) && sensor.read_parameter(0x26, lo) && hi == 0x03 && lo == 0xE8, "threshold_readback");
    sensor.set_channel_threshold(0, 0);
    sensor.enable_channels(0x03);
    sensor.poll_interrupt();
    CHECK(sensor.start_autonomous(0.1f), "start_autonomous");
    TEST_DELAY_MS(500);
    CHECK((sensor.poll_interrupt() & 0x03) != 0, "autonomous_irq");
    CHECK(sensor.read_channels(v, n, false) && n == 2, "autonomous_values");
    CHECK(sensor.pause(), "pause");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_response(state, err, counter) && (state & Si1133Full::RESP_SLEEP), "reset_sleep");
    CHECK(sensor.read(uv, white) && white > 0, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
