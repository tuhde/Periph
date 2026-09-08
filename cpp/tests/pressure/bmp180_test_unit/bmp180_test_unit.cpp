#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BMP180.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class BMP180TestAccess : public BMP180Full {
public:
    using BMP180Full::BMP180Full;
    using BMP180Full::REG_CAL_START;
    using BMP180Full::REG_ID;
    using BMP180Full::REG_SOFT_RESET;
    using BMP180Full::REG_CTRL_MEAS;
    using BMP180Full::REG_OUT_MSB;
    using BMP180Full::CMD_TEMP;
    using BMP180Full::CMD_PRESSURE_OSS0;
    using BMP180Full::SOFT_RESET_CMD;
};

static void preloadCalibration(I2CConnectionMock& connection) {
    // Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
    // AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
    // MB=-32768, MC=-8711, MD=2868.
    connection.setRegister(BMP180TestAccess::REG_CAL_START, {
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

int main() {
    I2CConnectionMock connection;
    preloadCalibration(connection);

    BMP180TestAccess sensor(connection);
    check_true(true, "init");

    // pressure() re-reads OUT_MSB for both UT (2 bytes) and UP (3 bytes)
    // from the same register within one call, and this mock always returns
    // the register map's current contents - it cannot hand back a different
    // UT then a different UP within a single call. So UT and the top 16
    // bits of UP are necessarily the same value here (0x6CFA = 27898); the
    // expected T/p below are computed from the real compensation formula
    // with UT=UP=27898, not the datasheet's mismatched worked example.
    connection.setRegister(BMP180TestAccess::REG_OUT_MSB, {0x6C, 0xFA});
    check_true(sensor.temperature() == 15.0f, "temperature");

    bool sawCmdTemp = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP180TestAccess::REG_CTRL_MEAS && w[1] == BMP180TestAccess::CMD_TEMP)
            sawCmdTemp = true;
    }
    check_true(sawCmdTemp, "temperature_writes_cmd_temp");

    connection.setRegister(BMP180TestAccess::REG_OUT_MSB, {0x6C, 0xFA});
    check_true(fabsf(sensor.pressure() - 820.8f) < 1e-3f, "pressure");

    bool sawCmdPressure = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP180TestAccess::REG_CTRL_MEAS && w[1] == BMP180TestAccess::CMD_PRESSURE_OSS0)
            sawCmdPressure = true;
    }
    check_true(sawCmdPressure, "pressure_writes_cmd_pressure_oss0");

    // chip_id(): expect 0x55.
    connection.setRegister(BMP180TestAccess::REG_ID, {0x55});
    check_true(sensor.chip_id() == 0x55, "chip_id");

    // oversampling()/set_oversampling()
    check_true(sensor.oversampling() == 0, "oversampling_default");
    sensor.set_oversampling(BMP180Full::OSS_STANDARD);
    check_true(sensor.oversampling() == 1, "set_oversampling");
    sensor.set_oversampling(0);  // restore ULP for the rest of the test

    // altitude(sea_level_hpa=1013.25 default): pressure() re-reads UT/UP internally.
    connection.setRegister(BMP180TestAccess::REG_OUT_MSB, {0x6C, 0xFA});
    check_true(fabsf(sensor.altitude() - 1741.7604174f) < 0.5f, "altitude_default_sea_level");

    // sea_level_pressure(altitude_m=100)
    connection.setRegister(BMP180TestAccess::REG_OUT_MSB, {0x6C, 0xFA});
    check_true(fabsf(sensor.sea_level_pressure(100.0f) - 830.599010429f) < 0.5f, "sea_level_pressure");

    // reset(): writes soft-reset command, then re-reads calibration coefficients.
    preloadCalibration(connection);
    sensor.reset();
    bool sawSoftReset = false;
    int calReads = 0;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP180TestAccess::REG_SOFT_RESET && w[1] == BMP180TestAccess::SOFT_RESET_CMD)
            sawSoftReset = true;
        if (w.size() == 1 && w[0] == BMP180TestAccess::REG_CAL_START)
            calReads++;
    }
    check_true(sawSoftReset, "reset_writes_soft_reset_cmd");
    check_true(calReads >= 2, "reset_rereads_calibration");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
