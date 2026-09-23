"""Unit test for the DRV8830 — runs without hardware using the I2C mock.

Verifies voltage-to-VSET conversion, direction bit encoding, the coast
floor and clamp, raw CONTROL writes, output read-back decoding, and fault
reporting/clearing by observing what the driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.motor.drv8830 import DRV8830Minimal, DRV8830Full

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


def last_write(mock, reg):
    return [w[1] for w in mock.writes if len(w) == 2 and w[0] == reg][-1]


REG_CONTROL = DRV8830Minimal._REG_CONTROL
REG_FAULT = DRV8830Minimal._REG_FAULT

# Construction makes no writes (presence check is a read only).
mock = I2CConnectionMock()
motor = DRV8830Minimal(mock)
check_true('init_no_register_writes', all(len(w) == 1 for w in mock.writes))

# drive(3.0): VSET = round(3.0 * 16 / 1.285) = 37 (0x25), forward (IN1).
motor.drive(3.0)
check_true('drive_forward_3v', last_write(mock, REG_CONTROL) == (37 << 2) | 0x01)

# drive(-2.0): VSET = round(2.0 * 16 / 1.285) = 25 (0x19), reverse (IN2).
motor.drive(-2.0)
check_true('drive_reverse_2v', last_write(mock, REG_CONTROL) == (25 << 2) | 0x02)

# drive(0.0) and sub-floor magnitudes coast with CONTROL = 0x00.
motor.drive(0.0)
check_true('drive_zero_coasts', last_write(mock, REG_CONTROL) == 0x00)
motor.drive(0.4)
check_true('drive_below_floor_coasts', last_write(mock, REG_CONTROL) == 0x00)

# drive(0.48): VSET 6 — the minimum valid code.
motor.drive(0.48)
check_true('drive_floor_vset6', last_write(mock, REG_CONTROL) == (6 << 2) | 0x01)

# drive(9.0): clamped to VSET 63.
motor.drive(9.0)
check_true('drive_clamps_to_vset63', last_write(mock, REG_CONTROL) == (63 << 2) | 0x01)

motor.brake()
check_true('brake_writes_0x03', last_write(mock, REG_CONTROL) == 0x03)
motor.stop()
check_true('stop_writes_0x00', last_write(mock, REG_CONTROL) == 0x00)

# Minimal does not expose the Full API.
check_true('minimal_has_no_read_fault', not hasattr(motor, 'read_fault'))

# set_output: raw fields; reserved codes rejected.
mock2 = I2CConnectionMock()
full = DRV8830Full(mock2)
full.set_output(20, False, True)
check_true('set_output_raw', last_write(mock2, REG_CONTROL) == (20 << 2) | 0x02)
try:
    full.set_output(5, True, False)
    check_true('set_output_rejects_reserved', False)
except ValueError:
    check_true('set_output_rejects_reserved', True)

# read_output decodes VSET and direction.
mock3 = I2CConnectionMock()
mock3.set_register(REG_CONTROL, (63 << 2) | 0x01)
full3 = DRV8830Full(mock3)
v, d = full3.read_output()
check_true('read_output_forward', d == 'forward' and abs(v - 5.06) < 0.01)
mock3.set_register(REG_CONTROL, (16 << 2) | 0x02)
v, d = full3.read_output()
check_true('read_output_reverse', d == 'reverse' and abs(v - 1.285) < 0.001)
mock3.set_register(REG_CONTROL, 0x03)
check_true('read_output_brake', full3.read_output() == (0.0, 'brake'))
mock3.set_register(REG_CONTROL, 0x00)
check_true('read_output_coast', full3.read_output() == (0.0, 'coast'))

# read_fault decodes each bit; does not write (never clears implicitly).
mock4 = I2CConnectionMock()
mock4.set_register(REG_FAULT, 0x01 | 0x10)  # FAULT + ILIMIT
full4 = DRV8830Full(mock4)
check_true('read_fault_ilimit', full4.read_fault() == (True, False, False, False, True))
mock4.set_register(REG_FAULT, 0x01 | 0x02 | 0x04 | 0x08)
check_true('poll_interrupt_ocp_uvlo_ots', full4.poll_interrupt() == (True, True, True, True, False))
check_true('read_fault_does_not_clear', not any(len(w) == 2 and w[0] == REG_FAULT for w in mock4.writes))

full4.clear_fault()
check_true('clear_fault_writes_0x80', last_write(mock4, REG_FAULT) == 0x80)

print('===DONE: %d passed, %d failed===' % (passed, failed))
if failed:
    raise SystemExit(1)
