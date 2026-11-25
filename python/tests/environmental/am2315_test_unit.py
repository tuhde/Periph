import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.environmental.am2315 import AM2315Full, AM2315Minimal, AM2315Error, _crc16

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


def check_close(label, got, expected, tol=1e-6):
    check_true(label, abs(got - expected) <= tol)


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


def resp(function, *payload):
    body = bytes([function]) + bytes(payload)
    crc = _crc16(body)
    return body + bytes([crc & 0xFF, crc >> 8])


def read_resp(data):
    return resp(0x03, len(data), *data)


def make(full=True):
    c = I2CConnectionMock()
    s = (AM2315Full if full else AM2315Minimal)(c)
    s._interval_ms = 0          # tests do not wait the 2 s between transactions
    return c, s


# CRC-16 (datasheet examples, low byte first)
check_true('crc16_example1', _crc16(bytes([0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA])) == 0xA531)
check_true('crc16_example2', _crc16(bytes([0x03, 0x04, 0x03, 0x39, 0x01, 0x15])) == 0xFEE1)
check_true('crc16_write', _crc16(bytes([0x10, 0x10, 0x02, 0x01, 0x02])) == 0x92C0)

# constructor: no bus access
c, s = make()
check_true('ctor_no_bus_access', c.writes == [])

# read: wake, command, decode
c, s = make()
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
r = s.read()
check_true('read_writes', c.writes == [bytes([0x00]), bytes([0x03, 0x00, 0x04])])
check_close('read_humidity', r['humidity_pct'], 50.0)
check_close('read_temperature', r['temperature_c'], 25.0)

c, s = make()
c.queue_read(bytes([0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE]))
r = s.read()
check_close('datasheet_humidity', r['humidity_pct'], 82.5)
check_close('datasheet_temperature', r['temperature_c'], 27.7)

# sign-magnitude temperature
c, s = make()
c.queue_read(read_resp([0x01, 0xF4, 0x80, 0x65]))
check_close('negative_temperature', s.read()['temperature_c'], -10.1)

# wake NACK is ignored
c, s = make()
c.fail_next_writes(1)
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
check_close('wake_nack_ignored', s.read()['humidity_pct'], 50.0)

# CRC mismatch: retried once (new wake), then ValueError
bad = bytearray(read_resp([0x01, 0xF4, 0x00, 0xFA]))
bad[-1] ^= 0xFF
c, s = make()
c.queue_read(bytes(bad))
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
check_close('crc_retry_recovers', s.read()['humidity_pct'], 50.0)
check_true('crc_retry_wakes_again', c.writes.count(bytes([0x00])) == 2)
c, s = make()
c.queue_read(bytes(bad))
c.queue_read(bytes(bad))
check_true('crc_mismatch_raises', raises(s.read, ValueError))

# bus error on the read: retried once, then OSError
c, s = make()
c.fail_next_reads(1)
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
check_close('bus_retry_recovers', s.read()['temperature_c'], 25.0)
c, s = make()
c.fail_next_reads(2)
check_true('bus_error_raises', raises(s.read, OSError))

# error response
c, s = make()
c.queue_read(bytes([0x83, 0x00, 0x00, 0x00]))
try:
    s.read()
    check_true('error_response_raises', False)
except AM2315Error as e:
    check_true('error_response_raises', True)
    check_true('error_response_code', e.code == 0x83)

# minimum interval is enforced
c = I2CConnectionMock()
s = AM2315Minimal(c)
s._interval_ms = 50
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
import time
start = time.time()
s.read()
check_true('interval_enforced', time.time() - start >= 0.04)

# Full: single values, raw
c, s = make()
c.queue_read(read_resp([0x00, 0x7B]))
check_close('read_humidity_only', s.read_humidity(), 12.3)
check_true('read_humidity_cmd', c.writes[-1] == bytes([0x03, 0x00, 0x02]))
c.queue_read(read_resp([0x80, 0x65]))
check_close('read_temperature_only', s.read_temperature(), -10.1)
check_true('read_temperature_cmd', c.writes[-1] == bytes([0x03, 0x02, 0x02]))
c.queue_read(read_resp([0x01, 0xF4, 0x80, 0x65]))
check_true('read_raw', s.read_raw() == (0x01F4, 0x8065))

# read_fresh: two transactions, second result returned
c, s = make()
c.queue_read(read_resp([0x01, 0xF4, 0x00, 0xFA]))
c.queue_read(read_resp([0x01, 0xF9, 0x01, 0x00]))
r = s.read_fresh()
check_close('read_fresh_second', r['humidity_pct'], 50.5)
check_close('read_fresh_temp', r['temperature_c'], 25.6)

# register read
c, s = make()
c.queue_read(read_resp([1, 2, 3]))
check_true('read_registers', s.read_registers(0x08, 3) == bytes([1, 2, 3]))
check_true('read_registers_count_0', raises(lambda: s.read_registers(0, 0), ValueError))
check_true('read_registers_count_11', raises(lambda: s.read_registers(0, 11), ValueError))

# device info
c, s = make()
c.queue_read(read_resp([0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78]))
info = s.read_device_info()
check_true('device_info_cmd', c.writes[-1] == bytes([0x03, 0x08, 0x07]))
check_true('device_info', info == {'model': 0x090F, 'version': 0x10, 'device_id': 0x12345678})

# user registers
c, s = make()
c.queue_read(read_resp([0x12, 0x34, 0xAB, 0xCD]))
check_true('read_user_registers', s.read_user_registers() == (0x1234, 0xABCD))
check_true('read_user_registers_cmd', c.writes[-1] == bytes([0x03, 0x10, 0x04]))

c, s = make()
c.queue_read(resp(0x10, 0x10, 0x04))
s.write_user_registers(0x1234, 0xABCD)
body = bytes([0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD])
crc = _crc16(body)
check_true('write_user_registers_frame', c.writes[-1] == body + bytes([crc & 0xFF, crc >> 8]))

c, s = make()
c.queue_read(resp(0x10, 0x10, 0x02))
s.write_registers(0x10, bytes([1, 2]))
check_true('write_registers_frame', c.writes[-1][:5] == bytes([0x10, 0x10, 0x02, 1, 2]))
check_true('write_registers_crc', c.writes[-1][5:] == bytes([0xC0, 0x92]))
check_true('write_registers_reject_low', raises(lambda: s.write_registers(0x0F, bytes([1])), ValueError))
check_true('write_registers_reject_high', raises(lambda: s.write_registers(0x13, bytes([1, 2])), ValueError))
check_true('write_registers_reject_empty', raises(lambda: s.write_registers(0x10, b''), ValueError))

# write error response
c, s = make()
c.queue_read(bytes([0x82, 0x00, 0x00, 0x00, 0x00]))
check_true('write_error_response', raises(lambda: s.write_registers(0x10, bytes([1])), AM2315Error))

# wake
c, s = make()
s.wake()
check_true('wake_write', c.writes == [bytes([0x00])])
c, s = make()
c.fail_next_writes(1)
s.wake()
check_true('wake_ignores_nack', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
