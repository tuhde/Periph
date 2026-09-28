#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BMP085.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void preloadCalibration(I2CConnectionMock& connection) {
    // Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
    // AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
    connection.setRegister(0xAA, {
        0x01, 0x98,  // AC1 = 408
        0xFF, 0xB8,  // AC2 = -72
        0xC7, 0xD1,  // AC3 = -14383
        0x7F, 0xE5,  // AC4 = 32741
        0x7F, 0xF5,  // AC5 = 32757
        0x5A, 0x71,  // AC6 = 23153
        0x18, 0x2E,  // B1 = 6190
        0x00, 0x04,  // B2 = 4
        0x80, 0x00,  // MB = -32768
        0xDD, 0xF9,  // MC = -8711
        0x0B, 0x34,  // MD = 2868
    });
}

static int lastWriteTo(const I2CConnectionMock& conn, uint8_t reg) {
    const auto& w = conn.writes();
    for (auto it = w.rbegin(); it != w.rend(); ++it) {
        if (it->size() == 2 && (*it)[0] == reg) return (*it)[1];
    }
    return -1;
}

int main() {
    // --- Minimal constructor: reads and unpacks calibration coefficients ---
    I2CConnectionMock conn;
    preloadCalibration(conn);
    BMP085Minimal chip(conn);
    check_true(true, "construction_succeeds");

    // --- temperature()/pressure(): datasheet worked example (UT=UP=27898
    // due to the mock's static register map -- both reads hit the same
    // OUT_MSB address within one call, so they can't differ within one step) ---
    conn.setRegister(0xF6, {0x6C, 0xFA}); // UT = 27898
    check_true(chip.temperature() == 15.0f, "temperature_known_value");
    check_true(lastWriteTo(conn, 0xF4) == 0x2E, "temperature_writes_cmd_temp");

    conn.setRegister(0xF6, {0x6C, 0xFA});
    check_true(fabsf(chip.pressure() - 82080.0f) < 1e-3f, "pressure_known_value");

    // --- Full: oversampling ---
    I2CConnectionMock fullConn;
    preloadCalibration(fullConn);
    BMP085Full full(fullConn, BMP085Full::OSS_HIGH_RES);
    check_true(full.oversampling() == 2, "constructor_oss");
    full.set_oversampling(3);
    check_true(full.oversampling() == 3, "set_oversampling");
    fullConn.setRegister(0xF6, {0x6C, 0xFA, 0x00});
    full.pressure();
    check_true(lastWriteTo(fullConn, 0xF4) == 0xF4, "pressure_writes_cmd_for_oss3");

    // --- altitude()/sea_level_pressure() ---
    I2CConnectionMock altConn;
    preloadCalibration(altConn);
    BMP085Full altSensor(altConn);
    altConn.setRegister(0xF6, {0x6C, 0xFA});
    check_true(altSensor.altitude(101325.0f) > 0.0f, "altitude_computed");

    I2CConnectionMock slConn;
    preloadCalibration(slConn);
    BMP085Full slSensor(slConn);
    slConn.setRegister(0xF6, {0x6C, 0xFA});
    check_true(fabsf(slSensor.sea_level_pressure(0.0f) - 82080.0f) < 1e-2f, "sea_level_pressure_at_zero_alt");

    // --- chip_id() ---
    I2CConnectionMock idConn;
    preloadCalibration(idConn);
    BMP085Full idSensor(idConn);
    idConn.setRegister(0xD0, {0x55});
    check_true(idSensor.chip_id() == 0x55, "chip_id");

    // --- reset(): writes soft-reset key ---
    I2CConnectionMock resetConn;
    preloadCalibration(resetConn);
    BMP085Full resetSensor(resetConn);
    resetSensor.reset();
    check_true(lastWriteTo(resetConn, 0xE0) == 0xB6, "reset_writes_key");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
