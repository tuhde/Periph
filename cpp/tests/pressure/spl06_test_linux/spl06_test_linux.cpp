#include <cstdio>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Spl06.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
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
    SPL06Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check ID, read coefficients, apply defaults, () → bool
    SPL06Coefficients co;
    sensor.coefficients(co);
    CHECK(co.c0 != 0 || co.c1 != 0 || co.c00 != 0 || co.c10 != 0, "coefficients_decoded");
    float t = 0, p = 0, alt = 0;
    CHECK(sensor.read(t, p), "read_ok");                   // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
    NEAR(p, 30000.0f, 110000.0f, "read_pressure_range");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_pressure_single");
    CHECK(sensor.read_temperature(t) && t > -40.0f && t < 85.0f, "read_temperature_single");
    int32_t praw = 0, traw = 0;
    CHECK(sensor.read_raw(praw, traw), "read_raw");
    CHECK(sensor.compensate(praw, traw, 16, 1, t, p) && p >= 30000.0f && p <= 110000.0f, "compensate_raw");
    CHECK(sensor.altitude(alt) && alt > -500.0f && alt < 9000.0f, "altitude_range");
    static const uint8_t osrs[3] = {1, 8, 128};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.set_pressure_config(osrs[i], 1);
        CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_at_osr");
    }
    sensor.set_pressure_config(16, 8);
    sensor.set_temperature_config(1, 8);
    CHECK(sensor.start_continuous(), "start_continuous");
    CHECK(sensor.read_continuous(t, p) && p >= 30000.0f && p <= 110000.0f, "read_continuous");
    TEST_DELAY_MS(300);
    bool pr = false, tr = false;
    CHECK(sensor.data_ready(pr, tr) && pr, "data_ready");
    CHECK(sensor.stop(), "stop");
    sensor.flush_fifo();
    sensor.enable_fifo();
    sensor.start_continuous();
    TEST_DELAY_MS(1000);
    sensor.stop();
    bool empty = true, full = false;
    CHECK(sensor.fifo_status(empty, full) && !empty, "fifo_not_empty");
    static SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);
    CHECK(n >= 4, "fifo_read");
    bool values_ok = true;
    for (uint8_t i = 0; i < n; i++) {
        if (entries[i].kind == SPL06Full::KIND_PRESSURE && (entries[i].value < 30000.0f || entries[i].value > 110000.0f))
            values_ok = false;
    }
    CHECK(values_ok, "fifo_values");
    CHECK(sensor.fifo_status(empty, full) && empty, "fifo_empty_after_read");
    sensor.disable_fifo();
    sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);
    sensor.poll_interrupt();
    sensor.read(t, p);
    CHECK((sensor.poll_interrupt() & SPL06Full::SOURCE_PRESSURE) != 0, "poll_interrupt_pressure");
    sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
