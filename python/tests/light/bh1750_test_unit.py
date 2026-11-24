import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.light.bh1750 import BH1750Full, BH1750Minimal

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


class ChipMock(I2CConnectionMock):
    """Command-protocol BH1750 model: every read returns the same 16-bit result."""

    def __init__(self, raw=0x8342):
        super().__init__()
        self.raw = raw

    def read(self, n):
        return bytes([self.raw >> 8, self.raw & 0xFF])[:n]


def cmds(c):
    return [w[0] for w in c.writes]


# constructor: no bus access; init: power on
c = ChipMock()
s = BH1750Full(c)
check_true('ctor_no_bus_access', c.writes == [])
s.init()
check_true('init_power_on', cmds(c) == [0x01])

# Minimal read: one-time H mode, raw / 1.2 (datasheet example 0x8342)
c = ChipMock(0x8342)
check_true('read_value', near(BH1750Minimal(c).read(), 0x8342 / 1.2))
check_true('read_command', cmds(c) == [0x20])
c = ChipMock(0x0012)
check_true('read_small_value', near(BH1750Minimal(c).read(), 15.0))

# Full: one-time modes
c = ChipMock(0x0012)
s = BH1750Full(c)
check_true('measure_once_h', near(s.measure_once(BH1750Full.MODE_H), 15.0) and cmds(c) == [0x20])
c.writes = []
check_true('measure_once_h2', near(s.measure_once(BH1750Full.MODE_H2), 7.5) and cmds(c) == [0x21])
c.writes = []
check_true('measure_once_l', near(s.measure_once(BH1750Full.MODE_L), 15.0) and cmds(c) == [0x23])
check_true('measure_once_bad_mode', raises(lambda: s.measure_once(7), ValueError))

# read / read_raw follow the current mode
c = ChipMock(0x0012)
s = BH1750Full(c)
s.measure_once(BH1750Full.MODE_H2)
c.writes = []
check_true('read_uses_current_mode', near(s.read(), 7.5) and cmds(c) == [0x21])
c.writes = []
check_true('read_raw', s.read_raw() == 0x12 and cmds(c) == [0x21])

# continuous mode
c = ChipMock(0x0012)
s = BH1750Full(c)
s.start_continuous(BH1750Full.MODE_H)
s.start_continuous(BH1750Full.MODE_H2)
s.start_continuous(BH1750Full.MODE_L)
check_true('continuous_opcodes', cmds(c) == [0x10, 0x11, 0x13])
s.start_continuous(BH1750Full.MODE_H2)
c.writes = []
check_true('read_continuous', near(s.read_continuous(), 7.5) and c.writes == [])
check_true('continuous_bad_mode', raises(lambda: s.start_continuous(-1), ValueError))

# power control and reset
c = ChipMock()
s = BH1750Full(c)
s.power_down()
s.power_on()
check_true('power_opcodes', cmds(c) == [0x00, 0x01])
c.writes = []
s.reset()
check_true('reset_powers_on_first', cmds(c) == [0x01, 0x07])

# measurement-time register
c = ChipMock()
s = BH1750Full(c)
check_true('mtreg_default', s.get_measurement_time_register() == 69)
s.set_measurement_time(69)
check_true('mtreg_69_opcodes', cmds(c) == [0x42, 0x65])
c.writes = []
s.set_measurement_time(254)
check_true('mtreg_254_opcodes', cmds(c) == [0x47, 0x7E])
check_true('mtreg_cached', s.get_measurement_time_register() == 254)
c.writes = []
s.set_measurement_time(31)
check_true('mtreg_31_opcodes', cmds(c) == [0x40 | 0, 0x60 | 31])
c.writes = []
check_true('mtreg_low_rejected', raises(lambda: s.set_measurement_time(30), ValueError))
check_true('mtreg_high_rejected', raises(lambda: s.set_measurement_time(255), ValueError))
check_true('mtreg_rejected_no_write', c.writes == [])
check_true('mtreg_unchanged_after_reject', s.get_measurement_time_register() == 31)

# sensitivity, lux per count and measurement time scale with MTreg
c = ChipMock()
s = BH1750Full(c)
check_true('lux_per_count_default', near(s.lux_per_count(), 1 / 1.2))
check_true('lux_per_count_h2', near(s.lux_per_count(BH1750Full.MODE_H2), 0.5 / 1.2))
check_true('measurement_time_h', near(s.measurement_time(), 0.180))
check_true('measurement_time_l', near(s.measurement_time(BH1750Full.MODE_L), 0.024))
s.set_sensitivity(2.0)
check_true('sensitivity_mtreg', s.get_measurement_time_register() == 138)
check_true('sensitivity_lux_per_count', near(s.lux_per_count(), 1 / 1.2 / 2))
check_true('sensitivity_time', near(s.measurement_time(), 0.360))
c.raw = 0x0012
check_true('sensitivity_scales_read', near(s.measure_once(BH1750Full.MODE_H), 15.0 / 2))
check_true('sensitivity_low_limit', raises(lambda: s.set_sensitivity(0.4), ValueError))
check_true('sensitivity_high_limit', raises(lambda: s.set_sensitivity(3.7), ValueError))
s.set_sensitivity(0.45)
check_true('sensitivity_min', s.get_measurement_time_register() == 31)
s.set_sensitivity(3.68)
check_true('sensitivity_max', s.get_measurement_time_register() == 254)

# Minimal inherits default behaviour; Full reads honour MTreg
c = ChipMock(0x0012)
s = BH1750Full(c)
s.set_measurement_time(138)
check_true('full_read_honours_mtreg', near(s.read(), 7.5))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
