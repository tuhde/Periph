#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "MPL115A2.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void preload(I2CConnectionMock& c) {
    // Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
    // ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
    c.setRegister(0x04, {0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8});
    c.setRegister(0x00, {0x66, 0x80, 0x7E, 0xC0});
}

int main() {
    I2CConnectionMock connection;
    preload(connection);

    MPL115A2Full sensor(connection);
    check_true(true, "init");

    float a0, b1, b2, c12;
    sensor.coefficients(a0, b1, b2, c12);
    check_true(fabsf(a0 - 2009.75f) < 1e-3f && fabsf(b1 + 2.375854f) < 1e-4f
               && fabsf(b2 + 0.92047f) < 1e-4f && fabsf(c12 - 0.000790f) < 1e-5f, "coefficients");

    float p = sensor.read_pressure();
    check_true(fabsf(p - 96586.0f) < 30.0f, "pressure");

    bool sawConvert = false;
    for (const auto& w : connection.writes())
        if (w.size() == 2 && w[0] == 0x12 && w[1] == 0x00) sawConvert = true;
    check_true(sawConvert, "convert_written");

    check_true(fabsf(sensor.read_temperature() - (25.0f + (507 - 498) / -5.35f)) < 1e-3f, "temperature");

    uint16_t padc, tadc;
    sensor.read_raw(padc, tadc);
    check_true(padc == 410 && tadc == 507, "read_raw");

    float pp, tc;
    sensor.read(pp, tc);
    check_true(fabsf(pp - p) < 1e-2f && fabsf(tc - sensor.read_temperature()) < 1e-3f, "read");
    check_true(fabsf(sensor.compensate(410, 507) - p) < 1e-2f, "compensate");
    check_true(sensor.compensate(0, 0) > 40000.0f && sensor.compensate(1023, 0) < 130000.0f, "compensate_limits");
    check_true(fabsf(sensor.pressure_hpa() - p / 100.0f) < 1e-3f, "pressure_hpa");
    check_true(fabsf(sensor.altitude() - 44330.0f * (1.0f - powf(p / 101325.0f, 1.0f / 5.255f))) < 0.1f, "altitude");
    check_true(fabsf(sensor.altitude(p)) < 0.1f, "altitude_sea_level");

    connection.setRegister(0x04, {0x00, 0x08, 0, 0, 0, 0, 0, 0});
    sensor.reload_coefficients();
    sensor.coefficients(a0, b1, b2, c12);
    check_true(a0 == 1.0f, "reload_coefficients");

    I2CConnectionMock c2;
    preload(c2);
    MPL115A2Minimal minimal(c2);
    check_true(fabsf(minimal.read_pressure() - 96586.0f) < 30.0f, "minimal_pressure");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
