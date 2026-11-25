"""Unit test for the DS3502 — runs without hardware using the I2C mock.

Verifies the initialization sequence (MODE = 1, power-up retry), wiper and
voltage conversions, clamping, ratio / resistance helpers, increment,
store_wiper (MODE = 0 around the EEPROM write, restored on error), MODE
handling and acknowledge polling by observing the mock's registers and writes.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ds3502 import DS3502Minimal, DS3502Full

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


def fresh(wr=0x40, **kw):
    m = I2CConnectionMock(registers={0x00: wr, 0x02: 0x00})
    return m, DS3502Full(m, **kw)


# --- init: CR <- 0x80 (MODE = 1), then WR is read; the wiper is not written.
mock, pot = fresh(0x23)
check_true('init_sets_mode_sram', mock.registers[0x02] == 0x80)
check_true('init_writes_only_cr', [w[0] for w in data_writes(mock)] == [0x02])
check_true('init_keeps_wiper', mock.registers[0x00] == 0x23)
check_true('init_caches_wr', pot.get_wiper() == 0x23)

# --- init retries once after a NACK (power-up recall).
mock = I2CConnectionMock(registers={0x00: 0x40, 0x02: 0x00})
mock.fail_next_writes(1)
DS3502Minimal(mock)
check_true('init_retries_after_nack', mock.registers[0x02] == 0x80 and len(data_writes(mock)) == 2)

# --- set_wiper / get_wiper
mock, pot = fresh()
pot.set_wiper(100)
check_true('set_wiper', mock.registers[0x00] == 100)
pot.set_wiper(300)
check_true('set_wiper_clamps_high', mock.registers[0x00] == 127)
pot.set_wiper(-5)
check_true('set_wiper_clamps_low', mock.registers[0x00] == 0)
mock.registers[0x00] = 0xFF  # bit 7 is unused and masked
check_true('get_wiper_masks_bit7', pot.get_wiper() == 127)
mock.registers[0x00] = 17
check_true('get_wiper', pot.get_wiper() == 17)

# --- voltage conversion (RH = 15 V, RL = 0 V)
mock, pot = fresh(v_high=15.0, v_low=0.0)
pot.set_voltage(6.0)
check_true('set_voltage_code', mock.registers[0x00] == 51)  # round(6/15*127) = 51
check_true('get_voltage', abs(pot.get_voltage() - 15.0 * 51 / 127) < 1e-9)
pot.set_voltage(99.0)
check_true('set_voltage_clamps_high', mock.registers[0x00] == 127)
pot.set_voltage(-3.0)
check_true('set_voltage_clamps_low', mock.registers[0x00] == 0)
mock.registers[0x00] = 127
check_true('get_voltage_full_scale', abs(pot.get_voltage() - 15.0) < 1e-9)

# Non-zero low end and inverted ends.
mock, pot = fresh(v_high=12.0, v_low=2.0)
pot.set_voltage(7.0)
check_true('set_voltage_offset_low', mock.registers[0x00] == 64)  # round(0.5*127) = 64
mock, pot = fresh(v_high=1.0, v_low=3.0)
pot.set_voltage(2.0)
check_true('set_voltage_inverted_ends', mock.registers[0x00] == 64)
mock, pot = fresh(v_high=5.0, v_low=5.0)
pot.set_voltage(5.0)
check_true('set_voltage_zero_span', mock.registers[0x00] == 0)

# --- ratio / resistance
mock, pot = fresh()
pot.set_ratio(0.5)
check_true('set_ratio', mock.registers[0x00] == 64)
pot.set_ratio(2.0)
check_true('set_ratio_clamps', mock.registers[0x00] == 127)
mock.registers[0x00] = 127
check_true('get_ratio', pot.get_ratio() == 1.0)
mock.registers[0x00] = 0
check_true('resistance_l_zero', pot.get_resistance('L') == 0.0 and pot.get_resistance('H') == 10000.0)
mock.registers[0x00] = 127
check_true('resistance_l_full', pot.get_resistance() == 10000.0 and pot.get_resistance('H') == 0.0)
try:
    pot.get_resistance('X')
    check_true('resistance_bad_terminal', False)
except ValueError:
    check_true('resistance_bad_terminal', True)

# --- increment
mock, pot = fresh(0x40)
check_true('increment_returns_code', pot.increment(5) == 69 and mock.registers[0x00] == 69)
check_true('increment_negative', pot.increment(-10) == 59)
mock.registers[0x00] = 125
check_true('increment_clamps_high', pot.increment(10) == 127)
mock.registers[0x00] = 3
check_true('increment_clamps_low', pot.increment(-10) == 0)

# --- mode
mock, pot = fresh()
check_true('get_mode_sram', pot.get_mode() is False)
pot.set_mode(True)
check_true('set_mode_eeprom', mock.registers[0x02] == 0x00 and pot.get_mode() is True)
pot.set_mode(False)
check_true('set_mode_sram', mock.registers[0x02] == 0x80)

# --- store_wiper: CR <- 0x00, WR <- code, (poll), CR <- 0x80
mock, pot = fresh(0x40)
n0 = len(data_writes(mock))
pot.store_wiper(77)
seq = [(w[0], w[1]) for w in data_writes(mock)[n0:]]
check_true('store_wiper_sequence', seq == [(0x02, 0x00), (0x00, 77), (0x02, 0x80)])
check_true('store_wiper_restores_mode', mock.registers[0x02] == 0x80 and pot.get_mode() is False)
check_true('store_wiper_moves_wiper', pot.get_wiper() == 77)
mock.registers[0x00] = 33
pot.store_wiper()
check_true('store_wiper_default_current', mock.registers[0x00] == 33)

# --- store_wiper restores MODE = 1 even when the EEPROM write never finishes.
mock, pot = fresh(0x40)
mock.fail_next_writes(31, after=2)  # CR <- 0, WR <- code succeed; all 31 polls NACK
try:
    pot.store_wiper(10)
    check_true('store_wiper_timeout_raises', False)
except TimeoutError:
    check_true('store_wiper_timeout_raises', True)
check_true('store_wiper_restores_mode_on_error', mock.registers[0x02] == 0x80)

# --- wait_ready: acknowledges after a few NACKs, times out otherwise.
mock, pot = fresh()
mock.fail_next_writes(3)  # a read of CR starts with the pointer write, which NACKs
pot.wait_ready()
check_true('wait_ready_polls_until_ack', True)
mock.fail_next_writes(1000)
try:
    pot.wait_ready(5)
    check_true('wait_ready_times_out', False)
except TimeoutError:
    check_true('wait_ready_times_out', True)

# --- set_endpoints: no I2C traffic, changes the conversions.
mock, pot = fresh(v_high=1.0, v_low=0.0)
n = len(mock.writes)
pot.set_endpoints(10.0, 0.0)
check_true('set_endpoints_no_traffic', len(mock.writes) == n)
mock.registers[0x00] = 127
check_true('set_endpoints_used', abs(pot.get_voltage() - 10.0) < 1e-9)

# --- Minimal class has no Full-only methods.
minimal = DS3502Minimal(I2CConnectionMock(registers={0x00: 0x40}))
check_true('minimal_has_no_store_wiper', not hasattr(minimal, 'store_wiper'))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
