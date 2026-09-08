import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.bme680 import BME680Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def check_close(label, actual, expected, tol=1e-6):
    check_true(label, abs(actual - expected) < tol)


# Calibration block 1 (23 bytes from 0x8A): par_T2=26435, par_T3=3,
# par_P1=36477, par_P2=-10685, par_P3=88, par_P4=2855, par_P5=140,
# par_P6=-7, par_P7=15, par_P8=-14600, par_P9=6000, par_P10=30.
# No published worked example exists for BME680 (see spec's Data
# Conversion > Validation) - these are self-consistent, hand-derived
# values computed directly from the spec's own compensation formulas, used
# to check every language's translation produces identical output for
# identical input, not to reproduce a datasheet reference value.
CAL_BLOCK1 = [0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
              0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E]

# Calibration block 2 (14 bytes from 0xE1): par_H1=600, par_H2=700, par_H3=0,
# par_H4=45, par_H5=20, par_H6=120, par_H7=-100, par_T1=26000, par_G2=-6900,
# par_G1=-30, par_G3=30.
CAL_BLOCK2 = [0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E]

# Single-byte calibration: res_heat_val=50, res_heat_range=2, range_switching_error=0.
RES_HEAT_VAL_BYTE = 0x32
RES_HEAT_RANGE_BYTE = 0x20
RANGE_SW_ERR_BYTE = 0x00

# ADC burst (13 bytes from 0x1F): press_adc=415148, temp_adc=419888,
# hum_adc=20000, gas_adc=400, gas_range=5, gas_valid=1, heat_stab=1.
ADC_BURST = [0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20, 0x00, 0x00, 0x00, 0x64, 0x35]

EXPECTED_T = 1.23
EXPECTED_P = 969.4
EXPECTED_H = 39.826
EXPECTED_GAS = 271155.0

connection = I2CConnectionMock()
connection.set_register(BME680Full._REG_CAL_BLOCK1, *CAL_BLOCK1)
connection.set_register(BME680Full._REG_CAL_BLOCK2, *CAL_BLOCK2)
connection.set_register(BME680Full._REG_RES_HEAT_VAL, RES_HEAT_VAL_BYTE)
connection.set_register(BME680Full._REG_RES_HEAT_RANGE, RES_HEAT_RANGE_BYTE)
connection.set_register(BME680Full._REG_RANGE_SW_ERR, RANGE_SW_ERR_BYTE)
connection.set_register(BME680Full._REG_PRESS_MSB, *ADC_BURST)

sensor = BME680Full(connection)
check_true('init', True)

check_true('init_ctrl_hum', connection.registers[BME680Full._REG_CTRL_HUM] == 1)
check_true('init_ctrl_meas_sleep', connection.registers[BME680Full._REG_CTRL_MEAS] == (1 << 5) | (1 << 2) | 0)
check_true('init_config', connection.registers[BME680Full._REG_CONFIG] == 0)
# Default heater profile 0: 320 degC target, 150 ms duration, ambient=25.0 (construction default).
check_true('init_res_heat_0', connection.registers[0x5A] == 0x52)
check_true('init_gas_wait_0', connection.registers[0x64] == 0x65)
check_true('init_ctrl_gas_1', connection.registers[BME680Full._REG_CTRL_GAS_1] == (1 << 4) | 0)

# set_heater(300, 200) at ambient=25.0 (unchanged since construction).
sensor.set_heater(300, 200)
check_true('set_heater_res_heat_0', connection.registers[0x5A] == 0x4E)
check_true('set_heater_gas_wait_0', connection.registers[0x64] == 0x72)

# set_heater_profile(4, 280, 50) at ambient=25.0 (still unchanged).
sensor.set_heater_profile(4, 280, 50)
check_true('set_heater_profile_res_heat_4', connection.registers[0x5A + 4] == 0x49)
check_true('set_heater_profile_gas_wait_4', connection.registers[0x64 + 4] == 0x32)

