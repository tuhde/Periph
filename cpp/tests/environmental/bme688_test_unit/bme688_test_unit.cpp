#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "BME688.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float actual, float expected, float tol, const char *label) {
    check_true(fabsf(actual - expected) < tol, label);
}

// Access protected register constants for building expected values.
class BME688TestAccess : public BME688Full {
public:
    using BME688Full::BME688Full;
    using BME688Full::REG_RES_HEAT_VAL;
    using BME688Full::REG_RES_HEAT_RANGE;
    using BME688Full::REG_MEAS_STATUS;
    using BME688Full::REG_VARIANT_ID;
    using BME688Full::VARIANT_ID;
    using BME688Full::REG_CTRL_GAS_0;
    using BME688Full::REG_CTRL_GAS_1;
    using BME688Full::REG_CTRL_HUM;
    using BME688Full::REG_CTRL_MEAS;
    using BME688Full::REG_CONFIG;
    using BME688Full::REG_CAL_BLOCK1;
    using BME688Full::REG_ID;
    using BME688Full::REG_RESET;
    using BME688Full::REG_CAL_BLOCK2;
    using BME688Full::CHIP_ID;
    using BME688Full::RESET_CMD;
};

static void setBytes(I2CConnectionMock& c, uint16_t reg, const std::vector<uint8_t>& v) {
    for (size_t i = 0; i < v.size(); i++) c.setRegister(reg + i, {v[i]});
}

