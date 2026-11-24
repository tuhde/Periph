#include <cstdio>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Mpl3115a2.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x60
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
    MPL3115A2Full sensor(connection);

    sensor.init();                                          // Reset and apply defaults, () → bool
    CHECK(sensor.who_am_i() == 0xC4, "who_am_i");
    float p = 0, t = 0;
    CHECK(sensor.read(p, t), "read ok");                    // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
    NEAR(p, 20000.0f, 110000.0f, "read_pressure_range");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    CHECK(sensor.read_pressure(p), "read_pressure_single");
    NEAR(p, 20000.0f, 110000.0f, "read_pressure_single_range");
    CHECK(sensor.read_temperature(t), "read_temperature_single");
    NEAR(t, -40.0f, 85.0f, "read_temperature_single_range");
    static const uint8_t ratios[3] = {1, 8, 128};
    for (uint8_t i = 0; i < 3; i++) {
        CHECK(sensor.set_oversample(ratios[i]), "set_oversample");
        CHECK(sensor.get_oversample() == ratios[i], "oversample_roundtrip");
        CHECK(sensor.read_pressure(p) && p >= 20000.0f && p <= 110000.0f, "read_at_osr");
    }
    sensor.set_oversample(16);
    CHECK(sensor.set_sea_level_pressure(101325.0f), "set_sea_level_pressure");
    float alt = 0;
    CHECK(sensor.read_altitude(alt) && alt > -500.0f && alt < 9000.0f, "read_altitude_range");
    CHECK(sensor.set_mode(false), "set_mode_barometer");
    CHECK(sensor.start_continuous(0), "start_continuous");
    CHECK(sensor.is_active(), "is_active");
    float v = 0;
    CHECK(sensor.read_continuous(v, t) && v >= 20000.0f && v <= 110000.0f, "read_continuous");
    CHECK(sensor.stop_continuous(), "stop_continuous");
    CHECK(!sensor.is_active(), "is_standby");
    CHECK(sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 4), "fifo_setup");
    sensor.start_continuous(0);
    TEST_DELAY_MS(5000);
    bool ov = false, wm = false;
    uint8_t cnt = 0;
    CHECK(sensor.fifo_status(ov, wm, cnt) && cnt >= 2, "fifo_collects");
    float fv[32], ft[32];
    CHECK(sensor.fifo_read(fv, ft, 32) >= 2, "fifo_read_count");
    sensor.stop_continuous();
    CHECK(sensor.fifo_setup(MPL3115A2Full::FIFO_OFF), "fifo_off");
    CHECK(sensor.enable_interrupt(MPL3115A2Full::SOURCE_DRDY, 1), "enable_interrupt");
    CHECK(sensor.disable_interrupt(MPL3115A2Full::SOURCE_DRDY), "disable_interrupt");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_pressure(p) && p >= 20000.0f && p <= 110000.0f, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
