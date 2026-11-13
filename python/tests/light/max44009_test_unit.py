"""Unit test for the MAX44009 — runs without hardware using the I2C mock.

Verifies the initialization sequence, lux conversion (exponent / mantissa,
overrange), the consistent lux read, configuration (mode, range, integration
time), threshold encoding / decoding, the threshold delay and interrupt
handling by observing the mock's registers and writes.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.light.max44009 as m
from periph.chips.light.max44009 import MAX44009Minimal, MAX44009Full

m._INIT_WAIT_S = 0.0  # skip the 900 ms first-conversion wait

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


def data_writes(mock):
    return [w for w in mock.writes if len(w) > 1]


def fresh(high=0x00, low=0x00):
    mock = I2CConnectionMock(registers={0x03: high, 0x04: low, 0x02: 0x03})
    return mock, MAX44009Full(mock)


def near(a, b, tol=1e-9):
    return abs(a - b) < tol


# --- init: reset state written, INT_STATUS read, no lux register written.
mock, chip = fresh()
seq = [(w[0], w[1]) for w in data_writes(mock)]
check_true('init_sequence', seq == [(0x01, 0x00), (0x02, 0x03), (0x05, 0xFF), (0x06, 0x00), (0x07, 0xFF)])
check_true('init_reads_int_status', any(len(w) == 1 and w[0] == 0x00 for w in mock.writes))

# --- lux conversion
mock, chip = fresh(0x00, 0x00)
check_true('lux_zero', chip.lux() == 0.0)
mock.registers[0x03] = 0x2A  # E = 2, M[7:4] = 0xA
mock.registers[0x04] = 0x05  # M[3:0] = 5
check_true('lux_exponent_mantissa', near(chip.lux(), 4 * 0xA5 * 0.045))
mock.registers[0x03] = 0xEF
mock.registers[0x04] = 0x0F
check_true('lux_max_valid', near(chip.lux(), 188006.4))
mock.registers[0x03] = 0xF3
check_true('lux_overrange_returns_max', near(chip.lux(), 188006.4))
mock.registers[0x03] = 0x01
mock.registers[0x04] = 0xF7   # bits 7:4 of LUX_LOW are unused
check_true('lux_low_nibble_only', near(chip.lux(), 0x17 * 0.045))

# --- raw / overrange / resolution
mock, chip = fresh(0x5B, 0x0C)
check_true('read_raw', chip.read_raw() == (5, 0xBC, False))
check_true('resolution', near(chip.resolution(), 32 * 0.045))
check_true('is_overrange_false', chip.is_overrange() is False)
mock.registers[0x03] = 0xF0
check_true('read_raw_overrange', chip.read_raw()[2] is True and chip.is_overrange() is True)

# --- the consistent read repeats once when the high byte changes (queue reads)
class FlipMock(I2CConnectionMock):
    def __init__(self):
        I2CConnectionMock.__init__(self, registers={0x03: 0x10, 0x04: 0x01, 0x02: 0x03})
        self.high_reads = 0

    def write_read(self, data, n):
        if data[0] == 0x03:
            self.high_reads += 1
            if self.high_reads == 2:
                self.registers[0x03] = 0x20
                self.registers[0x04] = 0x02
        return I2CConnectionMock.write_read(self, data, n)

flip = FlipMock()
chip2 = MAX44009Minimal(flip)
flip.high_reads = 0
value = chip2.lux()
check_true('lux_repeats_on_high_byte_change', near(value, 4 * 0x02 * 0.045) and flip.high_reads == 2)

# --- configuration
mock, chip = fresh()
chip.set_continuous(True)
check_true('set_continuous', mock.registers[0x02] == 0x83)
chip.set_continuous(False)
check_true('set_continuous_off', mock.registers[0x02] == 0x03)
chip.set_manual(50, False)
check_true('set_manual_50ms', mock.registers[0x02] == 0x44)
chip.set_manual(12, True)
check_true('set_manual_nearest_12_5ms_divide', mock.registers[0x02] == (0x40 | 0x08 | 0x06))
chip.set_continuous(True)
chip.set_manual(1000, False)
check_true('set_manual_keeps_continuous', mock.registers[0x02] == 0xC0)
chip.set_automatic()
check_true('set_automatic', mock.registers[0x02] & 0x40 == 0 and mock.registers[0x02] & 0x80)
mock.registers[0x02] = 0xCD
cfg = chip.get_config()
check_true('get_config', cfg == {'continuous': True, 'manual': True, 'divide_by_8': True, 'integration_ms': 25.0})

# --- thresholds
mock, chip = fresh()
chip.set_thresholds(0.0, 188006.4)
check_true('threshold_extremes', mock.registers[0x06] == 0x00 and mock.registers[0x05] == 0xEF)
chip.set_thresholds(5.0, 100.0)
low_code, high_code = mock.registers[0x06], mock.registers[0x05]
check_true('threshold_low_exponent0', low_code == 0x07)  # round(5 / 0.72) = 7
check_true('threshold_high_format', (high_code >> 4) == 4 and (high_code & 0x08))
low_lux, high_lux = chip.get_thresholds()
check_true('threshold_roundtrip', abs(low_lux - 5.0) < 0.5 and abs(high_lux - 100.0) / 100.0 < 0.13)
check_true('threshold_upper_implied_low_nibble', near(m._decode_threshold(0x25, True), 4 * 0x5F * 0.045))
check_true('threshold_lower_implied_zero', near(m._decode_threshold(0x25, False), 4 * 0x50 * 0.045))
try:
    chip.set_thresholds(10.0, 5.0)
    check_true('threshold_order_checked', False)
except ValueError:
    check_true('threshold_order_checked', True)

# --- threshold delay
mock, chip = fresh()
chip.set_threshold_delay(2.0)
check_true('delay_encoding', mock.registers[0x07] == 20 and near(chip.get_threshold_delay(), 2.0))
chip.set_threshold_delay(99)
check_true('delay_clamps_high', mock.registers[0x07] == 255)
chip.set_threshold_delay(-1)
check_true('delay_clamps_low', mock.registers[0x07] == 0)

# --- interrupt enable / status
mock, chip = fresh()
chip.enable_interrupt()
check_true('enable_interrupt', mock.registers[0x01] == 0x01)
chip.disable_interrupt()
check_true('disable_interrupt', mock.registers[0x01] == 0x00)
mock.registers[0x00] = 0x01
check_true('poll_interrupt', chip.poll_interrupt() == 1)
mock.registers[0x00] = 0x00
check_true('poll_interrupt_clear', chip.poll_interrupt() == 0)

seen = []
mock.registers[0x00] = 0x01
chip._callback = lambda s: seen.append(s)
chip._int_handler()
mock.registers[0x00] = 0x00   # reading INT_STATUS clears it on the chip
chip._int_handler()           # no event: no second callback
chip.off_interrupt()
check_true('callback_receives_status', seen == [1] and chip._callback is None)

# --- minimal class has no Full-only methods
minimal = MAX44009Minimal(I2CConnectionMock(registers={0x03: 0, 0x04: 0}))
check_true('minimal_has_no_thresholds', not hasattr(minimal, 'set_thresholds'))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