int main() {
    I2CConnectionMock connection;

    // Calibration block 1 (23 bytes from 0x8A). No published worked example
    // exists for BME688 (see spec's Data Conversion > Validation) - these are
    // self-consistent, hand-derived values used to check every language's
    // translation against the same formula, not a datasheet reference value.
    connection.setRegister(BME688TestAccess::REG_CAL_BLOCK1,
        {0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
         0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E});
    // Calibration block 2 (14 bytes from 0xE1).
    connection.setRegister(BME688TestAccess::REG_CAL_BLOCK2,
        {0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E});
    // Single-byte calibration: res_heat_val=50, res_heat_range=2.
    connection.setRegister(BME688TestAccess::REG_RES_HEAT_VAL, {0x32});
    connection.setRegister(BME688TestAccess::REG_RES_HEAT_RANGE, {0x20});
    // Field 0 (17 bytes from 0x1D): new_data set, press_adc=415148, temp_adc=419888,
    // hum_adc=20000, gas_adc=400, gas_range=5, gas_valid=1, heat_stab=1
    // (gas at 0x2C/0x2D, not 0x2A/0x2B as on the BME680).
    const std::vector<uint8_t> FIELD0 = {0x80, 0x00, 0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20,
                                         0x00, 0x00, 0x00, 0x00, 0x00, 0x64, 0x35};
    setBytes(connection, BME688TestAccess::REG_MEAS_STATUS, FIELD0);

    const float EXPECTED_T = 1.23f;
    const float EXPECTED_P = 969.4f;
    const float EXPECTED_H = 39.826f;
    const float EXPECTED_GAS = 2178700.0f;

    BME688TestAccess sensor(connection);
    check_true(true, "init");

    const auto& regs = connection.registers();
    check_true(regs.at(BME688TestAccess::REG_CTRL_HUM) == 1, "init_ctrl_hum");
    check_true(regs.at(BME688TestAccess::REG_CTRL_MEAS) == ((1 << 5) | (1 << 2) | 0), "init_ctrl_meas_sleep");
    check_true(regs.at(BME688TestAccess::REG_CONFIG) == 0, "init_config");
    check_true(regs.at(0x5A) == 0x52, "init_res_heat_0");
    check_true(regs.at(0x64) == 0x65, "init_gas_wait_0");
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == 0x20, "init_ctrl_gas_1");

    sensor.set_heater(300, 200);
    check_true(regs.at(0x5A) == 0x4E, "set_heater_res_heat_0");
    check_true(regs.at(0x64) == 0x72, "set_heater_gas_wait_0");

    sensor.set_heater_profile(4, 280, 50);
    check_true(regs.at(0x5A + 4) == 0x49, "set_heater_profile_res_heat_4");
    check_true(regs.at(0x64 + 4) == 0x32, "set_heater_profile_gas_wait_4");

    check_close(sensor.temperature(), EXPECTED_T, 0.01f, "temperature");
    check_close(sensor.pressure(), EXPECTED_P, 0.1f, "pressure");
    check_close(sensor.humidity(), EXPECTED_H, 0.01f, "humidity");
    check_close(sensor.gas_resistance(), EXPECTED_GAS, 1.0f, "gas_resistance");

    std::vector<uint8_t> lastCtrlMeas;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BME688TestAccess::REG_CTRL_MEAS) lastCtrlMeas = w;
    }
    check_true((lastCtrlMeas[1] & 0x03) == 1, "trigger_writes_forced_mode");

    sensor.configure(2, 3, 1, 0, 3);
    check_true(regs.at(BME688TestAccess::REG_CTRL_HUM) == 1, "configure_ctrl_hum");
    check_true(regs.at(BME688TestAccess::REG_CONFIG) == (3 << 2), "configure_config");
    check_true(regs.at(BME688TestAccess::REG_CTRL_MEAS) == ((2 << 5) | (3 << 2) | 0), "configure_ctrl_meas");

    sensor.set_oversampling(3, 4, 2);
    check_true(regs.at(BME688TestAccess::REG_CTRL_HUM) == 2, "set_oversampling_ctrl_hum");
    check_true(regs.at(BME688TestAccess::REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 0), "set_oversampling_ctrl_meas");

    sensor.set_filter(5);
    check_true(regs.at(BME688TestAccess::REG_CONFIG) == (5 << 2), "set_filter");

    sensor.select_heater_profile(2);
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == (0x20 | 2), "select_heater_profile");

    sensor.set_gas_enabled(false);
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == 2, "set_gas_enabled_false");
    sensor.set_gas_enabled(true);
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == (0x20 | 2), "set_gas_enabled_true");

    sensor.set_heater_off(true);
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_0) == 0x08, "set_heater_off_true");
    sensor.set_heater_off(false);
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_0) == 0x00, "set_heater_off_false");

    float t, p, h, g;
    sensor.read_all(t, p, h, g);
    check_close(t, EXPECTED_T, 0.01f, "read_all_t");
    check_close(p, EXPECTED_P, 0.1f, "read_all_p");
    check_close(h, EXPECTED_H, 0.01f, "read_all_h");
    check_close(g, EXPECTED_GAS, 1.0f, "read_all_gas");

    check_true(sensor.gas_valid() == true, "gas_valid");
    check_true(sensor.heater_stable() == true, "heater_stable");

    connection.setRegister(BME688TestAccess::REG_ID, {BME688TestAccess::CHIP_ID});
    check_true(sensor.chip_id() == 0x61, "chip_id");
    connection.setRegister(BME688TestAccess::REG_VARIANT_ID, {BME688TestAccess::VARIANT_ID});
    check_true(sensor.variant_id() == 0x01, "variant_id");

    // Parallel mode: two heater steps, field 1 has no new data, field 2 an invalid gas reading.
    const int16_t temps[2] = {320, 250};
    const uint8_t reps[2] = {1, 2};
    sensor.start_parallel(temps, reps, 2, 100.0f);
    check_true(regs.at(0x5A) == 0x52, "parallel_res_heat_0");
    check_true(regs.at(0x64) == 1, "parallel_gas_wait_0");
    check_true(regs.at(0x65) == 2, "parallel_gas_wait_1");
    check_true(regs.at(0x6E) == ((1 << 6) | (210 / 4)), "parallel_gas_wait_shared");
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == (0x20 | 2), "parallel_ctrl_gas_1");
    check_true((regs.at(BME688TestAccess::REG_CTRL_MEAS) & 0x03) == 2, "parallel_ctrl_meas");
    std::vector<uint8_t> all = FIELD0;
    for (int i = 0; i < 17; i++) all.push_back(0x00);
    std::vector<uint8_t> field2 = FIELD0;
    field2[0] = 0x81; field2[1] = 0x03; field2[16] = 0x05;
    all.insert(all.end(), field2.begin(), field2.end());
    setBytes(connection, BME688TestAccess::REG_MEAS_STATUS, all);
    BME688Field fields[3];
    uint8_t n = sensor.read_parallel(fields, 3);
    check_true(n == 2, "parallel_two_fields");
    check_true(fields[0].step == 0 && fields[0].sub_index == 0, "parallel_step0");
    check_close(fields[0].temperature, EXPECTED_T, 0.01f, "parallel_t0");
    check_close(fields[0].gas_resistance, EXPECTED_GAS, 1.0f, "parallel_gas0");
    check_true(fields[0].gas_valid && fields[0].heater_stable, "parallel_valid0");
    check_true(fields[1].step == 1 && fields[1].sub_index == 3, "parallel_step2");
    check_true(isnan(fields[1].gas_resistance), "parallel_gas_invalid");
    sensor.stop_parallel();
    check_true((regs.at(BME688TestAccess::REG_CTRL_MEAS) & 0x03) == 0, "parallel_stop");

    sensor.reset();
    bool sawReset = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 2 && w[0] == BME688TestAccess::REG_RESET && w[1] == BME688TestAccess::RESET_CMD) sawReset = true;
    }
    check_true(sawReset, "reset_writes_reset_cmd");
    check_true(regs.at(BME688TestAccess::REG_CTRL_HUM) == 2, "reset_reapplies_ctrl_hum");
    check_true(regs.at(BME688TestAccess::REG_CONFIG) == (5 << 2), "reset_reapplies_config");
    check_true(regs.at(BME688TestAccess::REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 0), "reset_reapplies_ctrl_meas");
    check_true(regs.at(BME688TestAccess::REG_CTRL_GAS_1) == (0x20 | 2), "reset_reapplies_ctrl_gas_1");
    check_true(sensor.status() == 0x80, "status");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
