import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.pressure.ms5607 import MS5607Full, MS5607Minimal, _crc4

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


def trunc_div(a, b):
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


# Datasheet example coefficients (C1..C6)
COEFFS = [46372, 43981, 29059, 27842, 31553, 28165]
D1_EX = 6465444
D2_EX = 8077636


def make_prom(serial=0x5A3):
    words = [0x3132] + COEFFS + [serial << 4]
    words[7] |= _crc4(words)
    return words


def reference(d1, d2, second_order=True):
    """Straight transcription of the datasheet algorithm (truncating C division)."""
    c1, c2, c3, c4, c5, c6 = COEFFS
    dt = d2 - c5 * 256
    temp = 2000 + trunc_div(dt * c6, 2 ** 23)
    off = c2 * 2 ** 17 + trunc_div(c4 * dt, 2 ** 6)
    sens = c1 * 2 ** 16 + trunc_div(c3 * dt, 2 ** 7)
    if second_order and temp < 2000:
        t2 = trunc_div(dt * dt, 2 ** 31)
        off2 = trunc_div(61 * (temp - 2000) ** 2, 16)
        sens2 = 2 * (temp - 2000) ** 2
        if temp < -1500:
            off2 += 15 * (temp + 1500) ** 2
            sens2 += 8 * (temp + 1500) ** 2
        temp -= t2
        off -= off2
        sens -= sens2
    return temp / 100.0, float(trunc_div(trunc_div(d1 * sens, 2 ** 21) - off, 2 ** 15))


