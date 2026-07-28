import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.aht21 import AHT21Full

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

# Status byte with CAL (0x08) and the paired 0x10 bit both set, so __init__'s
# (status & 0x18) != 0x18 check passes and no soft-reset/recalibration path runs.
connection.queue_read(bytes([0x18]))

sensor = AHT21Full(connection)
check_true('init_no_reset', not any(w == AHT21Full._CMD_SOFT_RESET for w in connection.writes))

# 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
# raw_rh = data[1]<<12 | data[2]<<4 | data[3]>>4        = 0x80000 (524288 / 2**20 * 100 = 50.0)
# raw_t  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]     = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
_MEASUREMENT = bytes([0x00, 0x80, 0x00, 0x08, 0x00, 0x00])

connection.queue_read(_MEASUREMENT)
data = sensor.read()
check_true('read_triggers', connection.writes[-1] == AHT21Full._CMD_TRIGGER)
check_true('read_humidity_value', data['humidity_pct'] == 50.0)
check_true('read_temperature_value', data['temperature_c'] == 50.0)

connection.queue_read(_MEASUREMENT)
check_true('read_temperature', sensor.read_temperature() == 50.0)

connection.queue_read(_MEASUREMENT)
check_true('read_humidity', sensor.read_humidity() == 50.0)

# read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
connection.queue_read(_MEASUREMENT + bytes([0x8B]))
with_crc = sensor.read_with_crc()
check_true('read_with_crc_values', with_crc['temperature_c'] == 50.0 and with_crc['humidity_pct'] == 50.0)
check_true('read_with_crc_ok', with_crc['crc_ok'] is True)

connection.queue_read(_MEASUREMENT + bytes([0x00]))
check_true('read_with_crc_detects_bad_crc', sensor.read_with_crc()['crc_ok'] is False)

sensor.soft_reset()
check_true('soft_reset', connection.writes[-1] == AHT21Full._CMD_SOFT_RESET)

connection.queue_read(bytes([0x08]))
check_true('is_calibrated_true', sensor.is_calibrated() is True)

connection.queue_read(bytes([0x00]))
check_true('is_calibrated_false', sensor.is_calibrated() is False)

connection.queue_read(bytes([0x80]))
check_true('is_busy_true', sensor.is_busy() is True)

connection.queue_read(bytes([0x00]))
check_true('is_busy_false', sensor.is_busy() is False)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
