import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.si7021 import Si7021Full, Si7021Minimal, _crc8

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


def frame(code):
    d = bytes([code >> 8, code & 0xFF])
    return d + bytes([_crc8(d)])


def make(full=True):
    c = I2CConnectionMock()
    return c, (Si7021Full if full else Si7021Minimal)(c)


def cmds(c):
    return [w[0] for w in c.writes]


def rh_code(rh):
    return int(round((rh + 6.0) * 65536.0 / 125.0))


def t_code(t):
    return int(round((t + 46.85) * 65536.0 / 175.72))


# CRC-8 (poly 0x31, init 0)
check_true('crc8_zero', _crc8(bytes([0x00, 0x00])) == 0x00)
check_true('crc8_single', _crc8(bytes([0x01])) == 0x31)

# init: no bus access
c, s = make()
check_true('init_no_bus_access', c.writes == [])

# read: 0xF5 then 0xE0
c, s = make()
c.queue_read(frame(rh_code(50.0)))
c.queue_read(bytes([t_code(25.0) >> 8, t_code(25.0) & 0xFF]))
t, rh = s.read()
check_true('read_commands', cmds(c) == [0xF5, 0xE0])
check_close('read_humidity', rh, 50.0, 0.01)
check_close('read_temperature', t, 25.0, 0.01)
check_true('minimal_class_read', Si7021Minimal(I2CConnectionMock()) is not None)

# NACKed read header: polls until ready
c, s = make()
c.fail_next_reads(3)
c.queue_read(frame(rh_code(40.0)))
c.queue_read(bytes([0x66, 0x66]))
t, rh = s.read()
check_close('read_after_nacks_rh', rh, 40.0, 0.01)

# never ready: OSError
c, s = make()
c.fail_next_reads(1000)
check_true('timeout_raises', raises(s.read, OSError))

# CRC mismatch
c, s = make()
bad = bytearray(frame(0x6000)); bad[2] ^= 0xFF
c.queue_read(bytes(bad))
check_true('crc_mismatch_raises', raises(s.read, ValueError))

# clamping
c, s = make()
c.queue_read(frame(0x0000))
c.queue_read(bytes([0, 0]))
check_close('clamp_low', s.read()[1], 0.0)
c.queue_read(frame(0xFFFC))
c.queue_read(bytes([0, 0]))
check_close('clamp_high', s.read()[1], 100.0)

# single values and raw codes
c, s = make()
del c.writes[:]
c.queue_read(frame(rh_code(30.0)))
check_close('read_humidity_only', s.read_humidity(), 30.0, 0.01)
c.queue_read(frame(t_code(-10.0)))
check_close('read_temperature_only', s.read_temperature(), -10.0, 0.01)
c.queue_read(bytes([t_code(20.0) >> 8, t_code(20.0) & 0xFF]))
check_close('read_temperature_previous', s.read_temperature_previous(), 20.0, 0.01)
check_true('single_commands', cmds(c) == [0xF5, 0xF3, 0xE0])
c.queue_read(frame(0x1234))
check_true('read_raw_humidity', s.read_raw_humidity() == 0x1234)
c.queue_read(frame(0x4321))
check_true('read_raw_temperature', s.read_raw_temperature() == 0x4321)

# clock stretching uses hold-master commands
c, s = make()
del c.writes[:]
s.set_clock_stretching(True)
c.queue_read(frame(rh_code(50.0)))
c.queue_read(bytes([0x66, 0x66]))
s.read()
c.queue_read(frame(0x6666))
s.read_temperature()
check_true('hold_master_commands', cmds(c) == [0xE5, 0xE0, 0xE3])

# user register: resolution, heater, vdd
c, s = make()
c.set_register(0xE7, 0x3A)
del c.writes[:]
s.set_resolution(11, 11)
check_true('set_resolution_write', c.writes[-1] == bytes([0xE6, 0xBB]))
del c.writes[:]
s.set_resolution(8, 12)
check_true('set_resolution_8_12', c.writes[-1] == bytes([0xE6, 0x3B]))
del c.writes[:]
s.set_resolution(10, 13)
check_true('set_resolution_10_13', c.writes[-1] == bytes([0xE6, 0xBA]))
check_true('set_resolution_invalid', raises(lambda: s.set_resolution(12, 12), ValueError))
c.set_register(0xE7, 0xBB)
check_true('get_resolution_11_11', s.get_resolution() == (11, 11))
c.set_register(0xE7, 0x3B)
check_true('get_resolution_8_12', s.get_resolution() == (8, 12))
c.set_register(0xE7, 0xBA)
check_true('get_resolution_10_13', s.get_resolution() == (10, 13))
c.set_register(0xE7, 0x3A)
check_true('get_resolution_default', s.get_resolution() == (12, 14))
del c.writes[:]
s.set_heater(True)
check_true('heater_on_write', c.writes[-1] == bytes([0xE6, 0x3E]))
c.set_register(0xE7, 0x3E)
check_true('is_heater_on', s.is_heater_on())
del c.writes[:]
s.set_heater(False)
check_true('heater_off_write', c.writes[-1] == bytes([0xE6, 0x3A]))
c.set_register(0xE7, 0x3A)
check_true('heater_off_read', not s.is_heater_on())
check_true('vdd_ok', not s.vdd_low())
c.set_register(0xE7, 0x7A)
check_true('vdd_low', s.vdd_low())
c.set_register(0xE7, 0x3A)
check_true('read_user_register', s.read_user_register() == 0x3A)
del c.writes[:]
s.write_user_register(0x00)
check_true('write_user_register_keeps_reserved', c.writes[-1] == bytes([0xE6, 0x3A]))

# conversion time at default resolution: 10 ms + 7 ms
check_close('conversion_time', s.conversion_time(), 0.017)

# software reset, identification
del c.writes[:]
s.software_reset()
check_true('software_reset', cmds(c) == [0xFE])
c = I2CConnectionMock()
c.set_register(0xFA0F, 0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0)
c.set_register(0xFCC9, 0x15, 0x66, 0, 0x77, 0x88, 0)
s = Si7021Full(c)
check_true('serial_number', s.serial_number() == 0x1122334415667788)
check_true('device_type', s.device_type() == 0x15)
c.set_register(0x84B8, 0xFF)
check_true('firmware_revision', s.firmware_revision() == 0xFF)

# dew point: 25 degC / 50 %RH -> 13.86 degC (Magnus)
c, s = make()
c.queue_read(frame(rh_code(50.0)))
c.queue_read(bytes([t_code(25.0) >> 8, t_code(25.0) & 0xFF]))
check_close('dew_point', s.dew_point(), 13.86, 0.05)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