class ChipMock(I2CConnectionMock):
    """Command-protocol MS5607 model; an ADC read returns the result once, then 0."""

    def __init__(self, d1=D1_EX, d2=D2_EX, prom=None):
        super().__init__()
        self.d1 = d1
        self.d2 = d2
        self.prom = prom if prom is not None else make_prom()
        self.last = None
        self.adc = 0
        self.convert_cmds = []

    def write(self, data):
        super().write(data)
        cmd = data[0]
        self.last = cmd
        if 0x40 <= cmd <= 0x5F:
            self.convert_cmds.append(cmd)
            self.adc = self.d2 if cmd & 0x10 else self.d1

    def read(self, n):
        if self.last == 0x00:
            value, self.adc = self.adc, 0
            return bytes([value >> 16, (value >> 8) & 0xFF, value & 0xFF])[:n]
        if self.last is not None and 0xA0 <= self.last <= 0xAE:
            w = self.prom[(self.last - 0xA0) // 2]
            return bytes([w >> 8, w & 0xFF])[:n]
        return bytes(n)


def cmds(c):
    return [w[0] for w in c.writes]


# CRC-4: an all-zero PROM gives 0; make_prom() output verifies
check_true('crc4_zero', _crc4([0] * 8) == 0)
prom = make_prom()
check_true('crc4_roundtrip', _crc4(prom) == (prom[7] & 0xF))
check_true('crc4_detects_change', _crc4([prom[0] ^ 1] + prom[1:]) != (prom[7] & 0xF))

# constructor: no bus access; init: reset, 8 PROM reads
c = ChipMock()
s = MS5607Full(c)
check_true('ctor_no_bus_access', c.writes == [])
s.init()
check_true('init_sequence', cmds(c) == [0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE])
check_true('init_coefficients', s.coefficients() == tuple(COEFFS))
check_true('init_crc_ok', s.prom_crc_ok())
check_true('serial_code', s.serial_code() == 0x5A3)
bad = make_prom()
bad[3] ^= 0x0100
check_true('init_bad_crc', raises(lambda: MS5607Minimal(ChipMock(prom=bad)).init(), ValueError))

# datasheet example: first-order values 20.00 degC / 110002 Pa
s.set_oversampling(4096)
r = s.compensate(D1_EX, D2_EX)
check_true('example_temperature', near(r['temperature_c'], 20.0))
check_true('example_pressure', near(r['pressure_pa'], 110002.0))
check_true('example_first_order', s.compensate(D1_EX, D2_EX, second_order=False) == r)

# Minimal read: D2 first, then D1, at OSR 4096
c = ChipMock()
m = MS5607Minimal(c)
m.init()
c.writes.clear()
r = m.read()
check_true('read_commands', cmds(c) == [0x58, 0x00, 0x48, 0x00])
check_true('read_values', near(r['temperature_c'], 20.0) and near(r['pressure_pa'], 110002.0))

# second-order compensation against the reference transcription
for d2 in (D2_EX - 300000, D2_EX - 1200000, D2_EX + 400000):
    for d1 in (D1_EX, 5000000):
        c = ChipMock(d1=d1, d2=d2)
        s = MS5607Full(c)
        s.init()
        got = s.compensate(d1, d2)
        t, p = reference(d1, d2)
        check_true('compensate_%d_%d' % (d1, d2), near(got['temperature_c'], t) and near(got['pressure_pa'], p))
t_cold = reference(D1_EX, D2_EX - 300000)[0]
check_true('cold_branch_taken', t_cold < 20.0 and t_cold > -15.0)
check_true('very_cold_branch_taken', reference(D1_EX, D2_EX - 1200000)[0] < -15.0)
s = MS5607Full(ChipMock())
s.init()
first = s.compensate(D1_EX, D2_EX - 300000, second_order=False)
second = s.compensate(D1_EX, D2_EX - 300000)
check_true('second_order_changes_result', first != second)

# Full: single values and raw
c = ChipMock()
s = MS5607Full(c)
s.init()
check_true('read_pressure', near(s.read_pressure(), 110002.0))
c.writes.clear()
check_true('read_temperature', near(s.read_temperature(), 20.0))
check_true('read_temperature_d2_only', cmds(c) == [0x58, 0x00])
check_true('read_raw', s.read_raw() == (D1_EX, D2_EX))

# oversampling commands and validation
for i, osr in enumerate((256, 512, 1024, 2048, 4096)):
    c = ChipMock()
    s = MS5607Full(c)
    s.init()
    s.set_oversampling(osr)
    c.writes.clear()
    s.read()
    check_true('osr_%d_commands' % osr, c.convert_cmds[-2:] == [0x50 | (i << 1), 0x40 | (i << 1)])
    check_true('osr_%d_get' % osr, s.get_oversampling() == osr)
check_true('osr_invalid', raises(lambda: MS5607Full(ChipMock()).set_oversampling(300), ValueError))
s = MS5607Full(ChipMock())
check_true('conversion_time_table', [s.conversion_time(o) for o in (256, 512, 1024, 2048, 4096)] == [0.0006, 0.00117, 0.00228, 0.00454, 0.00904])
check_true('conversion_time_current', near(s.conversion_time(), 0.00904))
check_true('conversion_time_invalid', raises(lambda: s.conversion_time(100), ValueError))
check_true('default_osr', s.get_oversampling() == 4096)

# non-blocking start_conversion / read_adc
c = ChipMock()
s = MS5607Full(c)
s.init()
c.writes.clear()
s.start_conversion(MS5607Full.TEMPERATURE)
check_true('start_conversion_temperature', cmds(c) == [0x58])
s.start_conversion(MS5607Full.PRESSURE, 512)
check_true('start_conversion_pressure_osr', cmds(c)[-1] == 0x42)
check_true('start_conversion_invalid', raises(lambda: s.start_conversion(0, 99), ValueError))
check_true('read_adc_value', s.read_adc() == D1_EX)
check_true('read_adc_repeat_is_error', raises(s.read_adc, ValueError))

# read without any conversion started returns 0 -> error
c = ChipMock()
s = MS5607Full(c)
s.init()
check_true('adc_zero_error', raises(s.read_adc, ValueError))

# reset reloads the PROM
c = ChipMock()
s = MS5607Full(c)
s.init()
c.writes.clear()
s.reset()
check_true('reset_commands', cmds(c)[0] == 0x1E and len(cmds(c)) == 9)

# read_prom
check_true('read_prom_words', MS5607Full(ChipMock()).read_prom() == make_prom())

# altitude: international barometric formula
s = MS5607Full(ChipMock())
s.init()
expected = 44330.0 * (1.0 - (110002.0 / 101325.0) ** (1.0 / 5.255))
check_true('altitude_formula', near(s.altitude(), expected, 1e-6))
check_true('altitude_negative_below_sea_level', s.altitude() < 0)
check_true('altitude_reference', near(s.altitude(110002.0), 0.0, 1e-6))

# bus errors propagate
c = ChipMock()
s = MS5607Full(c)
c.fail_next_writes(1)
check_true('bus_error_propagates', raises(s.init, OSError))

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(1 if failed else 0)
