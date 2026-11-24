"""Unit test for the ISL29125 — runs without hardware using the I2C mock.

Verifies the identity check and initialization sequence, the 16-bit channel
decoding, configuration (mode, range, resolution, sync, IR compensation),
thresholds, persistence, interrupt configuration and the status-flag latching.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.color.isl29125 as m
from periph.chips.color.isl29125 import (
    ISL29125Minimal, ISL29125Full, SOURCE_THRESHOLD, SOURCE_CONVERSION,
    STATUS_BROWNOUT, STATUS_CONVERSION, STATUS_THRESHOLD)

m._POLL_INTERVAL_S = 0.0

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
    return [(w[0], w[1:]) for w in mock.writes if len(w) > 1]


def fresh(ident=0x7D):
    mock = I2CConnectionMock(registers={0x00: ident, 0x01: 0x00, 0x02: 0x00, 0x03: 0x00,
                                        0x08: STATUS_CONVERSION})
    chip = ISL29125Full(mock)
    mock.registers[0x08] = STATUS_CONVERSION
    return mock, chip


# --- init
mock, chip = fresh()
check_true('init_sequence', data_writes(mock) == [
    (0x08, b'\x00'), (0x03, b'\x00'), (0x02, b'\xBF'), (0x01, b'\x0D')])
try:
    fresh(0x00)
    check_true('init_rejects_wrong_id', False)
except ValueError:
    check_true('init_rejects_wrong_id', True)
check_true('device_id', chip.device_id() == 0x7D)

# --- read: counts are 16 bit LSB first, green/red/blue order
mock, chip = fresh()
mock.set_register(0x09, 0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00)
counts = chip.read()
check_true('read_green', counts['green'] == 0x1234)
check_true('read_red', counts['red'] == 0xFFFF)
check_true('read_blue', counts['blue'] == 1)
check_true('read_rgb_order', chip.read_rgb() == (0xFFFF, 0x1234, 1))
check_true('read_red_single', chip.read_red() == 0xFFFF)
check_true('read_green_single', chip.read_green() == 0x1234)
check_true('read_blue_single', chip.read_blue() == 1)
minimal = ISL29125Minimal(I2CConnectionMock(registers={0x00: 0x7D}))
minimal._connection.registers[0x08] = STATUS_CONVERSION
check_true('minimal_read_keys', sorted(minimal.read().keys()) == ['blue', 'green', 'red'])
check_true('minimal_has_no_range', not hasattr(minimal, 'set_range'))

# --- configuration read-modify-write
mock, chip = fresh()
mock.registers[0x01] = 0x0D
for mode, code in (('power_down', 0), ('green', 1), ('red', 2), ('blue', 3), ('standby', 4),
                   ('rgb', 5), ('green_red', 6), ('green_blue', 7)):
    chip.set_mode(mode)
    check_true('mode_' + mode, mock.registers[0x01] == (0x08 | code))
chip.set_range(375)
check_true('range_375', mock.registers[0x01] == 0x07)
chip.set_range(10000)
check_true('range_10000', mock.registers[0x01] == 0x0F)
chip.set_resolution(12)
check_true('resolution_12', mock.registers[0x01] == 0x1F)
chip.set_resolution(16)
check_true('resolution_16', mock.registers[0x01] == 0x0F)
chip.set_sync(True)
check_true('sync_external', mock.registers[0x01] == 0x2F)
chip.set_sync(False)
check_true('sync_i2c', mock.registers[0x01] == 0x0F)
chip.set_ir_compensation(False, 0x3F)
check_true('ir_comp_low', mock.registers[0x02] == 0x3F)
chip.set_ir_compensation(True, 0x3F)
check_true('ir_comp_high', mock.registers[0x02] == 0xBF)
for call in (lambda: chip.set_mode('x'), lambda: chip.set_range(100),
             lambda: chip.set_resolution(8), lambda: chip.set_ir_compensation(False, 64),
             lambda: chip.set_persistence(3), lambda: chip.set_interrupt_channel('ir'),
             lambda: chip.set_thresholds(5, 4), lambda: chip.set_thresholds(0, 0x10000)):
    try:
        call()
        check_true('invalid_argument', False)
    except ValueError:
        check_true('invalid_argument', True)

# --- soft reset
mock, chip = fresh()
mock.writes[:] = []
chip.soft_reset()
check_true('soft_reset', data_writes(mock) == [(0x00, b'\x46')])

# --- thresholds
mock, chip = fresh()
chip.set_thresholds(0x1234, 0xABCD)
check_true('threshold_low_bytes', [mock.registers[0x04], mock.registers[0x05]] == [0x34, 0x12])
check_true('threshold_high_bytes', [mock.registers[0x06], mock.registers[0x07]] == [0xCD, 0xAB])
check_true('thresholds_roundtrip', chip.get_thresholds() == (0x1234, 0xABCD))

# --- interrupt configuration
mock, chip = fresh()
chip.set_interrupt_channel('red')
check_true('channel_red', mock.registers[0x03] == 0x02)
chip.set_interrupt_channel('blue')
check_true('channel_blue', mock.registers[0x03] == 0x03)
chip.set_interrupt_channel('none')
check_true('channel_none', mock.registers[0x03] == 0x00)
chip.set_persistence(8)
check_true('persistence_8', mock.registers[0x03] == 0x0C)
chip.set_persistence(1)
check_true('persistence_1', mock.registers[0x03] == 0x00)
chip.set_persistence(4)
chip.set_interrupt_channel('green')
check_true('persistence_keeps_channel', mock.registers[0x03] == 0x09)
chip.enable_interrupt(SOURCE_CONVERSION)
check_true('enable_conversion', mock.registers[0x03] == 0x19)
chip.disable_interrupt(SOURCE_CONVERSION)
check_true('disable_conversion', mock.registers[0x03] == 0x09)
chip.disable_interrupt(SOURCE_THRESHOLD)
check_true('disable_threshold', mock.registers[0x03] == 0x08)
chip.enable_interrupt(SOURCE_THRESHOLD)
check_true('enable_threshold_defaults_green', mock.registers[0x03] == 0x09)
chip.set_interrupt_channel('red')
chip.enable_interrupt(SOURCE_THRESHOLD)
check_true('enable_threshold_keeps_channel', mock.registers[0x03] == 0x0A)

# --- status latching: data_ready() must not lose the threshold bit
mock, chip = fresh()
mock.registers[0x08] = STATUS_CONVERSION | STATUS_THRESHOLD
check_true('data_ready_true', chip.data_ready())
mock.registers[0x08] = 0x00
check_true('data_ready_false', not chip.data_ready())
status = chip.poll_interrupt()
check_true('flags_kept_after_data_ready', status == (STATUS_CONVERSION | STATUS_THRESHOLD))
check_true('poll_clears_latch', chip.poll_interrupt() == 0)
mock.registers[0x08] = STATUS_BROWNOUT
check_true('status_brownout', chip.status() == STATUS_BROWNOUT)

# --- interrupt callback
mock, chip = fresh()
seen = []
mock.registers[0x08] = 0x00
chip.on_interrupt(lambda s: seen.append(s), int_pin=None)
chip.off_interrupt()
chip._callback = lambda s: seen.append(s)
mock.registers[0x08] = STATUS_THRESHOLD
chip._int_handler()
mock.registers[0x08] = 0x00
chip._int_handler()
chip._callback = None
check_true('callback_receives_status', seen == [STATUS_THRESHOLD])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
