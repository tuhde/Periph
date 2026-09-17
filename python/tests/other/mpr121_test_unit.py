"""Unit test for the MPR121 driver.

Uses the I2CConnectionMock to exercise register-level logic without
hardware. The chip has no WHO_AM_I register, so chip presence is
confirmed by writing a known value to a threshold register and
reading it back (per datasheet). The mock's write() applies writes
to the registers dict automatically, so a threshold write + readback
round-trips for the smoke check.
"""

import sys

sys.path.insert(0, 'python')

from periph.chips.other.mpr121 import Mpr121Minimal, Mpr121Full
from periph.connection.i2c_mock import I2CConnectionMock

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


def test_minimal_construction():
    """Minimal class runs init without error."""
    global passed, failed
    mock = I2CConnectionMock()
    Mpr121Minimal(mock)
    wrote_srst = any(w[0] == 0x80 and w[1] == 0x63 for w in mock.writes)
    check_true('Minimal init issues soft reset', wrote_srst)
    wrote_ecr = any(w[0] == 0x5E and w[1] == 0x8C for w in mock.writes)
    check_true('Minimal init writes ECR=0x8C', wrote_ecr)
    wrote_t0 = any(w[0] == 0x41 and w[1] == 12 for w in mock.writes)
    check_true('Minimal init writes ELE0_TTH=12', wrote_t0)
    wrote_r0 = any(w[0] == 0x42 and w[1] == 6 for w in mock.writes)
    check_true('Minimal init writes ELE0_RTH=6', wrote_r0)


def test_touched_decodes_bitmask():
    """touched() reads registers 0x00-0x01 as a coherent 12-bit bitmask."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x00] = 0x5A
    mock.registers[0x01] = 0x05
    mock.writes.clear()
    mpr = Mpr121Minimal(mock)
    mock.writes.clear()
    t = mpr.touched()
    check_true('touched writes reg=0x00 for 2-byte read',
               any(w[0] == 0x00 and len(w) == 1 for w in mock.writes))
    check_true('touched returns 0x55A',
               t == 0x5A | ((0x05 & 0x0F) << 8))


def test_is_touched_per_electrode():
    """is_touched(n) returns True for set bit n of touched bitmask."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x00] = (1 << 5) | (1 << 3)
    mock.registers[0x01] = (1 << 3)
    mpr = Mpr121Minimal(mock)
    check_true('is_touched(5) True', mpr.is_touched(5) is True)
    check_true('is_touched(3) True (byte 0)', mpr.is_touched(3) is True)
    check_true('is_touched(11) True (byte 1 bit 3)', mpr.is_touched(11) is True)
    check_true('is_touched(0) False', mpr.is_touched(0) is False)


def test_is_touched_validates_electrode():
    """is_touched raises ValueError for out-of-range electrode."""
    global passed, failed
    mock = I2CConnectionMock()
    mpr = Mpr121Minimal(mock)
    try:
        mpr.is_touched(12)
    except ValueError:
        check_true('is_touched(12) raises', True)
        return
    check_true('is_touched(12) raises', False)


def test_filtered_decodes_10bit():
    """filtered(0) reads 0x04-0x05 as little-endian 10-bit value."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x04] = 0x80
    mock.registers[0x05] = 0x02
    mpr = Mpr121Full(mock)
    check_true('filtered(0) = 0x280', mpr.filtered(0) == 0x280)


def test_baseline_shifts_left_2():
    """baseline(0) reads 0x1E and shifts left by 2."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x1E] = 0x80
    mpr = Mpr121Full(mock)
    check_true('baseline(0) = 0x200', mpr.baseline(0) == 0x200)


def test_set_baseline_shifts_right_2():
    """set_baseline(0, 0x300) writes 0xC0 to 0x1E."""
    global passed, failed
    mock = I2CConnectionMock()
    mpr = Mpr121Full(mock)
    mock.writes.clear()
    mpr.set_baseline(0, 0x300)
    check_true('set_baseline(0, 0x300) writes 0xC0 to 0x1E',
               any(w[0] == 0x1E and w[1] == 0xC0 for w in mock.writes))


