import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.hs30xx import (HS30xxFull, HS30xxMinimal, HS3001Full, HS3001Minimal,
                                          HS3002Full, HS3003Minimal)

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


def frame(rh_raw, t_raw, status=0):
    return bytes([(status << 6) | (rh_raw >> 8), rh_raw & 0xFF, t_raw >> 6, (t_raw & 0x3F) << 2])


def make(full=True):
    c = I2CConnectionMock()
    return c, (HS30xxFull if full else HS30xxMinimal)(c)


# constructor: no bus access
c, s = make()
check_true('constructor_no_bus_access', c.writes == [])

# aliases
check_true('alias_hs3001_full', HS3001Full is HS30xxFull)
check_true('alias_hs3002_full', HS3002Full is HS30xxFull)
check_true('alias_hs3003_minimal', HS3003Minimal is HS30xxMinimal)
check_true('alias_hs3001_minimal', HS3001Minimal is HS30xxMinimal)

# datasheet-style conversions: 0x2000 -> 50 %RH, 0x1000 -> 1.25 degC
c, s = make(False)
c.queue_read(frame(0x2000, 0x1000))
m = s.read()
check_true('read_request_is_empty_write', c.writes == [b''])
check_close('read_humidity', m['humidity_pct'], 8192 / 16383 * 100.0)
check_close('read_temperature', m['temperature_c'], 4096 / 16383 * 165.0 - 40.0)
check_close('humidity_50', m['humidity_pct'], 50.0, 0.01)
check_close('temperature_1_25', m['temperature_c'], 1.25, 0.01)

# full scale and zero
c, s = make(False)
c.queue_read(frame(0x3FFF, 0x3FFF))
m = s.read()
check_close('humidity_full_scale', m['humidity_pct'], 100.0)
check_close('temperature_full_scale', m['temperature_c'], 125.0)
c.queue_read(frame(0, 0))
m = s.read()
check_close('humidity_zero', m['humidity_pct'], 0.0)
check_close('temperature_zero', m['temperature_c'], -40.0)

# undetermined bits 1:0 of byte 3 are masked off
c, s = make(False)
c.queue_read(bytes([0x20, 0x00, 0x10, 0x03]))
check_close('masks_undetermined_bits', s.read()['temperature_c'], 0x400 / 16383 * 165.0 - 40.0)

# stale then valid: one retry
c, s = make(False)
c.queue_read(frame(0x2000, 0x1000, 1))
c.queue_read(frame(0x2000, 0x1000))
check_close('stale_retry_ok', s.read()['humidity_pct'], 50.0, 0.01)
check_true('stale_retry_single_request', c.writes == [b''])

# stale twice: raises
c, s = make(False)
c.queue_read(frame(0, 0, 1))
c.queue_read(frame(0, 0, 1))
check_true('stale_twice_raises', raises(s.read, RuntimeError))

# Full: single values, raw, wait
c, s = make()
c.queue_read(frame(0x2000, 0x1000))
check_close('read_temperature_full', s.read_temperature(), 1.25, 0.01)
c.queue_read(bytes([0x20, 0x00]))
check_close('read_humidity_full', s.read_humidity(), 50.0, 0.01)
c.queue_read(frame(0x1234, 0x0ABC))
check_true('read_raw', s.read_raw() == (0x1234, 0x0ABC, 0))
c.queue_read(frame(0x1234, 0x0ABC, 1))
check_true('read_raw_status_stale', s.read_raw()[2] == 1)
check_true('set_measurement_wait_invalid', raises(lambda: s.set_measurement_wait(0), ValueError))
s.set_measurement_wait(5)
c.queue_read(frame(0x2000, 0x1000))
check_close('read_after_wait_override', s.read_humidity(), 50.0, 0.01)

# programming mode commands
c, s = make()
s.enter_programming_mode()
s.exit_programming_mode()
check_true('programming_commands', c.writes == [bytes([0xA0, 0, 0]), bytes([0x80, 0, 0])])

