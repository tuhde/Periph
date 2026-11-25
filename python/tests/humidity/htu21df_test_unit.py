import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.htu21df import HTU21DFFull, HTU21DFMinimal, _crc8

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
    return c, (HTU21DFFull if full else HTU21DFMinimal)(c)


def cmds(c):
    return [w[0] for w in c.writes]


def rh_code(rh):
    return (int(round((rh + 6.0) * 65536.0 / 125.0)) & 0xFFFC) | 0x02


def t_code(t):
    return int(round((t + 46.85) * 65536.0 / 175.72)) & 0xFFFC


# CRC-8 (datasheet examples)
check_true('crc8_683A', _crc8(bytes([0x68, 0x3A])) == 0x7C)
check_true('crc8_4E85', _crc8(bytes([0x4E, 0x85])) == 0x6B)
check_true('crc8_DC', _crc8(bytes([0xDC])) == 0x79)

# constructor: no bus access
c, s = make()
check_true('ctor_no_bus_access', c.writes == [])

# init: soft reset
s.init()
check_true('init_soft_reset', cmds(c) == [0xFE])

# read: 0xF3 then 0xF5, status bits ignored
c, s = make()
c.queue_read(frame(t_code(25.0)))
c.queue_read(frame(rh_code(50.0)))
t, rh = s.read()
check_true('read_commands', cmds(c) == [0xF3, 0xF5])
check_close('read_humidity', rh, 50.0, 0.05)
check_close('read_temperature', t, 25.0, 0.05)
check_true('minimal_class_read', HTU21DFMinimal(I2CConnectionMock()) is not None)

# datasheet value: 0x683A -> status bits cleared 0x6838
c, s = make()
c.queue_read(frame(0x683A))
c.queue_read(frame(0x683A))
t, rh = s.read()
check_close('conv_temperature', t, -46.85 + 175.72 * 0x6838 / 65536.0)
check_close('conv_humidity', rh, -6.0 + 125.0 * 0x6838 / 65536.0)

# NACKed read header: polls until ready
c, s = make()
c.fail_next_reads(3)
c.queue_read(frame(t_code(20.0)))
c.queue_read(frame(rh_code(40.0)))
t, rh = s.read()
check_close('read_after_nacks_rh', rh, 40.0, 0.05)

# never ready: OSError
c, s = make()
c.fail_next_reads(1000)
check_true('timeout_raises', raises(s.read, OSError))

# CRC mismatch: retried once, then ValueError
c, s = make()
bad = bytearray(frame(0x6000)); bad[2] ^= 0xFF
c.queue_read(bytes(bad))
c.queue_read(bytes(bad))
check_true('crc_mismatch_raises', raises(s.read, ValueError))
c, s = make()
c.queue_read(bytes(bad))
c.queue_read(frame(t_code(25.0)))
c.queue_read(frame(rh_code(50.0)))
check_close('crc_retry_recovers', s.read()[0], 25.0, 0.05)
check_true('crc_retry_commands', cmds(c) == [0xF3, 0xF3, 0xF5])

# not clamped
c, s = make()
c.queue_read(frame(0))
c.queue_read(frame(0))
check_close('humidity_not_clamped', s.read()[1], -6.0)

# single values and raw codes
c, s = make()
c.queue_read(frame(rh_code(30.0)))
check_close('read_humidity_only', s.read_humidity(), 30.0, 0.05)
c.queue_read(frame(t_code(-10.0)))
check_close('read_temperature_only', s.read_temperature(), -10.0, 0.05)
check_true('single_commands', cmds(c) == [0xF5, 0xF3])
c.queue_read(frame(0x1237))
c.queue_read(frame(0x4321))
check_true('read_raw', s.read_raw() == (0x1234, 0x4320))

# compensated humidity: 50 %RH at 35 degC -> 50 + (25-35)*-0.15 = 51.5
c, s = make()
c.queue_read(frame(t_code(35.0)))
c.queue_read(frame(rh_code(50.0)))
check_close('humidity_compensated', s.read_humidity_compensated(), 51.5, 0.1)

# hold master uses 0xE3 / 0xE5
c, s = make()
s.set_hold_master(True)
c.queue_read(frame(t_code(25.0)))
c.queue_read(frame(rh_code(50.0)))
s.read()
check_true('hold_master_commands', cmds(c) == [0xE3, 0xE5])

# user register: resolution, heater, battery
c, s = make()
c.set_register(0xE7, 0x02)
del c.writes[:]
s.set_resolution(3)
check_true('set_resolution_3', c.writes[-1] == bytes([0xE6, 0x83]))
s.set_resolution(1)
check_true('set_resolution_1', c.writes[-1] == bytes([0xE6, 0x03]))
s.set_resolution(2)
check_true('set_resolution_2', c.writes[-1] == bytes([0xE6, 0x82]))
check_true('set_resolution_invalid', raises(lambda: s.set_resolution(4), ValueError))
for reg, res in ((0x83, 3), (0x03, 1), (0x82, 2), (0x02, 0)):
    c.set_register(0xE7, reg)
    check_true('get_resolution_%d' % res, s.get_resolution() == res)
c.set_register(0xE7, 0x02)
s.set_heater(True)
check_true('heater_on_write', c.writes[-1] == bytes([0xE6, 0x06]))
c.set_register(0xE7, 0x06)
check_true('heater_on', s.heater())
s.set_heater(False)
check_true('heater_off_write', c.writes[-1] == bytes([0xE6, 0x02]))
c.set_register(0xE7, 0x02)
check_true('heater_off', not s.heater())
check_true('battery_ok', not s.battery_low())
c.set_register(0xE7, 0x42)
check_true('battery_low', s.battery_low())
check_true('read_user_register', s.read_user_register() == 0x42)

# soft reset
del c.writes[:]
s.soft_reset()
check_true('soft_reset', cmds(c) == [0xFE])

# dew point: 25 degC / 50 %RH -> about 13.86 degC
c, s = make()
c.queue_read(frame(t_code(25.0)))
c.queue_read(frame(rh_code(50.0)))
check_close('dew_point', s.dew_point(), 13.86, 0.1)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
