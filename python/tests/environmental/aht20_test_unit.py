import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.aht20 import AHT20Full

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

# Status 0x18 (idle + calibrated): no reset or calibration init runs.
connection.queue_read(bytes([0x18]))

sensor = AHT20Full(connection)
check_true('init_reads_status', connection.writes[0] == AHT20Full._CMD_STATUS)
check_true('init_no_reset', connection.writes == [AHT20Full._CMD_STATUS])

# 6-byte response encoding humidity=50.0%, temperature=50.0 degC:
# raw_rh = 0x80000 (524288 / 2**20 * 100 = 50.0), raw_t = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
_MEASUREMENT = bytes([0x08, 0x80, 0x00, 0x08, 0x00, 0x00])

connection.queue_read(_MEASUREMENT)
data = sensor.read()
check_true('read_triggers', connection.writes[-1] == AHT20Full._CMD_TRIGGER)
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

# Spec example: raw_t = 0x6B300 (439040) -> 33.7 degC
connection.queue_read(bytes([0x08, 0x80, 0x00, 0x06, 0xB3, 0x00]))
check_true('read_temperature_example', abs(sensor.read_temperature() - 33.74) < 0.01)

# Dew point at 50 degC / 50 %RH is about 36.7 degC.
connection.queue_read(_MEASUREMENT)
check_true('dew_point', abs(sensor.dew_point() - 36.7) < 0.1)

connection.queue_read(bytes([0x08]))
check_true('read_status', sensor.read_status() == 0x08)
check_true('read_status_cmd', connection.writes[-1] == AHT20Full._CMD_STATUS)

# CRC-8 (poly 0x31, init 0xFF) over the 6 frame bytes;.
frame = bytes([0x1C, 0x80, 0x00, 0x08, 0x00, 0x00])
crc = AHT20Full._crc8(frame)
connection.queue_read(frame + bytes([crc]))
res = sensor.read_with_crc()
check_true('read_with_crc_ok', res['crc_ok'] is True and res['humidity_pct'] == 50.0
           and res['temperature_c'] == 50.0)
connection.queue_read(frame + bytes([crc ^ 0xFF]))
check_true('read_with_crc_bad', sensor.read_with_crc()['crc_ok'] is False)
check_true('crc8_known_vector', AHT20Full._crc8(bytes([0xBE, 0xEF])) == 0x92)

# Soft reset re-runs the calibration check (status 0x18 -> no calibration init).
connection.queue_read(bytes([0x18]))
sensor.soft_reset()
check_true('soft_reset', connection.writes[-2] == AHT20Full._CMD_SOFT_RESET
           and connection.writes[-1] == AHT20Full._CMD_STATUS)

connection.queue_read(bytes([0x08]))
check_true('is_calibrated_true', sensor.is_calibrated() is True)

connection.queue_read(bytes([0x00]))
check_true('is_calibrated_false', sensor.is_calibrated() is False)

connection.queue_read(bytes([0x80]))
check_true('is_busy_true', sensor.is_busy() is True)

connection.queue_read(bytes([0x00]))
check_true('is_busy_false', sensor.is_busy() is False)

# Uncalibrated at power-on: soft reset, then success (no calibration init).
c2 = I2CConnectionMock()
c2.queue_read(bytes([0x00]))
c2.queue_read(bytes([0x18]))
AHT20Full(c2)
check_true('init_reset', AHT20Full._CMD_SOFT_RESET in c2.writes and AHT20Full._CMD_CAL_INIT[0] not in c2.writes)

# Still uncalibrated after the reset: calibration registers are written, then success.
c4 = I2CConnectionMock()
c4.queue_read(bytes([0x00]))
c4.queue_read(bytes([0x00]))
c4.queue_read(bytes([0x18]))
AHT20Full(c4)
check_true('init_cal_init', [w for w in c4.writes if len(w) == 3] == list(AHT20Full._CMD_CAL_INIT))

# Still uncalibrated after calibration init: error.
c3 = I2CConnectionMock()
c3.queue_read(bytes([0x00]))
c3.queue_read(bytes([0x00]))
c3.queue_read(bytes([0x00]))
try:
    AHT20Full(c3)
    check_true('init_uncalibrated_raises', False)
except RuntimeError:
    check_true('init_uncalibrated_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
