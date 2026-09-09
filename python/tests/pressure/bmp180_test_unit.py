import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.bmp180 import BMP180Full

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


def preload_calibration(connection):
    # Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
    # AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
    # MB=-32768, MC=-8711, MD=2868.
    connection.set_register(BMP180Full._REG_CAL_START,
                             0x01, 0x98,  # AC1 = 408
                             0xFF, 0xB8,  # AC2 = -72
                             0xC7, 0xD1,  # AC3 = -14383
                             0x7F, 0xE5,  # AC4 = 32741
                             0x7F, 0xF5,  # AC5 = 32757
                             0x5A, 0x71,  # AC6 = 23153
                             0x18, 0x2E,  # B1 = 6190
                             0x00, 0x04,  # B2 = 4
                             0x80, 0x00,  # MB = -32768
                             0xDD, 0xF9,  # MC = -8711
                             0x0B, 0x34)  # MD = 2868


connection = I2CConnectionMock()
preload_calibration(connection)

sensor = BMP180Full(connection)
check_true('init', True)

# Datasheet worked example uses UT=27898 (0x6CFA) with a *different* raw
# UP=23843. But pressure() re-reads OUT_MSB for both UT (2 bytes) and UP
# (3 bytes) from the same register within one call, and this mock always
# returns the register map's *current* contents - it cannot hand back a
# different UT then a different UP within a single call. So UT and the top
# 16 bits of UP are necessarily the same value here (0x6CFA = 27898); the
# expected T/p below are computed from the real compensation formula with
# UT=UP=27898, not the datasheet's mismatched worked example.
connection.set_register(BMP180Full._REG_OUT_MSB, 0x6C, 0xFA)
check_true('temperature', sensor.temperature() == 15.0)

ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP180Full._REG_CTRL_MEAS]
check_true('temperature_writes_cmd_temp', ctrl_meas_writes[-1][1] == BMP180Full._CMD_TEMP)

connection.set_register(BMP180Full._REG_OUT_MSB, 0x6C, 0xFA)
check_true('pressure', abs(sensor.pressure() - 820.8) < 1e-6)

ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP180Full._REG_CTRL_MEAS]
check_true('pressure_writes_cmd_pressure_oss0', ctrl_meas_writes[-1][1] == BMP180Full._CMD_PRESSURE[0])

# chip_id(): expect 0x55.
connection.set_register(BMP180Full._REG_ID, 0x55)
check_true('chip_id', sensor.chip_id() == 0x55)

# oversampling()/set_oversampling()
check_true('oversampling_default', sensor.oversampling() == 0)
sensor.set_oversampling(BMP180Full.OSS_STANDARD)
check_true('set_oversampling', sensor.oversampling() == 1)
sensor.set_oversampling(0)  # restore ULP for the rest of the test

# altitude(sea_level_hpa=1013.25): pressure() re-reads UT/UP internally.
connection.set_register(BMP180Full._REG_OUT_MSB, 0x6C, 0xFA)
alt = sensor.altitude()
check_true('altitude_default_sea_level', abs(alt - 1741.7604174) < 1e-3)

# sea_level_pressure(altitude_m=100)
connection.set_register(BMP180Full._REG_OUT_MSB, 0x6C, 0xFA)
slp = sensor.sea_level_pressure(100)
check_true('sea_level_pressure', abs(slp - 830.599010429) < 1e-3)

# reset(): writes soft-reset command, then re-reads calibration coefficients.
preload_calibration(connection)
sensor.reset()
reset_writes = [w for w in connection.writes if len(w) == 2 and w[0] == BMP180Full._REG_SOFT_RESET]
check_true('reset_writes_soft_reset_cmd', reset_writes[-1][1] == BMP180Full._SOFT_RESET_CMD)
cal_reads = [w for w in connection.writes if len(w) == 1 and w[0] == BMP180Full._REG_CAL_START]
check_true('reset_rereads_calibration', len(cal_reads) >= 2)

# Invalid calibration data (a coefficient of 0x0000) raises ValueError.
bad_connection = I2CConnectionMock()
bad_connection.set_register(BMP180Full._REG_CAL_START,
                             0x00, 0x00,  # AC1 = 0 (invalid)
                             0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
                             0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34)
try:
    BMP180Full(bad_connection)
    check_true('invalid_calibration_raises', False)
except ValueError:
    check_true('invalid_calibration_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
