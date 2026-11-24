"""Unit test for the APDS-9250 — runs without hardware using the I2C mock.

Verifies the identity check and initialization sequence, the 3-byte channel
decoding, configuration (mode, resolution, rate, gain), thresholds, persistence,
variance, interrupt configuration and the status-flag latching.
"""

import sys

from periph.connection.i2c_mock import I2CConnectionMock
import periph.chips.light.apds9250 as m
from periph.chips.light.apds9250 import (
    APDS9250Minimal, APDS9250Full, SOURCE_THRESHOLD, SOURCE_VARIATION,
    STATUS_INTERRUPT, STATUS_POWER_ON, STATUS_DATA_READY)

m._START_WAIT_S = 0.0
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


def fresh(part=0xB5):
    mock = I2CConnectionMock(registers={0x06: part, 0x07: 0x08, 0x00: 0x00, 0x04: 0x22,
                                        0x05: 0x01, 0x19: 0x10})
    return mock, APDS9250Full(mock)


# --- init
mock, chip = fresh()
check_true('init_sequence', data_writes(mock) == [
    (0x04, b'\x22'), (0x05, b'\x01'), (0x19, b'\x10'), (0x00, b'\x06')])

try:
    fresh(0x00)
    check_true('init_rejects_wrong_part', False)
except ValueError:
    check_true('init_rejects_wrong_part', True)
try:
    fresh(0xC5)
    check_true('init_rejects_wrong_nibble', False)
except ValueError:
    check_true('init_rejects_wrong_nibble', True)
mock, _ = fresh(0xB0)
check_true('init_accepts_other_revision', True)

# --- read: counts are 3 bytes LSB first, IR/green/blue/red order, 20 bit
mock, chip = fresh()
mock.set_register(0x0A, 0x01, 0x02, 0x03, 0x10, 0x00, 0x00, 0xFF, 0xFF, 0x0F, 0x00, 0x00, 0xF4)
# IR = 0x030201, green = 16, blue = 0xFFFFF, red: upper nibble of byte 2 ignored -> 0x40000
counts = chip.read()
check_true('read_ir', counts['ir'] == 0x030201)
check_true('read_green', counts['green'] == 16)
check_true('read_blue', counts['blue'] == 0xFFFFF)
check_true('read_red_masks_nibble', counts['red'] == 0x40000)
check_true('read_channels_order', chip.read_channels() == (0x40000, 16, 0xFFFFF, 0x030201))
check_true('read_red', chip.read_red() == 0x40000)
check_true('read_green_single', chip.read_green() == 16)
check_true('read_blue_single', chip.read_blue() == 0xFFFFF)
check_true('read_ir_single', chip.read_ir() == 0x030201)

# --- configuration read-modify-write
mock, chip = fresh()
mock.writes[:] = []
chip.set_resolution(20)
check_true('resolution_20', data_writes(mock)[-1] == (0x04, b'\x02') and chip.get_resolution() == 20)
chip.set_resolution(13)
check_true('resolution_13', mock.registers[0x04] == 0x52 and chip.get_resolution() == 13)
check_true('integration_13', abs(chip.integration_time() - 0.003125) < 1e-12)
chip.set_resolution(16)
check_true('resolution_keeps_rate', mock.registers[0x04] == 0x42)
chip.set_measurement_rate(2000)
check_true('rate_2000', mock.registers[0x04] == 0x46 and chip.get_measurement_rate() == 2000)
chip.set_measurement_rate(25)
check_true('rate_25_keeps_resolution', mock.registers[0x04] == 0x40 and chip.get_measurement_rate() == 25)
for gain, code in ((1, 0), (3, 1), (6, 2), (9, 3), (18, 4)):
    chip.set_gain(gain)
    check_true('gain_%d' % gain, mock.registers[0x05] == code and chip.get_gain() == gain)
for call in (lambda: chip.set_gain(2), lambda: chip.set_resolution(12),
             lambda: chip.set_measurement_rate(30), lambda: chip.set_persistence(0),
             lambda: chip.set_persistence(17), lambda: chip.set_variance(12),
             lambda: chip.set_interrupt_channel('x'), lambda: chip.set_thresholds(5, 4),
             lambda: chip.set_thresholds(0, 0x100000)):
    try:
        call()
        check_true('invalid_argument', False)
    except ValueError:
        check_true('invalid_argument', True)

