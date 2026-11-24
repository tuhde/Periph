import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.shtc3 import SHTC3Full, SHTC3Minimal, _crc8

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


def near(a, b, tol=1e-6):
    return abs(a - b) < tol


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


def word(code):
    d = bytes([code >> 8, code & 0xFF])
    return d + bytes([_crc8(d)])


MEASURE = (0x7866, 0x7CA2, 0x609C, 0x6458)


class ChipMock(I2CConnectionMock):
    """Command-protocol SHTC3 model: sleeping chips ignore everything except wake-up."""

    def __init__(self, t_raw=0x6666, rh_raw=0x6000, ident=0x0807, asleep=False):
        super().__init__()
        self.t_raw = t_raw
        self.rh_raw = rh_raw
        self.ident = ident
        self.asleep = asleep
        self.last = None
        self.ignored = 0

    def write(self, data):
        super().write(data)
        cmd = (data[0] << 8) | data[1]
        if self.asleep and cmd != 0x3517:
            self.ignored += 1
            return
        if cmd == 0x3517:
            self.asleep = False
        elif cmd == 0xB098:
            self.asleep = True
        self.last = cmd

    def read(self, n):
        if self.last in MEASURE:
            out = word(self.t_raw) + word(self.rh_raw)
        elif self.last == 0xEFC8:
            out = word(self.ident)
        else:
            out = bytes(n)
        return out[:n]


def cmds(c):
    return [(w[0] << 8) | w[1] for w in c.writes]


# CRC-8 (datasheet vectors)
check_true('crc8_BEEF', _crc8(bytes([0xBE, 0xEF])) == 0x92)
check_true('crc8_00', _crc8(bytes([0x00])) == 0xAC)

# constructor: no bus access; init: wake, reset, read ID, sleep
c = ChipMock(asleep=True)
s = SHTC3Full(c)
check_true('ctor_no_bus_access', c.writes == [])
s.init()
check_true('init_sequence', cmds(c) == [0x3517, 0x805D, 0xEFC8, 0xB098])
check_true('init_leaves_asleep', c.asleep and c.ignored == 0)
check_true('init_wrong_id', raises(lambda: SHTC3Minimal(ChipMock(ident=0x0000)).init(), ValueError))

# Minimal read: wake, measure, read, sleep; conversion formulas
c = ChipMock(t_raw=0x6666, rh_raw=0x6000, asleep=True)
s = SHTC3Minimal(c)
s._asleep = True
m = s.read()
check_true('read_commands', cmds(c) == [0x3517, 0x7866, 0xB098])
check_true('read_ignored_none', c.ignored == 0)
check_true('read_temperature', near(m['temperature_c'], -45.0 + 175.0 * 0x6666 / 65536.0))
check_true('read_humidity', near(m['humidity_pct'], 100.0 * 0x6000 / 65536.0))
check_true('humidity_example_37_5', near(m['humidity_pct'], 37.5))

# clamping and range
c = ChipMock(rh_raw=0xFFFF)
check_true('humidity_full_scale', SHTC3Minimal(c).read()['humidity_pct'] <= 100.0)
c = ChipMock(rh_raw=0x0000, t_raw=0x0000)
m = SHTC3Minimal(c).read()
check_true('humidity_zero', near(m['humidity_pct'], 0.0))
check_true('temperature_min', near(m['temperature_c'], -45.0))
c = ChipMock(t_raw=0xFFFF)
check_true('temperature_max', near(SHTC3Minimal(c).read()['temperature_c'], 130.0, 0.01))

# CRC mismatch: retried once, then raises
c = ChipMock()
orig = c.read
c.read = lambda n: bytes([orig(n)[0] ^ 0x01]) + orig(n)[1:]
check_true('crc_mismatch_temperature', raises(lambda: SHTC3Minimal(c).read(), ValueError))
check_true('crc_mismatch_retried', cmds(c).count(0x7866) == 2)
c = ChipMock()
orig = c.read
c.read = lambda n: orig(n)[:5] + bytes([orig(n)[5] ^ 0xFF])
check_true('crc_mismatch_humidity', raises(lambda: SHTC3Minimal(c).read(), ValueError))
c = ChipMock()
orig = c.read
calls = []


