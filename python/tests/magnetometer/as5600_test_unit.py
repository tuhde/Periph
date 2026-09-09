import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.magnetometer.as5600 import AS5600Full

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


connection = I2CConnectionMock()

# STATUS: MD=1 (magnet detected), MH=0, ML=0.
connection.set_register(AS5600Full._REG_STATUS, 0x08)

sensor = AS5600Full(connection)
check_true('init', True)

check_true('is_magnet_detected', sensor.is_magnet_detected())
check_true('is_magnet_too_strong_false', not sensor.is_magnet_too_strong())
check_true('is_magnet_too_weak_false', not sensor.is_magnet_too_weak())

# ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291.
connection.set_register(AS5600Full._REG_ANGLE_H, 0x01, 0x23)
check_true('angle_raw', sensor.angle_raw() == 291)
check_true('angle', abs(sensor.angle() - (291 * 360.0 / 4096)) < 1e-9)

# RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees.
connection.set_register(AS5600Full._REG_RAW_ANGLE_H, 0x02, 0x00)
check_true('raw_angle', sensor.raw_angle() == 512)
check_true('raw_angle_degrees', abs(sensor.raw_angle_degrees() - 45.0) < 1e-9)

connection.set_register(AS5600Full._REG_AGC, 128)
check_true('agc', sensor.agc() == 128)

# MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100.
connection.set_register(AS5600Full._REG_MAGNITUDE_H, 0x00, 0x64)
check_true('magnitude', sensor.magnitude() == 100)

# STATUS: MD=1, MH=1 (magnet too strong).
connection.set_register(AS5600Full._REG_STATUS, 0x28)
check_true('is_magnet_too_strong_true', sensor.is_magnet_too_strong())
check_true('status_byte', sensor.status_byte() == 0x28)

# configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5).
connection.set_register(AS5600Full._REG_CONF_H, 0xC5, 0x00)
sensor.configure(pm=1, hyst=2, outs=1, pwmf=3, sf=2, fth=5, wd=True)
check_true('configure',
           connection.registers[AS5600Full._REG_CONF_H] == 0xF6 and
           connection.registers[AS5600Full._REG_CONF_L] == 0xD9)

sensor.set_zero_position(1000)
check_true('zero_position', sensor.zero_position() == 1000)

sensor.set_max_position(2000)
check_true('max_position', sensor.max_position() == 2000)

sensor.set_max_angle(2048)
check_true('max_angle', sensor.max_angle() == 2048)

connection.set_register(AS5600Full._REG_ZMCO, 0x02)
check_true('burn_count', sensor.burn_count() == 2)

# burn_angle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80.
sensor.burn_angle()
check_true('burn_angle', connection.writes[-1] == bytes([AS5600Full._REG_BURN, 0x80]))

# burn_setting(): requires ZMCO=0.
connection.set_register(AS5600Full._REG_ZMCO, 0x00)
sensor.burn_setting()
check_true('burn_setting', connection.writes[-1] == bytes([AS5600Full._REG_BURN, 0x40]))

# burn_angle() must raise when magnet not detected.
connection.set_register(AS5600Full._REG_STATUS, 0x00)
try:
    sensor.burn_angle()
    check_true('burn_angle_raises_no_magnet', False)
except RuntimeError:
    check_true('burn_angle_raises_no_magnet', True)

# burn_angle() must raise when ZMCO limit (3) reached.
connection.set_register(AS5600Full._REG_STATUS, 0x08)
connection.set_register(AS5600Full._REG_ZMCO, 0x03)
try:
    sensor.burn_angle()
    check_true('burn_angle_raises_zmco_limit', False)
except RuntimeError:
    check_true('burn_angle_raises_zmco_limit', True)

# burn_setting() must raise when ZMCO != 0.
connection.set_register(AS5600Full._REG_ZMCO, 0x01)
try:
    sensor.burn_setting()
    check_true('burn_setting_raises_zmco_nonzero', False)
except RuntimeError:
    check_true('burn_setting_raises_zmco_nonzero', True)

# init() must raise when no magnet is detected.
no_magnet_connection = I2CConnectionMock()
no_magnet_connection.set_register(AS5600Full._REG_STATUS, 0x00)
try:
    AS5600Full(no_magnet_connection)
    check_true('init_raises_no_magnet', False)
except RuntimeError:
    check_true('init_raises_no_magnet', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
