import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.htu31d import HTU31DFull, HTU31DMinimal, _crc8

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


class ChipMock(I2CConnectionMock):
    """Command-protocol HTU31D model: a read returns what the last read command selected."""

    def __init__(self, t_raw=0x683A, rh_raw=0x8000, diag=0x00, serial=0x123456):
        super().__init__()
        self.t_raw = t_raw
        self.rh_raw = rh_raw
        self.diag = diag
        self.serial = serial
        self.last = None

    def write(self, data):
        super().write(data)
        self.last = bytes(data)[0]
        if self.last == 0x04:
            self.diag |= 0x01
        elif self.last in (0x02, 0x1E):
            self.diag &= ~0x01

    def read(self, n):
        if self.last == 0x00:
            out = word(self.t_raw) + word(self.rh_raw)
        elif self.last == 0x10:
            out = word(self.rh_raw)
        elif self.last == 0x08:
            out = bytes([self.diag])
        elif self.last == 0x0A:
            d = bytes([(self.serial >> 16) & 0xFF, (self.serial >> 8) & 0xFF, self.serial & 0xFF])
            out = d + bytes([_crc8(d)])
        else:
            out = bytes(n)
        return out[:n]


def cmds(c):
    return [w[0] for w in c.writes]


# CRC-8 (datasheet vectors)
check_true('crc8_DC', _crc8(bytes([0xDC])) == 0x79)
check_true('crc8_683A', _crc8(bytes([0x68, 0x3A])) == 0x7C)
check_true('crc8_4E85', _crc8(bytes([0x4E, 0x85])) == 0x6B)

# constructor: no bus access; init: reset
c = ChipMock()
s = HTU31DFull(c)
check_true('ctor_no_bus_access', c.writes == [])
s.init()
check_true('init_reset', cmds(c) == [0x1E])

# Minimal read: 0x5E, wait, 0x00, both CRCs, conversion formulas
c = ChipMock(t_raw=0x683A, rh_raw=0x8000)
m = HTU31DMinimal(c).read()
check_true('read_commands', cmds(c) == [0x5E, 0x00])
check_true('read_temperature', near(m['temperature_c'], -40.0 + 165.0 * 0x683A / 65535.0))
check_true('read_humidity', near(m['humidity_pct'], 100.0 * 0x8000 / 65535.0))

# humidity clamped 0..100
c = ChipMock(rh_raw=0xFFFF)
check_true('humidity_full_scale', near(HTU31DMinimal(c).read()['humidity_pct'], 100.0))
c = ChipMock(rh_raw=0x0000)
check_true('humidity_zero', near(HTU31DMinimal(c).read()['humidity_pct'], 0.0))
c = ChipMock(t_raw=0x0000)
check_true('temperature_min', near(HTU31DMinimal(c).read()['temperature_c'], -40.0))
c = ChipMock(t_raw=0xFFFF)
check_true('temperature_max', near(HTU31DMinimal(c).read()['temperature_c'], 125.0))

# CRC mismatch raises (temperature word, humidity word)
c = ChipMock()
orig = c.read
c.read = lambda n: bytes([orig(n)[0] ^ 0x01]) + orig(n)[1:]
check_true('crc_mismatch_temperature', raises(lambda: HTU31DMinimal(c).read(), ValueError))
c = ChipMock()
orig = c.read
c.read = lambda n: orig(n)[:5] + bytes([orig(n)[5] ^ 0xFF])
check_true('crc_mismatch_humidity', raises(lambda: HTU31DMinimal(c).read(), ValueError))

# Full: single values
c = ChipMock(t_raw=0x4000, rh_raw=0x4000)
s = HTU31DFull(c)
check_true('read_humidity_command', near(s.read_humidity(), 100.0 * 0x4000 / 65535.0) and cmds(c) == [0x5E, 0x10])
c.writes = []
check_true('read_temperature_command', near(s.read_temperature(), -40.0 + 165.0 * 0x4000 / 65535.0) and cmds(c) == [0x5E, 0x00])
c.writes = []
check_true('read_raw', s.read_raw() == (0x4000, 0x4000))