def flaky(n):
    calls.append(1)
    data = orig(n)
    return bytes([data[0] ^ 1]) + data[1:] if len(calls) == 1 else data


c.read = flaky
check_true('crc_single_glitch_recovered', near(SHTC3Minimal(c).read()['humidity_pct'], 37.5))
check_true('crc_glitch_slept_once', cmds(c)[-1] == 0xB098 and cmds(c).count(0xB098) == 1)

# Full: single values and raw
c = ChipMock(t_raw=0x4000, rh_raw=0x4000)
s = SHTC3Full(c)
check_true('read_humidity', near(s.read_humidity(), 25.0))
check_true('read_temperature', near(s.read_temperature(), -45.0 + 175.0 * 0.25))
check_true('read_raw', s.read_raw() == (0x4000, 0x4000))

# low power and clock stretching command selection
c = ChipMock()
s = SHTC3Full(c)
s.set_low_power(True)
s.read()
check_true('low_power_command', 0x609C in cmds(c))
c = ChipMock()
s = SHTC3Full(c)
s.set_clock_stretching(True)
s.read()
check_true('stretching_command', 0x7CA2 in cmds(c))
c = ChipMock()
s = SHTC3Full(c)
s.set_low_power(True)
s.set_clock_stretching(True)
s.read()
check_true('low_power_stretching_command', 0x6458 in cmds(c))

# sleep between measurements
c = ChipMock()
s = SHTC3Full(c)
s.set_sleep_between(False)
s.read()
s.read()
check_true('no_sleep_between', 0xB098 not in cmds(c))
check_true('no_sleep_no_ignored', c.ignored == 0)
s.sleep()
check_true('manual_sleep', cmds(c)[-1] == 0xB098 and c.asleep)
s.wake()
check_true('manual_wake', cmds(c)[-1] == 0x3517 and not c.asleep)
c = ChipMock()
s = SHTC3Full(c)
s.read()
s.read()
check_true('sleep_between_wakes_each_time', cmds(c) == [0x7866, 0xB098, 0x3517, 0x7866, 0xB098])
check_true('sleep_between_no_ignored', c.ignored == 0)

# soft reset and ID
c = ChipMock()
s = SHTC3Full(c)
s.soft_reset()
check_true('soft_reset_command', cmds(c) == [0x805D])
c = ChipMock(ident=0xAB07)
s = SHTC3Full(c)
check_true('read_id_value', s.read_id() == 0xAB07 and cmds(c) == [0xEFC8])
check_true('read_id_product_code', (s.read_id() & 0x083F) == 0x0807)
c = ChipMock(asleep=True)
s = SHTC3Full(c)
s._asleep = True
check_true('read_id_wakes_and_sleeps', s.read_id() == 0x0807 and cmds(c) == [0x3517, 0xEFC8, 0xB098])
c = ChipMock()
orig = c.read
c.read = lambda n: orig(n)[:2] + bytes([orig(n)[2] ^ 0x55])
check_true('read_id_crc_mismatch', raises(lambda: SHTC3Full(c).read_id(), ValueError))

# dew point: Magnus, 25 degC / 50 %RH is about 13.9 degC
t_raw = int(round((25.0 + 45.0) * 65536.0 / 175.0))
rh_raw = 0x8000
c = ChipMock(t_raw=t_raw, rh_raw=rh_raw)
check_true('dew_point', abs(SHTC3Full(c).dew_point() - 13.86) < 0.15)
c = ChipMock(t_raw=t_raw, rh_raw=0)
check_true('dew_point_dry_finite', SHTC3Full(c).dew_point() < -20.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
