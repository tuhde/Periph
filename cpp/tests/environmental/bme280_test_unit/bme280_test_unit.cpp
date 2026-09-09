#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BME280.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float actual, float expected, float tol, const char *label) {
    check_true(fabsf(actual - expected) < tol, label);
}

// Access protected register constants for building expected values.
class BME280TestAccess : public BME280Full {
public:
    using BME280Full::BME280Full;
    using BME280Full::REG_CAL_START;
    using BME280Full::REG_H1;
    using BME280Full::REG_ID;
    using BME280Full::REG_RESET;
    using BME280Full::REG_CAL_H2;
    using BME280Full::REG_CTRL_HUM;
    using BME280Full::REG_STATUS;
    using BME280Full::REG_CTRL_MEAS;
    using BME280Full::REG_CONFIG;
    using BME280Full::REG_DATA_START;
    using BME280Full::CHIP_ID;
    using BME280Full::RESET_CMD;
};

int main() {
    I2CConnectionMock connection;

    // Calibration NVM block 1 (26 bytes from 0x88), from the BMP280 datasheet's
    // worked example (dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477,
    // dig_P2=-10685, dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7,
    // dig_P7=15500, dig_P8=-14600, dig_P9=6000), plus dig_H1=75 at 0xA1.
    connection.setRegister(BME280TestAccess::REG_CAL_START,
        {0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
         0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
         0x70, 0x17, 0x00, 0x4B});
    // Calibration NVM block 2 (7 bytes from 0xE1): dig_H2=384, dig_H3=0,
    // dig_H4=301, dig_H5=50, dig_H6=30.
    connection.setRegister(BME280TestAccess::REG_CAL_H2,
        {0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E});
    // ADC burst (8 bytes from 0xF7): adc_P=415148, adc_T=519888, adc_H=32768.
    connection.setRegister(BME280TestAccess::REG_DATA_START,
        {0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00});

    const float EXPECTED_T = 25.08f;
    const float EXPECTED_P = 1006.5325390625f;
    const float EXPECTED_H = 79.0869140625f;

    BME280TestAccess sensor(connection);
    check_true(true, "init");

    std::vector<uint8_t> firstCtrlHum, firstCtrlMeas;
    int ctrlHumIdx = -1, ctrlMeasIdx = -1, idx = 0;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BME280TestAccess::REG_CTRL_HUM && ctrlHumIdx < 0) {
            ctrlHumIdx = idx; firstCtrlHum = w;
        }
        if (w.size() == 2 && w[0] == BME280TestAccess::REG_CTRL_MEAS && ctrlMeasIdx < 0) {
            ctrlMeasIdx = idx; firstCtrlMeas = w;
        }
        idx++;
    }
    check_true(ctrlHumIdx >= 0 && ctrlMeasIdx >= 0 && ctrlHumIdx < ctrlMeasIdx,
               "init_writes_ctrl_hum_before_ctrl_meas");
    check_true(firstCtrlHum[1] == 1, "init_ctrl_hum_osrs_x1");
    check_true(firstCtrlMeas[1] == ((1 << 5) | (1 << 2) | 0), "init_ctrl_meas_osrs_x1_sleep");

    check_close(sensor.temperature(), EXPECTED_T, 0.01f, "temperature");
    check_close(sensor.pressure(), EXPECTED_P, 0.01f, "pressure");
    check_close(sensor.humidity(), EXPECTED_H, 0.01f, "humidity");

    const auto& lastWrites = connection.writes();
    std::vector<uint8_t> lastCtrlMeas;
    for (const auto& w : lastWrites) {
        if (w.size() == 2 && w[0] == BME280TestAccess::REG_CTRL_MEAS) lastCtrlMeas = w;
    }
    check_true((lastCtrlMeas[1] & 0x03) == 1, "trigger_writes_forced_mode");

    const auto& regs = connection.registers();

    sensor.configure(2, 3, 1, 3, 2, 5);
    check_true(regs.at(BME280TestAccess::REG_CTRL_HUM) == 1, "configure_ctrl_hum");
    check_true(regs.at(BME280TestAccess::REG_CONFIG) == ((5 << 5) | (2 << 2)), "configure_config");
    check_true(regs.at(BME280TestAccess::REG_CTRL_MEAS) == ((2 << 5) | (3 << 2) | 3), "configure_ctrl_meas");

    sensor.set_oversampling(3, 4, 2);
    check_true(regs.at(BME280TestAccess::REG_CTRL_HUM) == 2, "set_oversampling_ctrl_hum");
    check_true(regs.at(BME280TestAccess::REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 3), "set_oversampling_ctrl_meas");

    sensor.set_mode(1);
    check_true(regs.at(BME280TestAccess::REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 1), "set_mode");

    sensor.set_filter(3);
    check_true(regs.at(BME280TestAccess::REG_CONFIG) == ((5 << 5) | (3 << 2)), "set_filter");

    sensor.set_standby(6);
    check_true(regs.at(BME280TestAccess::REG_CONFIG) == ((6 << 5) | (3 << 2)), "set_standby");

    connection.setRegister(BME280TestAccess::REG_STATUS, {0x08});
    check_true(sensor.status() == 0x08, "status");

    check_close(sensor.altitude(), 56.07668235692459f, 0.05f, "altitude");
    check_close(sensor.sea_level_pressure(56.07668235692459f), 1013.25f, 0.05f, "sea_level_pressure");
    check_close(sensor.dew_point(), 21.191706255732008f, 0.05f, "dew_point");

    connection.setRegister(BME280TestAccess::REG_ID, {BME280TestAccess::CHIP_ID});
    check_true(sensor.chip_id() == 0x60, "chip_id");

    sensor.reset();
    bool sawReset = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BME280TestAccess::REG_RESET && w[1] == BME280TestAccess::RESET_CMD) sawReset = true;
    }
    check_true(sawReset, "reset_writes_reset_cmd");
    check_true(regs.at(BME280TestAccess::REG_CTRL_HUM) == 2, "reset_reapplies_ctrl_hum");
    check_true(regs.at(BME280TestAccess::REG_CONFIG) == ((6 << 5) | (3 << 2)), "reset_reapplies_config");
    check_true(regs.at(BME280TestAccess::REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 1), "reset_reapplies_ctrl_meas");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
