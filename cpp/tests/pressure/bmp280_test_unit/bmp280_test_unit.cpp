#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BMP280.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class BMP280TestAccess : public BMP280Full {
public:
    using BMP280Full::BMP280Full;
    using BMP280Full::REG_CAL_START;
    using BMP280Full::REG_ID;
    using BMP280Full::REG_RESET;
    using BMP280Full::REG_STATUS;
    using BMP280Full::REG_CTRL_MEAS;
    using BMP280Full::REG_CONFIG;
    using BMP280Full::REG_DATA_START;
    using BMP280Full::RESET_CMD;
};

static void preloadCalibration(I2CConnectionMock& connection) {
    // Spec's Data Conversion "Validation" worked example (datasheet page 23):
    // dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
    // dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
    // dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
    connection.setRegister(BMP280TestAccess::REG_CAL_START, {
        0x70, 0x6B,  // dig_T1 = 27504
        0x43, 0x67,  // dig_T2 = 26435
        0x18, 0xFC,  // dig_T3 = -1000
        0x7D, 0x8E,  // dig_P1 = 36477
        0x43, 0xD6,  // dig_P2 = -10685
        0xD0, 0x0B,  // dig_P3 = 3024
        0x27, 0x0B,  // dig_P4 = 2855
        0x8C, 0x00,  // dig_P5 = 140
        0xF9, 0xFF,  // dig_P6 = -7
        0x8C, 0x3C,  // dig_P7 = 15500
        0xF8, 0xC6,  // dig_P8 = -14600
        0x70, 0x17,  // dig_P9 = 6000
    });
}

static void preloadData(I2CConnectionMock& connection) {
    // UT=519888, UP=415148 (same worked example), one 6-byte burst - unlike
    // BMP180, both ADCs come from a single read, so this mock can represent
    // the exact worked example (no shared-register aliasing).
    connection.setRegister(BMP280TestAccess::REG_DATA_START, {
        0x65, 0x5A, 0xC0,  // adc_P = 415148
        0x7E, 0xED, 0x00,  // adc_T = 519888
    });
}

int main() {
    I2CConnectionMock connection;
    preloadCalibration(connection);
    preloadData(connection);

    BMP280TestAccess sensor(connection);
    check_true(true, "init");

    bool sawCtrlMeasSleep = false, sawConfigDefault = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x24) sawCtrlMeasSleep = true;
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CONFIG && w[1] == 0x00) sawConfigDefault = true;
    }
    check_true(sawCtrlMeasSleep, "init_writes_ctrl_meas_sleep");
    check_true(sawConfigDefault, "init_writes_config_default");

    // temperature(): worked example -> T = 25.08 degC.
    check_true(fabsf(sensor.temperature() - 25.08f) < 1e-3f, "temperature");

    bool sawTriggerForced = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x25) sawTriggerForced = true;
    }
    check_true(sawTriggerForced, "temperature_triggers_forced");

    // pressure(): worked example -> p = 25767233/256/100 = 1006.5325... hPa.
    check_true(fabsf(sensor.pressure() - 1006.5325390625f) < 1e-2f, "pressure");

    // chip_id(): expect 0x58.
    connection.setRegister(BMP280TestAccess::REG_ID, {0x58});
    check_true(sensor.chip_id() == 0x58, "chip_id");

    // status(): raw status byte.
    connection.setRegister(BMP280TestAccess::REG_STATUS, {0x09});
    check_true(sensor.status() == 0x09, "status");

    // configure(osrs_t=2, osrs_p=3, mode=3, filter=2, t_sb=4):
    // CONFIG = (4<<5)|(2<<2) = 0x88; CTRL_MEAS = (2<<5)|(3<<2)|3 = 0x4F.
    sensor.configure(2, 3, 3, 2, 4);
    bool sawConfigureConfig = false, sawConfigureCtrl = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CONFIG && w[1] == 0x88) sawConfigureConfig = true;
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x4F) sawConfigureCtrl = true;
    }
    check_true(sawConfigureConfig, "configure_config");
    check_true(sawConfigureCtrl, "configure_ctrl_meas");

    // set_oversampling(osrs_t=4, osrs_p=5): mode stays 3 (from configure).
    // CTRL_MEAS = (4<<5)|(5<<2)|3 = 0x97.
    sensor.set_oversampling(4, 5);
    bool sawSetOversampling = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x97) sawSetOversampling = true;
    }
    check_true(sawSetOversampling, "set_oversampling");

    // set_mode(1): CTRL_MEAS = (4<<5)|(5<<2)|1 = 0x95.
    sensor.set_mode(1);
    bool sawSetMode = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x95) sawSetMode = true;
    }
    check_true(sawSetMode, "set_mode");

    // set_filter(3): CONFIG = (4<<5)|(3<<2) = 0x8C.
    sensor.set_filter(3);
    bool sawSetFilter = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CONFIG && w[1] == 0x8C) sawSetFilter = true;
    }
    check_true(sawSetFilter, "set_filter");

    // set_standby(6): CONFIG = (6<<5)|(3<<2) = 0xCC.
    sensor.set_standby(6);
    bool sawSetStandby = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_CONFIG && w[1] == 0xCC) sawSetStandby = true;
    }
    check_true(sawSetStandby, "set_standby");

    // altitude(sea_level_hpa=1013.25 default): pressure() re-reads DATA_START,
    // still preloaded with the same worked-example bytes.
    check_true(fabsf(sensor.altitude() - 56.07668235692459f) < 0.5f, "altitude_default_sea_level");

    // sea_level_pressure(altitude_m=200)
    check_true(fabsf(sensor.sea_level_pressure(200.0f) - 1030.736388797547f) < 0.5f, "sea_level_pressure");

    // reset(): writes RESET=0xB6, re-reads calibration, re-applies current
    // configuration (t_sb=6, filter=3, osrs_t=4, osrs_p=5, mode=1 from above).
    preloadCalibration(connection);
    sensor.reset();
    bool sawResetCmd = false;
    int calReads = 0;
    bool sawReappliedConfig = false, sawReappliedCtrl = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BMP280TestAccess::REG_RESET && w[1] == BMP280TestAccess::RESET_CMD) sawResetCmd = true;
        if (w.size() == 1 && w[0] == BMP280TestAccess::REG_CAL_START) calReads++;
    }
    const auto& allWrites = connection.writes();
    for (size_t i = allWrites.size(); i-- > 0; ) {
        const auto& w = allWrites[i];
        if (!sawReappliedConfig && w.size() == 2 && w[0] == BMP280TestAccess::REG_CONFIG && w[1] == 0xCC) sawReappliedConfig = true;
        if (!sawReappliedCtrl && w.size() == 2 && w[0] == BMP280TestAccess::REG_CTRL_MEAS && w[1] == 0x95) sawReappliedCtrl = true;
    }
    check_true(sawResetCmd, "reset_writes_reset_cmd");
    check_true(calReads >= 2, "reset_rereads_calibration");
    check_true(sawReappliedConfig, "reset_reapplies_config");
    check_true(sawReappliedCtrl, "reset_reapplies_ctrl_meas");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
