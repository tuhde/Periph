#include <cstdio>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Ms5611.h"

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
    MS5611Full sensor(connection);

    CHECK(sensor.init(), "init ok");                        // Reset, read PROM, verify CRC-4, () → bool
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    CHECK(sensor.prom_crc_ok(), "prom_crc_ok");
    CHECK(sensor.read(t, p), "read ok");                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    NEAR(p, 30000.0f, 120000.0f, "read_pressure_range");
    NEAR(sensor.read_pressure(), 30000.0f, 120000.0f, "read_pressure_single");
    NEAR(sensor.read_temperature(), -40.0f, 85.0f, "read_temperature_single");
    CHECK(sensor.read_raw(d1, d2) && d1 > 0 && d2 > 0, "read_raw");
    float tc = 0, pc = 0;
    sensor.compensate(d1, d2, tc, pc);
    NEAR(pc, p - 200.0f, p + 200.0f, "compensate_matches_read");
    sensor.coefficients(k);
    CHECK(k[0] > 0 && k[5] > 0, "coefficients_nonzero");
    CHECK(sensor.serial_code() < 4096, "serial_code_range");
    CHECK(sensor.read_prom(prom), "read_prom");
    static const uint16_t osrs[5] = {256, 512, 1024, 2048, 4096};
    for (int i = 0; i < 5; i++) {
        CHECK(sensor.set_oversampling(osrs[i]) && sensor.get_oversampling() == osrs[i], "osr_roundtrip");
        NEAR(sensor.read_pressure(), 30000.0f, 120000.0f, "osr_read");
    }
    CHECK(sensor.start_conversion(MS5611Full::TEMPERATURE), "start_conversion");
    TEST_DELAY_MS(12);
    CHECK(sensor.read_adc(adc) && adc > 0, "non_blocking_adc");
    float alt = sensor.altitude();
    NEAR(alt, -500.0f, 6000.0f, "altitude_plausible");
    CHECK(sensor.reset(), "reset");
    NEAR(sensor.read_pressure(), 30000.0f, 120000.0f, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