check_close('temperature', sensor.temperature(), EXPECTED_T, tol=0.01)
check_close('pressure', sensor.pressure(), EXPECTED_P, tol=0.1)
check_close('humidity', sensor.humidity(), EXPECTED_H, tol=0.01)
check_close('gas_resistance', sensor.gas_resistance(), EXPECTED_GAS, tol=1.0)

last_ctrl_meas = [w for w in connection.writes if len(w) == 2 and w[0] == BME680Full._REG_CTRL_MEAS][-1]
check_true('trigger_writes_forced_mode', last_ctrl_meas[1] & 0x03 == 1)

sensor.configure(osrs_t=2, osrs_p=3, osrs_h=1, mode=0, filter=3)
check_true('configure_ctrl_hum', connection.registers[BME680Full._REG_CTRL_HUM] == 1)
check_true('configure_config', connection.registers[BME680Full._REG_CONFIG] == (3 << 2))
check_true('configure_ctrl_meas', connection.registers[BME680Full._REG_CTRL_MEAS] == (2 << 5) | (3 << 2) | 0)

sensor.set_oversampling(3, 4, 2)
check_true('set_oversampling_ctrl_hum', connection.registers[BME680Full._REG_CTRL_HUM] == 2)
check_true('set_oversampling_ctrl_meas', connection.registers[BME680Full._REG_CTRL_MEAS] == (3 << 5) | (4 << 2) | 0)

sensor.set_filter(5)
check_true('set_filter', connection.registers[BME680Full._REG_CONFIG] == (5 << 2))

sensor.select_heater_profile(2)
check_true('select_heater_profile', connection.registers[BME680Full._REG_CTRL_GAS_1] == (1 << 4) | 2)

sensor.set_gas_enabled(False)
check_true('set_gas_enabled_false', connection.registers[BME680Full._REG_CTRL_GAS_1] == 2)
sensor.set_gas_enabled(True)
check_true('set_gas_enabled_true', connection.registers[BME680Full._REG_CTRL_GAS_1] == (1 << 4) | 2)

sensor.set_heater_off(True)
check_true('set_heater_off_true', connection.registers[BME680Full._REG_CTRL_GAS_0] == 0x08)
sensor.set_heater_off(False)
check_true('set_heater_off_false', connection.registers[BME680Full._REG_CTRL_GAS_0] == 0x00)

t, p, h, g = sensor.read_all()
check_close('read_all_t', t, EXPECTED_T, tol=0.01)
check_close('read_all_p', p, EXPECTED_P, tol=0.1)
check_close('read_all_h', h, EXPECTED_H, tol=0.01)
check_close('read_all_gas', g, EXPECTED_GAS, tol=1.0)

check_true('gas_valid', sensor.gas_valid() is True)
check_true('heater_stable', sensor.heater_stable() is True)

connection.set_register(BME680Full._REG_MEAS_STATUS, 0xA0)
check_true('status', sensor.status() == 0xA0)

connection.set_register(BME680Full._REG_ID, BME680Full._CHIP_ID)
check_true('chip_id', sensor.chip_id() == 0x61)

sensor.reset()
check_true('reset_writes_reset_cmd',
           any(w == bytes([BME680Full._REG_RESET, BME680Full._RESET_CMD]) for w in connection.writes))
check_true('reset_reapplies_ctrl_hum', connection.registers[BME680Full._REG_CTRL_HUM] == 2)
check_true('reset_reapplies_config', connection.registers[BME680Full._REG_CONFIG] == (5 << 2))
check_true('reset_reapplies_ctrl_meas', connection.registers[BME680Full._REG_CTRL_MEAS] == (3 << 5) | (4 << 2) | 0)
check_true('reset_reapplies_ctrl_gas_1', connection.registers[BME680Full._REG_CTRL_GAS_1] == (1 << 4) | 2)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