# read_nvm
c, s = make()
c.queue_read(bytes([0x81, 0x12, 0x34]))
check_true('read_nvm_value', s.read_nvm(0x1E) == 0x1234)
check_true('read_nvm_command', c.writes == [bytes([0x1E, 0, 0])])
c.queue_read(bytes([0x80, 0x12, 0x34]))
check_true('read_nvm_bad_status', raises(lambda: s.read_nvm(0x1E), RuntimeError))

# NVM access without a power_cycle raises before any bus traffic
c, s = make()
check_true('get_resolution_needs_power_cycle', raises(s.get_resolution, RuntimeError))
check_true('set_resolution_needs_power_cycle', raises(lambda: s.set_resolution(12, 12), RuntimeError))
check_true('read_id_needs_power_cycle', raises(s.read_id, RuntimeError))
check_true('no_bus_without_power_cycle', c.writes == [])

# get_resolution: register bits 11:10 (default 0xC00 = 14 bit)
c, s = make()
cycles = []
c.queue_read(bytes([0x81, 0x0C, 0x00]))   # humidity 14 bit
c.queue_read(bytes([0x81, 0x08, 0x05]))   # temperature 12 bit, other bits set
check_true('get_resolution', s.get_resolution(lambda: cycles.append(1)) == (14, 12))
check_true('get_resolution_power_cycled', cycles == [1])
check_true('get_resolution_sequence', c.writes == [bytes([0xA0, 0, 0]), bytes([0x06, 0, 0]),
                                                   bytes([0x11, 0, 0]), bytes([0x80, 0, 0])])

# set_resolution: read-modify-write preserves the other bits
c, s = make()
c.queue_read(bytes([0x81, 0x0C, 0x07]))
c.queue_read(bytes([0x81, 0x0C, 0x21]))
s.set_resolution(8, 12, lambda: None)
check_true('set_resolution_sequence', c.writes == [
    bytes([0xA0, 0, 0]),
    bytes([0x06, 0, 0]), bytes([0x46, 0x00, 0x07]),
    bytes([0x11, 0, 0]), bytes([0x51, 0x08, 0x21]),
    bytes([0x80, 0, 0])])

# set_resolution: only one channel
c, s = make()
c.queue_read(bytes([0x81, 0x00, 0x00]))
s.set_resolution(temperature_bits=10, power_cycle=lambda: None)
check_true('set_resolution_temperature_only', c.writes == [
    bytes([0xA0, 0, 0]), bytes([0x11, 0, 0]), bytes([0x51, 0x04, 0x00]), bytes([0x80, 0, 0])])

# set_resolution: invalid values raise before any bus traffic
c, s = make()
check_true('set_resolution_invalid', raises(lambda: s.set_resolution(9, None, lambda: None), ValueError))
check_true('set_resolution_invalid_no_bus', c.writes == [])

# a failed NVM read still leaves programming mode
c, s = make()
c.queue_read(bytes([0x00, 0, 0]))
check_true('read_failure_raises', raises(lambda: s.get_resolution(lambda: None), RuntimeError))
check_true('read_failure_exits_programming', c.writes[-1] == bytes([0x80, 0, 0]))

# read_id
c, s = make()
c.queue_read(bytes([0x81, 0xDE, 0xAD]))
c.queue_read(bytes([0x81, 0xBE, 0xEF]))
check_true('read_id', s.read_id(lambda: None) == 0xDEADBEEF)

# dew point: 25 degC / 50 %RH -> about 13.86 degC
c, s = make()
c.queue_read(frame(int(0.5 * 16383), int((25.0 + 40.0) / 165.0 * 16383)))
check_close('dew_point', s.dew_point(), 13.86, 0.1)
c.queue_read(frame(0, 0x1000))
check_close('dew_point_zero_humidity', s.dew_point(), -273.15)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
