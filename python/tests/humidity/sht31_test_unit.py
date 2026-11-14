import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.sht31 import SHT31Full, SHT31Minimal, _crc8

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


def frame(raw_t, raw_rh):
    t = bytes([raw_t >> 8, raw_t & 0xFF])
    h = bytes([raw_rh >> 8, raw_rh & 0xFF])
    return t + bytes([_crc8(t)]) + h + bytes([_crc8(h)])


def make(full=True):
    c = I2CConnectionMock()
    return c, (SHT31Full if full else SHT31Minimal)(c)


def cmds(c):
    return [(w[0] << 8) | w[1] for w in c.writes]


# CRC reference value from the datasheet
check_true('crc8_beef', _crc8(bytes([0xBE, 0xEF])) == 0x92)

# init: soft reset only
c, s = make()
check_true('init_soft_reset', cmds(c) == [0x30A2])

# read: default single shot, high repeatability, no stretching
c, s = make()
del c.writes[:]
c.queue_read(frame(0x6666, 0x8000))        # T = -45 + 175*0x6666/65535 = 25.0; RH = 50.0 (approx)
t, rh = s.read()
check_true('read_command', cmds(c) == [0x2400])
check_close('read_temperature', t, -45.0 + 175.0 * 0x6666 / 65535.0)
check_close('read_humidity', rh, 100.0 * 0x8000 / 65535.0)
check_true('minimal_class_read', SHT31Minimal(I2CConnectionMock()) is not None)

# NACKed read header: polls until ready
c, s = make()
c.fail_next_reads(3)
c.queue_read(frame(0, 65535))
t, rh = s.read()
check_close('read_after_nacks_t', t, -45.0)
check_close('read_after_nacks_rh', rh, 100.0)

# CRC mismatch: one retry, then ValueError
c, s = make()
del c.writes[:]
bad = bytearray(frame(0x1234, 0x5678)); bad[2] ^= 0xFF
c.queue_read(bytes(bad))
c.queue_read(frame(0x1234, 0x5678))
t, rh = s.read()
check_true('crc_retry_resends_command', cmds(c) == [0x2400, 0x2400])
check_close('crc_retry_value', t, -45.0 + 175.0 * 0x1234 / 65535.0)
c.queue_read(bytes(bad))
c.queue_read(bytes(bad))
check_true('crc_persistent_raises', raises(s.read, ValueError))

# raw, single values
c, s = make()
c.queue_read(frame(1000, 2000))
check_true('read_raw', s.read_raw() == (1000, 2000))
c.queue_read(frame(0, 0))
check_close('read_temperature_only', s.read_temperature(), -45.0)
c.queue_read(frame(0, 65535))
check_close('read_humidity_only', s.read_humidity(), 100.0)

# repeatability and clock stretching
c, s = make()
del c.writes[:]
s.set_repeatability('low')
c.queue_read(frame(0, 0)); s.read()
s.set_repeatability('medium')
c.queue_read(frame(0, 0)); s.read()
s.set_clock_stretching(True)
c.queue_read(frame(0, 0)); s.read()
s.set_repeatability('high')
c.queue_read(frame(0, 0)); s.read()
check_true('repeatability_commands', cmds(c) == [0x2416, 0x240B, 0x2C0D, 0x2C06])
check_true('repeatability_invalid', raises(lambda: s.set_repeatability('ultra'), ValueError))

# periodic mode
c, s = make()
del c.writes[:]
s.start_periodic(2)
s.start_periodic(0.5, 'low')
s.start_periodic(10, 'medium')
s.start_periodic(1, art=True)
check_true('periodic_commands', cmds(c) == [0x2236, 0x202F, 0x2721, 0x2B32])
check_true('periodic_bad_rate', raises(lambda: s.start_periodic(3), ValueError))
check_true('periodic_bad_repeatability', raises(lambda: s.start_periodic(1, 'x'), ValueError))
del c.writes[:]
c.queue_read(frame(0x4000, 0x4000))
res = s.fetch()
check_true('fetch_command', cmds(c) == [0xE000])
check_close('fetch_value', res[0], -45.0 + 175.0 * 0x4000 / 65535.0)
c.fail_next_reads(1)
check_true('fetch_none_when_nacked', s.fetch() is None)
del c.writes[:]
s.stop_periodic()
check_true('stop_periodic', cmds(c) == [0x3093])

# heater, status, reset
del c.writes[:]
s.set_heater(True)
s.set_heater(False)
s.clear_status()
check_true('heater_and_clear_commands', cmds(c) == [0x306D, 0x3066, 0x3041])
c.queue_read(bytes([0x80, 0x10, _crc8(bytes([0x80, 0x10]))]))
check_true('read_status', s.read_status() == 0x8010 and cmds(c)[-1] == 0xF32D)
c.queue_read(bytes([0x80, 0x10, 0x00]))
check_true('read_status_crc', raises(s.read_status, ValueError))
del c.writes[:]
s.soft_reset()
check_true('soft_reset', cmds(c) == [0x30A2])

# dew point: 25 degC / 50 %RH -> 13.86 degC (Magnus)
c, s = make()
raw_t = int(round((25.0 + 45.0) * 65535.0 / 175.0))
raw_rh = int(round(50.0 * 65535.0 / 100.0))
c.queue_read(frame(raw_t, raw_rh))
check_close('dew_point', s.dew_point(), 13.86, 0.05)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
