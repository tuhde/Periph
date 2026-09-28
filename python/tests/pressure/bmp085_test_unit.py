import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.bmp085 import BMP085Minimal, BMP085Full

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
    # Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
    # AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
    connection.set_register(0xAA,
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


def last_write_to(connection, reg):
    for w in reversed(connection.writes):
        if len(w) == 2 and w[0] == reg:
            return w[1]
    return None


# --- Minimal constructor: reads and unpacks calibration coefficients ---
connection = I2CConnectionMock()
preload_calibration(connection)
sensor = BMP085Minimal(connection)
check_true('init_reads_calibration', sensor._ac1 == 408 and sensor._mc == -8711)

# --- Calibration sanity check regression: raw 0xFFFF must be rejected even
# for SIGNED coefficients (unpacks to -1, not 0xFFFF, after sign conversion) ---
bad_connection = I2CConnectionMock()
bad_connection.set_register(0xAA, *([0xFF, 0xFF] * 11))  # every word raw 0xFFFF
try:
    BMP085Minimal(bad_connection)
    check_true('bad_calibration_all_ffff_raises', False)
except ValueError:
    check_true('bad_calibration_all_ffff_raises', True)

bad_connection2 = I2CConnectionMock()
# Only AC1 (a SIGNED coefficient) is the sentinel 0xFFFF; rest are the
# datasheet's valid worked-example values. The buggy version compared the
# *signed* value (-1) against the literal 0xFFFF and never caught this.
bad_connection2.set_register(0xAA,
                              0xFF, 0xFF,  # AC1 raw 0xFFFF -> signed -1
                              0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
                              0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34)
try:
    BMP085Minimal(bad_connection2)
    check_true('bad_calibration_signed_ffff_raises', False)
except ValueError:
    check_true('bad_calibration_signed_ffff_raises', True)

bad_connection3 = I2CConnectionMock()
bad_connection3.set_register(0xAA, *([0x00, 0x00] * 11))  # every word 0x0000
try:
    BMP085Minimal(bad_connection3)
    check_true('bad_calibration_all_zero_raises', False)
except ValueError:
    check_true('bad_calibration_all_zero_raises', True)

# --- temperature()/pressure(): datasheet worked example (UT=UP=27898 due
# to the mock's static register map -- both reads hit the same OUT_MSB
# address within one call, so they can't differ within a single test step) ---
connection.set_register(0xF6, 0x6C, 0xFA)  # UT = 27898
check_true('temperature_known_value', sensor.temperature() == 15.0)

ctrl_meas_writes = [w for w in connection.writes if len(w) == 2 and w[0] == 0xF4]
check_true('temperature_writes_cmd_temp', ctrl_meas_writes[-1][1] == 0x2E)

connection.set_register(0xF6, 0x6C, 0xFA)
check_true('pressure_known_value', abs(sensor.pressure() - 82080.0) < 1e-6)

# --- Full: oversampling ---
full_conn = I2CConnectionMock()
preload_calibration(full_conn)
full = BMP085Full(full_conn, oss=BMP085Full.OSS_HIGH_RES)
check_true('constructor_oss', full.oversampling() == 2)
full.set_oversampling(3)
check_true('set_oversampling', full.oversampling() == 3)
full_conn.set_register(0xF6, 0x6C, 0xFA, 0x00)
full.pressure()
ctrl_meas_writes = [w for w in full_conn.writes if len(w) == 2 and w[0] == 0xF4]
check_true('pressure_writes_cmd_for_oss3', ctrl_meas_writes[-1][1] == 0xF4)

# --- altitude()/sea_level_pressure() ---
alt_conn = I2CConnectionMock()
preload_calibration(alt_conn)
alt_sensor = BMP085Full(alt_conn)
alt_conn.set_register(0xF6, 0x6C, 0xFA)
alt = alt_sensor.altitude(101325.0)
check_true('altitude_computed', alt > 0)  # 82080 Pa is below sea-level ref -> positive altitude

sl_conn = I2CConnectionMock()
preload_calibration(sl_conn)
sl_sensor = BMP085Full(sl_conn)
sl_conn.set_register(0xF6, 0x6C, 0xFA)
sl = sl_sensor.sea_level_pressure(0.0)
check_true('sea_level_pressure_at_zero_alt', abs(sl - 82080.0) < 1e-6)

# --- chip_id() ---
id_conn = I2CConnectionMock()
preload_calibration(id_conn)
id_sensor = BMP085Full(id_conn)
id_conn.set_register(0xD0, 0x55)
check_true('chip_id', id_sensor.chip_id() == 0x55)

# --- reset(): writes soft-reset key, re-reads calibration ---
reset_conn = I2CConnectionMock()
preload_calibration(reset_conn)
reset_sensor = BMP085Full(reset_conn)
reset_sensor.reset()
check_true('reset_writes_key', last_write_to(reset_conn, 0xE0) == 0xB6)
check_true('reset_rereads_calibration', reset_sensor._ac1 == 408)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
