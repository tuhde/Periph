import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.bme280 import BME280Full

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


# Calibration NVM block 1 (26 bytes from 0x88), from the BMP280 datasheet's
# worked example (dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477,
# dig_P2=-10685, dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7,
# dig_P7=15500, dig_P8=-14600, dig_P9=6000), plus dig_H1=75 at 0xA1 — reused
# per the spec ("use the BMP280's worked example to validate the pressure
# and temperature paths").
CAL_BLOCK1 = [0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
              0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
              0x70, 0x17, 0x00, 0x4B]

# Calibration NVM block 2 (7 bytes from 0xE1): dig_H2=384, dig_H3=0,
# dig_H4=301, dig_H5=50, dig_H6=30 (arbitrary values with no published
# worked example — see spec's Data Conversion > Validation).
CAL_BLOCK2 = [0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E]

# ADC burst (8 bytes from 0xF7): adc_P=415148, adc_T=519888 (BMP280 worked
# example), adc_H=32768.
ADC_BURST = [0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00]

EXPECTED_T = 25.08
EXPECTED_P = 1006.5325390625
EXPECTED_H = 79.0869140625

connection = I2CConnectionMock()
connection.set_register(BME280Full._REG_CAL_START, *CAL_BLOCK1)
connection.set_register(BME280Full._REG_CAL_H2, *CAL_BLOCK2)
connection.set_register(BME280Full._REG_DATA_START, *ADC_BURST)

sensor = BME280Full(connection)
check_true('init', True)

# Init sequence: ctrl_hum (osrs_h=1) must be written BEFORE ctrl_meas.
ctrl_hum_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BME280Full._REG_CTRL_HUM]
ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BME280Full._REG_CTRL_MEAS]
ctrl_hum_idx = connection.writes.index(ctrl_hum_writes[0])
ctrl_meas_idx = connection.writes.index(ctrl_meas_writes[0])
check_true('init_writes_ctrl_hum_before_ctrl_meas', ctrl_hum_idx < ctrl_meas_idx)
check_true('init_ctrl_hum_osrs_x1', ctrl_hum_writes[0][1] == 1)
check_true('init_ctrl_meas_osrs_x1_sleep', ctrl_meas_writes[0][1] == (1 << 5) | (1 << 2) | 0)

check_close('temperature', sensor.temperature(), EXPECTED_T, tol=0.01)
check_close('pressure', sensor.pressure(), EXPECTED_P, tol=0.01)
check_close('humidity', sensor.humidity(), EXPECTED_H, tol=0.01)

# Each forced-mode trigger writes ctrl_hum then ctrl_meas with mode=1 (forced).
last_ctrl_meas = [w for w in connection.writes if len(w) == 2 and w[0] == BME280Full._REG_CTRL_MEAS][-1]
check_true('trigger_writes_forced_mode', last_ctrl_meas[1] & 0x03 == 1)

sensor.configure(osrs_t=2, osrs_p=3, osrs_h=1, mode=3, filter=2, t_sb=5)
check_true('configure_ctrl_hum', connection.registers[BME280Full._REG_CTRL_HUM] == 1)
check_true('configure_config', connection.registers[BME280Full._REG_CONFIG] == (5 << 5) | (2 << 2))
check_true('configure_ctrl_meas', connection.registers[BME280Full._REG_CTRL_MEAS] == (2 << 5) | (3 << 2) | 3)

sensor.set_oversampling(3, 4, 2)
check_true('set_oversampling_ctrl_hum', connection.registers[BME280Full._REG_CTRL_HUM] == 2)
check_true('set_oversampling_ctrl_meas',
           connection.registers[BME280Full._REG_CTRL_MEAS] == (3 << 5) | (4 << 2) | 3)

sensor.set_mode(1)
check_true('set_mode', connection.registers[BME280Full._REG_CTRL_MEAS] == (3 << 5) | (4 << 2) | 1)

sensor.set_filter(3)
check_true('set_filter', connection.registers[BME280Full._REG_CONFIG] == (5 << 5) | (3 << 2))

sensor.set_standby(6)
check_true('set_standby', connection.registers[BME280Full._REG_CONFIG] == (6 << 5) | (3 << 2))

connection.set_register(BME280Full._REG_STATUS, 0x08)
check_true('status', sensor.status() == 0x08)

check_close('altitude', sensor.altitude(), 56.07668235692459, tol=0.001)
check_close('sea_level_pressure', sensor.sea_level_pressure(56.07668235692459), 1013.25, tol=0.001)
check_close('dew_point', sensor.dew_point(), 21.191706255732008, tol=0.001)

connection.set_register(BME280Full._REG_ID, BME280Full._CHIP_ID)
check_true('chip_id', sensor.chip_id() == 0x60)

sensor.reset()
check_true('reset_writes_reset_cmd',
           any(w == bytes([BME280Full._REG_RESET, BME280Full._RESET_CMD]) for w in connection.writes))
# reset() re-applies the last configured ctrl_hum/config/ctrl_meas (osrs_h=2,
# osrs_t=3, osrs_p=4, mode=1 from set_mode(1) above, filter=3, t_sb=6).
check_true('reset_reapplies_ctrl_hum', connection.registers[BME280Full._REG_CTRL_HUM] == 2)
check_true('reset_reapplies_config', connection.registers[BME280Full._REG_CONFIG] == (6 << 5) | (3 << 2))
check_true('reset_reapplies_ctrl_meas',
           connection.registers[BME280Full._REG_CTRL_MEAS] == (3 << 5) | (4 << 2) | 1)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