def test_proximity_touched_bit4():
    """proximity_touched() reads bit 4 of register 0x01."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x01] = 0x10
    mpr = Mpr121Full(mock)
    check_true('proximity_touched True at 0x01=0x10', mpr.proximity_touched() is True)
    mock.registers[0x01] = 0x00
    check_true('proximity_touched False at 0x01=0x00', mpr.proximity_touched() is False)


def test_clear_overcurrent_clears_bit7():
    """clear_overcurrent writes register 0x01 with bit 7 cleared."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x01] = 0x80
    mpr = Mpr121Full(mock)
    mock.writes.clear()
    mpr.clear_overcurrent()
    check_true('clear_overcurrent writes 0x01 with bit 7 cleared',
               any(len(w) == 2 and w[0] == 0x01 and (w[1] & 0x80) == 0
                   for w in mock.writes))


def test_configure_sampling_packs_bits():
    """configure_sampling packs ffi into CDC_CONFIG[7:6] and cdt/sfi/esi into CDT_CONFIG."""
    global passed, failed
    mock = I2CConnectionMock()
    mpr = Mpr121Full(mock)
    mock.writes.clear()
    mpr.configure_sampling(cdc=10, cdt=2, ffi=1, sfi=2, esi=5)
    wrote_cdc = any(len(w) == 2 and w[0] == 0x5C and w[1] == 0x4A for w in mock.writes)
    check_true('configure_sampling writes CDC_CONFIG=0x4A (ffi=1, cdc=10)', wrote_cdc)
    wrote_cdt = any(len(w) == 2 and w[0] == 0x5D and w[1] == 0x4D for w in mock.writes)
    check_true('configure_sampling writes CDT_CONFIG=0x4D (cdt=2, sfi=2, esi=5)', wrote_cdt)


def test_configure_debounce_packs_bits():
    """configure_debounce packs release/touch into the DEBOUNCE register."""
    global passed, failed
    mock = I2CConnectionMock()
    mpr = Mpr121Full(mock)
    mock.writes.clear()
    mpr.configure_debounce(touch=3, release=5)
    check_true('configure_debounce writes 0x5B=0x53 (release=5,touch=3)',
               any(w[0] == 0x5B and w[1] == 0x53 for w in mock.writes))


def test_poll_interrupt_returns_13bit():
    """poll_interrupt returns a 13-bit bitmask."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x00] = 0xFF
    mock.registers[0x01] = 0x1F
    mpr = Mpr121Full(mock)
    check_true('poll_interrupt returns 0x1FFF', mpr.poll_interrupt() == 0x1FFF)


def test_enable_disable_interrupt():
    """enable_interrupt / disable_interrupt update AUTOCONFIG1."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[0x7C] = 0x00
    mpr = Mpr121Full(mock)
    mock.writes.clear()
    mpr.enable_interrupt(Mpr121Full.SOURCE_OOR)
    check_true('enable_interrupt(SOURCE_OOR) writes 0x7C=0x04',
               any(len(w) == 2 and w[0] == 0x7C and w[1] == 0x04 for w in mock.writes))
    mock.registers[0x7C] = 0x04
    mock.writes.clear()
    mpr.disable_interrupt(Mpr121Full.SOURCE_OOR)
    check_true('disable_interrupt(SOURCE_OOR) writes 0x7C=0x00',
               any(len(w) == 2 and w[0] == 0x7C and w[1] == 0x00 for w in mock.writes))


test_minimal_construction()
test_touched_decodes_bitmask()
test_is_touched_per_electrode()
test_is_touched_validates_electrode()
test_filtered_decodes_10bit()
test_baseline_shifts_left_2()
test_set_baseline_shifts_right_2()
test_proximity_touched_bit4()
test_clear_overcurrent_clears_bit7()
test_configure_sampling_packs_bits()
test_configure_debounce_packs_bits()
test_poll_interrupt_returns_13bit()
test_enable_disable_interrupt()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