chip.set_resolution(18)
chip.set_gain(3)
check_true('normalized', abs(chip.normalized(3000) - 10000.0) < 1e-6)

# --- mode, enable, disable, reset
mock, chip = fresh()
mock.registers[0x00] = 0x06
chip.set_mode(False)
check_true('mode_als', mock.registers[0x00] == 0x02)
chip.set_mode(True)
check_true('mode_cs', mock.registers[0x00] == 0x06)
chip.disable()
check_true('disable', mock.registers[0x00] == 0x04)
chip.enable()
check_true('enable', mock.registers[0x00] == 0x06)
mock.writes[:] = []
chip.soft_reset()
check_true('soft_reset', data_writes(mock) == [(0x00, b'\x10')])
check_true('part_id', chip.part_id() == (0xB, 0x5))

# --- thresholds
mock, chip = fresh()
chip.set_thresholds(0x12345, 0xABCDE)
check_true('threshold_low_bytes', [mock.registers[0x24], mock.registers[0x25], mock.registers[0x26]] == [0x45, 0x23, 0x01])
check_true('threshold_high_bytes', [mock.registers[0x21], mock.registers[0x22], mock.registers[0x23]] == [0xDE, 0xBC, 0x0A])
check_true('thresholds_roundtrip', chip.get_thresholds() == (0x12345, 0xABCDE))

# --- interrupt configuration
mock, chip = fresh()
chip.set_interrupt_channel('red')
check_true('channel_red', mock.registers[0x19] == 0x20)
chip.set_interrupt_channel('ir')
check_true('channel_ir', mock.registers[0x19] == 0x00)
chip.set_interrupt_channel('blue')
check_true('channel_blue', mock.registers[0x19] == 0x30)
chip.enable_interrupt(SOURCE_VARIATION)
check_true('enable_variation', mock.registers[0x19] == 0x3C)
chip.enable_interrupt(SOURCE_THRESHOLD)
check_true('enable_threshold', mock.registers[0x19] == 0x34)
chip.disable_interrupt()
check_true('disable_interrupt', mock.registers[0x19] == 0x30)
chip.set_persistence(1)
check_true('persistence_1', mock.registers[0x1A] == 0x00)
chip.set_persistence(16)
check_true('persistence_16', mock.registers[0x1A] == 0xF0)
chip.set_variance(8)
check_true('variance_8', mock.registers[0x27] == 0)
chip.set_variance(1024)
check_true('variance_1024', mock.registers[0x27] == 7)

# --- status latching: data_ready() must not lose the interrupt bit
mock, chip = fresh()
mock.registers[0x07] = STATUS_DATA_READY | STATUS_INTERRUPT
check_true('data_ready_true', chip.data_ready())
mock.registers[0x07] = 0x00
check_true('data_ready_false', not chip.data_ready())
status = chip.poll_interrupt()
check_true('interrupt_kept_after_data_ready', status == STATUS_INTERRUPT)
check_true('poll_clears_latch', chip.poll_interrupt() == 0)
mock.registers[0x07] = STATUS_POWER_ON
check_true('status_power_on', chip.status() == STATUS_POWER_ON)

# --- interrupt callback
mock, chip = fresh()
seen = []
chip.on_interrupt(lambda s: seen.append(s), int_pin=None)
chip.off_interrupt()
chip._callback = lambda s: seen.append(s)
mock.registers[0x07] = STATUS_INTERRUPT
chip._int_handler()
mock.registers[0x07] = 0x00
chip._int_handler()
chip._callback = None
check_true('callback_receives_status', seen == [STATUS_INTERRUPT])

# --- minimal has no Full-only methods
minimal = APDS9250Minimal(I2CConnectionMock(registers={0x06: 0xB5, 0x07: 0x08}))
check_true('minimal_has_no_gain', not hasattr(minimal, 'set_gain'))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
