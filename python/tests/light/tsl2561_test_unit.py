"""Unit test for the TSL2561 — runs without hardware using the I2C mock.

Verifies the initialization sequence, the command-byte addressing (byte, word,
block), the lux formula for both coefficient sets and all ratio segments,
saturation handling, gain / integration time / manual timing, auto-ranging,
power control, the interrupt window and interrupt clearing by observing the
mock's registers and writes.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.light.tsl2561 as m
from periph.chips.light.tsl2561 import TSL2561Minimal, TSL2561Full, calculate_lux

m._WAIT_FACTOR = 0.0  # skip the integration waits

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
    return abs(a - b) <= tol * max(1.0, abs(b))


def set_counts(mock, ch0, ch1):
    mock.set_register(0x9C, ch0 & 0xFF, ch0 >> 8, ch1 & 0xFF, ch1 >> 8)


def fresh(ch0=0, ch1=0):
    mock = I2CConnectionMock(registers={0x8A: 0x15})
    set_counts(mock, ch0, ch1)
    return mock, TSL2561Full(mock)


# --- init: CONTROL <- 0x03, read back, TIMING <- 0x02
mock, chip = fresh()
writes = [bytes(w) for w in mock.writes if len(w) > 1]
check_true('init_sequence', writes == [bytes([0x80, 0x03]), bytes([0x81, 0x02])])
check_true('init_reads_control', bytes([0x80]) in [bytes(w) for w in mock.writes])

class DeadMock(I2CConnectionMock):
    def write(self, data):
        I2CConnectionMock.write(self, data)
        self.registers[0x80] = 0x00

try:
    TSL2561Minimal(DeadMock())
    check_true('init_raises_on_bad_control', False)
except OSError:
    check_true('init_raises_on_bad_control', True)

# --- lux formula (T/FN/CL), independent reference
def ref(ch0, ch1, gain, scale, coeff):
    c0 = ch0 * (16.0 / gain) / scale
    c1 = ch1 * (16.0 / gain) / scale
    r = c1 / c0
    if r <= coeff[0][0]:
        return coeff[0][1] * c0 - coeff[0][2] * c0 * r ** 1.4
    for lim, a, b in coeff[1:]:
        if r <= lim:
            return a * c0 - b * c1
    return 0.0

T = ((0.50, 0.0304, 0.062), (0.61, 0.0224, 0.031), (0.80, 0.0128, 0.0153), (1.30, 0.00146, 0.00112))
CS = ((0.52, 0.0315, 0.0593), (0.65, 0.0229, 0.0291), (0.80, 0.0157, 0.0180), (1.30, 0.00338, 0.00260))

mock, chip = fresh(1000, 200)
check_true('read_lux_segment1', near(chip.read_lux(), ref(1000, 200, 1, 1.0, T)))
check_true('read_lux_value', abs(chip.read_lux() - 382.2) < 0.5)
for ch1, label in ((550, 'segment2'), (700, 'segment3'), (1000, 'segment4')):
    set_counts(mock, 1000, ch1)
    check_true('read_lux_' + label, near(chip.read_lux(), ref(1000, ch1, 1, 1.0, T)))
set_counts(mock, 1000, 1400)
check_true('read_lux_ratio_above_1_30', chip.read_lux() == 0.0)
set_counts(mock, 0, 0)
check_true('read_lux_dark', chip.read_lux() == 0.0)
check_true('calculate_lux_gain16_101ms', near(calculate_lux(500, 100, 16, 101), ref(500, 100, 16, 0.252, T)))
check_true('calculate_lux_14ms', near(calculate_lux(500, 300, 1, 14), ref(500, 300, 1, 0.034, T)))
for ch1 in (100, 600, 700, 1000):
    check_true('calculate_lux_cs_%d' % ch1, near(calculate_lux(1000, ch1, 1, 402, 'CS'), ref(1000, ch1, 1, 1.0, CS)))

# --- block read uses 0x9C, 4 bytes
mock, chip = fresh(0x1234, 0x0567)
check_true('read_raw', chip.read_raw() == (0x1234, 0x0567))
check_true('read_raw_block_cmd', bytes(mock.writes[-1]) == bytes([0x9C]))
lux, c0, c1 = chip.read_lux_raw()
check_true('read_lux_raw', (c0, c1) == (0x1234, 0x0567) and near(lux, ref(0x1234, 0x0567, 1, 1.0, T)))

# --- saturation
mock, chip = fresh(65535, 100)
try:
    chip.read_lux()
    check_true('saturated_raises', False)
except ValueError:
    check_true('saturated_raises', True)
chip.set_integration_time(101)
set_counts(mock, 37177, 10)
try:
    chip.read_lux()
    check_true('saturated_101ms_raises', False)
except ValueError:
    check_true('saturated_101ms_raises', True)
set_counts(mock, 37176, 10)
check_true('below_limit_ok', chip.read_lux() > 0)

# --- gain / integration time / package
mock, chip = fresh()
chip.set_gain(16)
check_true('set_gain_16', mock.registers[0x81] == 0x12 and chip.get_gain() == 16)
chip.set_integration_time(14)
check_true('set_integration_14', mock.registers[0x81] == 0x10 and chip.get_integration_time() == 14)
chip.set_gain(1)
chip.set_integration_time(101)
check_true('set_integration_101', mock.registers[0x81] == 0x01 and chip.get_integration_time() == 101)
for bad, fn in ((4, chip.set_gain), (50, chip.set_integration_time), ('X', chip.set_package)):
    try:
        fn(bad)
        check_true('rejects_%r' % (bad,), False)
    except ValueError:
        check_true('rejects_%r' % (bad,), True)
set_counts(mock, 1000, 100)
chip.set_integration_time(402)
chip.set_package('CS')
check_true('set_package_cs', near(chip.read_lux(), ref(1000, 100, 1, 1.0, CS)))

# --- manual timing
mock, chip = fresh()
chip.set_gain(16)
chip.start_manual()
check_true('start_manual', mock.registers[0x81] == 0x1B)
chip.stop_manual()
check_true('stop_manual', mock.registers[0x81] == 0x13 and chip.get_integration_time() == 0)

# --- auto_range
class RangeMock(I2CConnectionMock):
    """Counts follow the TIMING register: 'lux' fixed, counts scale with gain and time."""
    def __init__(self, base):
        I2CConnectionMock.__init__(self, registers={})
        self.base = base  # counts at 1x / 402 ms

    def write_read(self, data, n):
        if data[0] == 0x9C:
            t = self.registers.get(0x81, 0x02)
            gain = 16 if t & 0x10 else 1
            scale, limit = {0: (0.034, 5047), 1: (0.252, 37177), 2: (1.0, 65535)}[t & 0x03]
            ch0 = min(limit, int(self.base * gain * scale))
            ch1 = min(limit, int(self.base * 0.2 * gain * scale))
            self.set_register(0x9C, ch0 & 0xFF, ch0 >> 8, ch1 & 0xFF, ch1 >> 8)
        if data[0] == 0x80:
            return bytes([self.registers.get(0x80, 0)])
        return I2CConnectionMock.write_read(self, data, n)

dim = RangeMock(20)
chip = TSL2561Full(dim)
lux = chip.auto_range()
check_true('auto_range_dim_16x', chip.get_gain() == 16 and chip.get_integration_time() == 402 and lux > 0)
bright = RangeMock(148000)
chip = TSL2561Full(bright)
lux = chip.auto_range()
check_true('auto_range_bright_14ms', chip.get_gain() == 1 and chip.get_integration_time() == 14 and lux > 0)
mid = RangeMock(5000)
chip = TSL2561Full(mid)
lux = chip.auto_range()
check_true('auto_range_mid_keeps_1x_402', chip.get_gain() == 1 and chip.get_integration_time() == 402)
check_true('auto_range_lux_consistent', near(lux, calculate_lux(5000, 1000, 1, 402), 1e-3))

# --- power
mock, chip = fresh()
check_true('is_powered', chip.is_powered() is True)
chip.power_down()
check_true('power_down', mock.registers[0x80] == 0x00 and chip.is_powered() is False)
chip.power_up()
check_true('power_up', mock.registers[0x80] == 0x03)

# --- thresholds (word writes, little-endian) and interrupt register
mock, chip = fresh(500, 0)
chip.set_thresholds(0x0102, 0x0A0B)
check_true('set_thresholds_word', bytes([0xA2, 0x02, 0x01]) in [bytes(w) for w in mock.writes]
           and bytes([0xA4, 0x0B, 0x0A]) in [bytes(w) for w in mock.writes])
check_true('get_thresholds', chip.get_thresholds() == (0x0102, 0x0A0B))
try:
    chip.set_thresholds(-1, 70000)
    check_true('thresholds_range', False)
except ValueError:
    check_true('thresholds_range', True)
chip.configure_interrupt(1, 2)
check_true('configure_interrupt', mock.registers[0x86] == 0x12)
for args in ((3, 0), (1, 16)):
    try:
        chip.configure_interrupt(*args)
        check_true('configure_interrupt_rejects_%r' % (args,), False)
    except ValueError:
        check_true('configure_interrupt_rejects_%r' % (args,), True)
check_true('read_id', chip.read_id() == 0x15 and chip.read_id() >> 4 == 1)

# --- poll_interrupt
chip.set_thresholds(100, 1000)
check_true('poll_inside_window', chip.poll_interrupt() is False)
set_counts(mock, 100, 0)
check_true('poll_at_low', chip.poll_interrupt() is True)
set_counts(mock, 1001, 0)
check_true('poll_above_high', chip.poll_interrupt() is True)
chip.clear_interrupt()
check_true('clear_interrupt', bytes(mock.writes[-1]) == bytes([0xC0]))

# --- on_interrupt with a pin
class FakePin:
    def __init__(self):
        self.handler = None

    def on_edge(self, handler, edge):
        self.handler = handler

    def off_edge(self, handler):
        self.handler = None

pin = FakePin()
seen = []
chip.on_interrupt(seen.append, int_pin=pin)
set_counts(mock, 4242, 0)
pin.handler()
check_true('on_interrupt_callback', seen == [4242] and bytes(mock.writes[-1]) == bytes([0xC0]))
chip.off_interrupt()
check_true('off_interrupt', pin.handler is None)

print('===DONE: %d passed, %d failed===' % (passed, failed))
sys.exit(1 if failed else 0)
