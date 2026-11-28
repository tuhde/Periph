import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.gas.rrh62000 import RRH62000Full, RRH62000Error, crc8

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


def make_block(status=0x0000, words=None):
    """Build a 37-byte block: status LE, 17 big-endian words, CRC-8."""
    if words is None:
        words = [1234, 1000, 500, 100, 20,      # NC x10
                 52, 125, 203,                  # PM KCl x10
                 61, 140, 230,                  # PM smoke x10
                 2345, 4567,                    # T x100, RH x100
                 30, 812, 215, 0]               # TVOC /10, eCO2, IAQ x100, rel IAQ
    b = bytes([status & 0xFF, status >> 8])
    for w in words:
        b += bytes([(w >> 8) & 0xFF, w & 0xFF])
    return b + bytes([crc8(b)])


check_true('crc8_beef', crc8(b'\xBE\xEF') == 0x92)
check_true('crc8_zero', crc8(b'\x00\x00') == 0x81)

conn = I2CConnectionMock()
conn.set_register(0x75, 2, 7)
s = RRH62000Full(conn, access_interval_ms=0)
s.init()
check_true('init_reads_fwver', conn.writes[-1] == bytes([0x75]))

conn.set_register(0x40, 0x01)
conn.set_register(0x00, *make_block(0x0005))
r = s.read()
check_true('read_keys', sorted(r) == sorted(['pm1_ug_m3', 'pm2_5_ug_m3', 'pm10_ug_m3', 'temperature_c',
                                              'humidity_rh', 'tvoc', 'eco2_ppm', 'iaq']))
check_true('read_pm', r['pm1_ug_m3'] == 5.2 and r['pm2_5_ug_m3'] == 12.5 and r['pm10_ug_m3'] == 20.3)
check_true('read_temperature', abs(r['temperature_c'] - 23.45) < 1e-9)
check_true('read_humidity', abs(r['humidity_rh'] - 45.67) < 1e-9)
check_true('read_tvoc', r['tvoc'] == 300)
check_true('read_eco2', r['eco2_ppm'] == 812)
check_true('read_iaq', abs(r['iaq'] - 2.15) < 1e-9)
check_true('read_polls_data_then_block', conn.writes[-2:] == [bytes([0x40]), bytes([0x00])])

a = s.read_all()
check_true('read_all_status', a['status'] == 5 and a['high_concentration'] and a['fan_speed']
           and not a['dust_accumulation'] and not a['fan'])
check_true('read_all_nc', a['nc_0_3'] == 123.4 and a['nc_4'] == 2.0)
check_true('read_all_smoke', a['pm1_smoke_ug_m3'] == 6.1 and a['pm10_smoke_ug_m3'] == 23.0)

conn.set_register(0x00, *make_block(0x0002, [0] * 11 + [(-512) & 0xFFFF] + [0] * 5))
st = s.read_status()
check_true('read_status', st == {'raw': 2, 'high_concentration': False, 'dust_accumulation': True,
                                 'fan_speed': False, 'fan': False})
check_true('read_status_no_data_poll', conn.writes[-1] == bytes([0x00]) and conn.writes[-2] != bytes([0x40]))
check_true('negative_temperature', s.read_all()['temperature_c'] == -5.12)
conn.set_register(0x00, *make_block())
p = s.read_particles()
check_true('read_particles', len(p) == 11 and p['pm2_5_smoke_ug_m3'] == 14.0 and p['nc_1'] == 50.0)

bad = bytearray(make_block())
bad[36] ^= 0xFF
conn.set_register(0x00, *bad)
try:
    s.read()
    check_true('crc_error_raises', False)
except RRH62000Error:
    check_true('crc_error_raises', True)
conn.set_register(0x00, *([0xFF] * 37))
try:
    s.read()
    check_true('startup_block_raises', False)
except RRH62000Error as e:
    check_true('startup_block_raises', 'start-up' in str(e))

conn.set_register(0x40, 0x00)
conn.set_register(0x00, *make_block())
s._DATA_TIMEOUT_MS = 0
try:
    s.read()
    check_true('data_timeout_raises', False)
except RRH62000Error:
    check_true('data_timeout_raises', True)
check_true('data_ready_false', s.data_ready() is False)
conn.set_register(0x40, 0x01)
check_true('data_ready_true', s.data_ready() is True)

s.sleep()
check_true('sleep', conn.writes[-1] == bytes([0x50, 0x00]))
s.wake()
check_true('wake', conn.writes[-1] == bytes([0x50, 0x80]))
s.start_cleaning()
check_true('start_cleaning', conn.writes[-1] == bytes([0x51, 0x01]))
conn.set_register(0x74, 0x01)
check_true('cleaning_done', s.cleaning_done() is True)
s.soft_reset()
check_true('soft_reset', conn.writes[-1] == bytes([0x52, 0x81]))
conn.fail_next_writes(1)
s.soft_reset()
check_true('soft_reset_nack_ignored', True)

s.set_moving_average(30)
check_true('moving_average', s.get_moving_average() == 30)
s.set_fan_speed(70)
check_true('fan_speed', s.get_fan_speed() == 70)
s.set_cleaning_interval(86400)
check_true('cleaning_interval_bytes', conn.registers[0x5A] == 0x0B and conn.registers[0x5B] == 0x40)
check_true('cleaning_interval', s.get_cleaning_interval() == 86400)
s.set_cleaning_time(20)
check_true('cleaning_time', s.get_cleaning_time() == 20)
for label, fn, value in (('moving_average_range', s.set_moving_average, 0),
                         ('moving_average_range_hi', s.set_moving_average, 61),
                         ('fan_speed_range', s.set_fan_speed, 59),
                         ('fan_speed_range_hi', s.set_fan_speed, 101),
                         ('cleaning_interval_multiple', s.set_cleaning_interval, 31),
                         ('cleaning_interval_range', s.set_cleaning_interval, 1814430),
                         ('cleaning_time_range', s.set_cleaning_time, 61)):
    try:
        fn(value)
        check_true(label, False)
    except ValueError:
        check_true(label, True)

conn.set_register(0x71, 0x00, 0x01, 0xE2, 0x40)
check_true('mox_resistance', s.read_mox_resistance() == 123456)
conn.set_register(0x72, 1, 2, 3, 4, 5, 6)
check_true('unique_id', s.unique_id() == bytes([1, 2, 3, 4, 5, 6]))
conn.set_register(0x73, 3, 1, 4)
check_true('algorithm_version', s.algorithm_version() == (3, 1, 4))
conn.set_register(0x75, 2, 7)
check_true('firmware_version', s.firmware_version() == (2, 7))

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(0 if failed == 0 else 1)
