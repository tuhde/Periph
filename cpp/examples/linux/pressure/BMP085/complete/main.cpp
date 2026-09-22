#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <cstdio>
#include "I2CConnectionLinux.h"
#include "BMP085.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    BMP085Full bmp(connection);                             // Create BMP085 driver, (connection, oss=0)

    uint8_t cid = bmp.chip_id();                            // Read chip ID, () → int
                                                             // returns 0x55 for BMP085
    check_true(cid == 0x55, "chip_id");

    uint8_t oss = bmp.oversampling();                       // Read OSS, () → int 0–3
    check_true(oss == 0, "default_oss");

    bmp.set_oversampling(BMP085Full::OSS_STANDARD);         // Set OSS, (oss 0–3) → None
                                                             // changes conversion time vs resolution trade-off
    check_true(bmp.oversampling() == 1, "set_oss");

    float t = bmp.temperature();                            // Read temperature, () → float C
    float p = bmp.pressure();                              // Read pressure, () → float Pa
    float alt = bmp.altitude();                            // Compute altitude, (sea_level_pa=101325.0) → float m
                                                             // uses barometric formula to convert pressure to metres
    float slp = bmp.sea_level_pressure(alt);                // Compute sea-level pressure, (altitude_m) → float Pa
    bmp.reset();                                            // Soft reset chip, () → None

    check_true(t > -40.0f && t < 85.0f, "temperature_in_range");
    check_true(p > 30000.0f && p < 110000.0f, "pressure_in_range");
    printf("T=%.1f C, P=%.1f Pa, alt=%.1f m, slp=%.1f Pa\n", t, p, alt, slp);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