# resolution: conversion command byte and conversion time
c = ChipMock()
s = HTU31DFull(c)
check_true('default_resolution', s.get_resolution() == (3, 3))
check_true('conversion_time_osr33', near(s.conversion_time(), 0.00834 + 0.01198))
s.set_resolution(0, 0)
s.read()
check_true('convert_osr00', cmds(c)[0] == 0x40)
check_true('conversion_time_osr00', near(s.conversion_time(), 0.00111 + 0.00157))
c.writes = []
s.set_resolution(2, 1)
s.read()
check_true('convert_osr21', cmds(c)[0] == (0x40 | (2 << 3) | (1 << 1)))
c.writes = []
s.set_resolution(1, 2)
s.start_conversion()
check_true('start_conversion_only', cmds(c) == [0x40 | (1 << 3) | (2 << 1)])
check_true('get_resolution', s.get_resolution() == (1, 2))
check_true('resolution_rejects_high_rh', raises(lambda: s.set_resolution(4, 0), ValueError))
check_true('resolution_rejects_negative_t', raises(lambda: s.set_resolution(0, -1), ValueError))

# read_result: 0x00 only
c = ChipMock(t_raw=0x2000, rh_raw=0x6000)
s = HTU31DFull(c)
m = s.read_result()
check_true('read_result_command', cmds(c) == [0x00])
check_true('read_result_values', near(m['humidity_pct'], 100.0 * 0x6000 / 65535.0))

# heater
c = ChipMock()
s = HTU31DFull(c)
s.set_heater(True)
check_true('heater_on_command', cmds(c) == [0x04])
check_true('is_heater_on_true', s.is_heater_on())
s.set_heater(False)
check_true('heater_off_command', cmds(c)[2] == 0x02)
check_true('is_heater_on_false', not s.is_heater_on())

# diagnostic
c = ChipMock(diag=0xA5)
s = HTU31DFull(c)
check_true('diagnostic_byte', s.diagnostic() == 0xA5 and cmds(c) == [0x08])
f = s.diagnostic_flags()
check_true('flags_nvm_err', f['nvm_err'] is True)
check_true('flags_rh_overrun_clear', f['rh_overrun'] is False)
check_true('flags_rh_high_err', f['rh_high_err'] is True)
check_true('flags_t_high_err', f['t_high_err'] is True)
check_true('flags_heater_on', f['heater_on'] is True)
check_true('flags_t_low_err_clear', f['t_low_err'] is False)

# serial number
c = ChipMock(serial=0xABCDEF)
s = HTU31DFull(c)
check_true('serial_number', s.serial_number() == 0xABCDEF and cmds(c) == [0x0A])
c = ChipMock(serial=0xABCDEF)
orig = c.read
c.read = lambda n: orig(n)[:3] + bytes([orig(n)[3] ^ 0x55])
check_true('serial_crc_mismatch', raises(lambda: HTU31DFull(c).serial_number(), ValueError))

# software reset: command and defaults restored
c = ChipMock()
s = HTU31DFull(c)
s.set_resolution(0, 1)
s.software_reset()
check_true('software_reset_command', cmds(c) == [0x1E])
check_true('software_reset_defaults', s.get_resolution() == (3, 3))

# dew point: Magnus, 25 degC / 50 %RH is about 13.9 degC
t_raw = int(round((25.0 + 40.0) * 65535.0 / 165.0))
rh_raw = int(round(0.5 * 65535.0))
c = ChipMock(t_raw=t_raw, rh_raw=rh_raw)
check_true('dew_point', abs(HTU31DFull(c).dew_point() - 13.86) < 0.15)
c = ChipMock(t_raw=t_raw, rh_raw=0)
check_true('dew_point_dry_finite', HTU31DFull(c).dew_point() < -20.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
