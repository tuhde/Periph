import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.aht10 import AHT10Full

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

# CAL bit (0x08) set after the init command, so no soft-reset retry runs.
connection.queue_read(bytes([0x08]))

sensor = AHT10Full(connection)
check_true('init_sends_e1', connection.writes[0] == AHT10Full._CMD_INIT)
check_true('init_no_reset', not any(w == AHT10Full._CMD_SOFT_RESET for w in connection.writes))

# 6-byte response encoding humidity=50.0%, temperature=50.0 degC:
# raw_rh = 0x80000 (524288 / 2**20 * 100 = 50.0), raw_t = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
_MEASUREMENT = bytes([0x08, 0x80, 0x00, 0x08, 0x00, 0x00])

connection.queue_read(_MEASUREMENT)
data = sensor.read()
check_true('read_triggers', connection.writes[-1] == AHT10Full._CMD_TRIGGER)
check_true('read_humidity_value', data['humidity_pct'] == 50.0)
check_true('read_temperature_value', data['temperature_c'] == 50.0)

connection.queue_read(_MEASUREMENT)
check_true('read_temperature', sensor.read_temperature() == 50.0)

connection.queue_read(_MEASUREMENT)
check_true('read_humidity', sensor.read_humidity() == 50.0)

connection.queue_read(_MEASUREMENT)
check_true('read_raw', sensor.read_raw() == (0x80000, 0x80000))

# BUSY set on first read, cleared on the second: driver re-reads.
connection.queue_read(bytes([0x88, 0x80, 0x00, 0x08, 0x00, 0x00]))
connection.queue_read(_MEASUREMENT)
check_true('read_polls_busy', sensor.read()['temperature_c'] == 50.0)

# Spec example: raw_t = 0x6B300 -> 33.7 degC
connection.queue_read(bytes([0x08, 0x80, 0x00, 0x06, 0xB3, 0x00]))
check_true('read_temperature_example', abs(sensor.read_temperature() - 33.74) < 0.01)

# Dew point at 50 degC / 50 %RH is about 36.7 degC.
connection.queue_read(_MEASUREMENT)
check_true('dew_point', abs(sensor.dew_point() - 36.7) < 0.1)

connection.queue_read(bytes([0x08]))
check_true('read_status', sensor.read_status() == 0x08)

sensor.soft_reset()
check_true('soft_reset', connection.writes[-2] == AHT10Full._CMD_SOFT_RESET
           and connection.writes[-1] == AHT10Full._CMD_INIT)

connection.queue_read(bytes([0x08]))
check_true('is_calibrated_true', sensor.is_calibrated() is True)

connection.queue_read(bytes([0x00]))
check_true('is_calibrated_false', sensor.is_calibrated() is False)

connection.queue_read(bytes([0x80]))
check_true('is_busy_true', sensor.is_busy() is True)

connection.queue_read(bytes([0x00]))
check_true('is_busy_false', sensor.is_busy() is False)

# Uncalibrated after the first init: soft reset + retry, then success.
c2 = I2CConnectionMock()
c2.queue_read(bytes([0x00]))
c2.queue_read(bytes([0x08]))
AHT10Full(c2)
check_true('init_retry', c2.writes.count(AHT10Full._CMD_INIT) == 2 and AHT10Full._CMD_SOFT_RESET in c2.writes)

# Still uncalibrated after retry: error.
c3 = I2CConnectionMock()
c3.queue_read(bytes([0x00]))
c3.queue_read(bytes([0x00]))
try:
    AHT10Full(c3)
    check_true('init_uncalibrated_raises', False)
except RuntimeError:
    check_true('init_uncalibrated_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
