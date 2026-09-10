"""Unit test for the APDS-9930 driver.

Uses the I2CConnectionMock to exercise register-level logic without
hardware. The APDS-9930 has a command-register protocol: every bus
transaction starts with a command byte whose high bits are the type
(0x80=write, 0xA0=auto-increment read, 0xE0=special) and whose low 5
bits are the register address. The mock is addressed by raw byte
index, so this test preloads registers at the command-byte addresses
(0x80|reg for the chip's "set register N" address space, 0xA0|reg for
the "read register N" address space) and inspects writes the same way.
"""

import sys

sys.path.insert(0, 'python')

from periph.chips.light.apds_9930 import APDS9930Minimal, APDS9930Full
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


def cmd_write(reg):
    return 0x80 | (reg & 0x1F)


def cmd_read(reg):
    return 0xA0 | (reg & 0x1F)


def test_minimal_construction():
    """Minimal class runs init without error when ID is 0x39."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39  # ID register
    mock.registers[cmd_read(0x0F)] = 0x00  # CONTROL
    mock.registers[cmd_read(0x01)] = 0xDB  # ATIME
    mock.registers[cmd_read(0x02)] = 0xFF  # PTIME
    mock.registers[cmd_read(0x0E)] = 0x08  # PPULSE
    mock.registers[cmd_read(0x0D)] = 0x00  # CONFIG
    mock.registers[cmd_read(0x14)] = 0x00  # CH0DATAL
    mock.registers[cmd_read(0x15)] = 0x00  # CH0DATAH
    mock.registers[cmd_read(0x16)] = 0x00  # CH1DATAL
    mock.registers[cmd_read(0x17)] = 0x00  # CH1DATAH
    mock.registers[cmd_read(0x18)] = 0x00  # PDATAL
    mock.registers[cmd_read(0x19)] = 0x00  # PDATAH
    APDS9930Minimal(mock)
    wrote_enable = any(w[0] == cmd_write(0x00) and w[1] == 0x07
                       for w in mock.writes)
    check_true('Minimal init enables PON|AEN|PEN', wrote_enable)
    wrote_ppulse = any(w[0] == cmd_write(0x0E) and w[1] == 0x08
                       for w in mock.writes)
    check_true('Minimal init writes PPULSE=0x08', wrote_ppulse)


def test_minimal_rejects_wrong_id():
    """Minimal raises ValueError when ID is not 0x39."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0xAB  # wrong ID
    try:
        APDS9930Minimal(mock)
    except ValueError:
        check_true('Minimal raises on wrong ID', True)
        return
    check_true('Minimal raises on wrong ID', False)


def test_lux_zero_when_ch0_zero():
    """lux returns 0 when both channels are dark (no ambient light)."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00  # CONTROL=0
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x00
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x00
    mock.registers[cmd_read(0x19)] = 0x00
    apds = APDS9930Minimal(mock)
    check_true('lux=0 when dark', apds.lux() == 0.0)


def test_lux_positive_when_ch0_only():
    """lux is positive when Ch0 has counts and Ch1 is zero (pure visible light)."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x10  # Ch0=4096
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x00
    mock.registers[cmd_read(0x19)] = 0x00
    apds = APDS9930Minimal(mock)
    check_true('lux>0 with visible-only light', apds.lux() > 0.0)


def test_proximity_returns_16bit():
    """proximity reads PDATAL/H as a little-endian 16-bit value."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x00
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x34
    mock.registers[cmd_read(0x19)] = 0x12
    apds = APDS9930Minimal(mock)
    check_true('proximity is 0x1234', apds.proximity() == 0x1234)


def test_full_configure_als():
    """Full.configure_als writes ATIME and AGAIN fields."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x00
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x00
    mock.registers[cmd_read(0x19)] = 0x00
    apds = APDS9930Full(mock)
    apds.configure_als(atime=0xF6, again=2, agl=False)
    wrote_atime = any(w[0] == cmd_write(0x01) and w[1] == 0xF6
                      for w in mock.writes)
    wrote_again = any(w[0] == cmd_write(0x0F) and (w[1] & 0x03) == 0x02
                      for w in mock.writes)
    check_true('configure_als writes ATIME', wrote_atime)
    check_true('configure_als writes AGAIN', wrote_again)


def test_full_status_decoded():
    """Full.status returns a dict of booleans."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x13)] = 0x01  # STATUS: AVALID
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x00
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x00
    mock.registers[cmd_read(0x19)] = 0x00
    apds = APDS9930Full(mock)
    st = apds.status()
    check_true('status has avalid bool', isinstance(st.get('avalid'), bool))
    check_true('status avalid=True with STATUS=0x01', st['avalid'] is True)
    check_true('status pvalid=False with STATUS=0x01', st['pvalid'] is False)


def test_clear_interrupt_writes_command():
    """Full.clear_interrupt issues the special-function command byte."""
    global passed, failed
    mock = I2CConnectionMock()
    mock.registers[cmd_read(0x12)] = 0x39
    mock.registers[cmd_read(0x0F)] = 0x00
    mock.registers[cmd_read(0x01)] = 0xDB
    mock.registers[cmd_read(0x02)] = 0xFF
    mock.registers[cmd_read(0x0E)] = 0x08
    mock.registers[cmd_read(0x0D)] = 0x00
    mock.registers[cmd_read(0x14)] = 0x00
    mock.registers[cmd_read(0x15)] = 0x00
    mock.registers[cmd_read(0x16)] = 0x00
    mock.registers[cmd_read(0x17)] = 0x00
    mock.registers[cmd_read(0x18)] = 0x00
    mock.registers[cmd_read(0x19)] = 0x00
    apds = APDS9930Full(mock)
    mock.writes.clear()
    apds.clear_interrupt('proximity')
    check_true('clear_interrupt(proximity) writes 0xE5',
               bytes([0xE5]) in [bytes(w) for w in mock.writes])
    mock.writes.clear()
    apds.clear_interrupt('als')
    check_true('clear_interrupt(als) writes 0xE6',
               bytes([0xE6]) in [bytes(w) for w in mock.writes])
    mock.writes.clear()
    apds.clear_interrupt('both')
    check_true('clear_interrupt(both) writes 0xE7',
               bytes([0xE7]) in [bytes(w) for w in mock.writes])


test_minimal_construction()
test_minimal_rejects_wrong_id()
test_lux_zero_when_ch0_zero()
test_lux_positive_when_ch0_only()
test_proximity_returns_16bit()
test_full_configure_als()
test_full_status_decoded()
test_clear_interrupt_writes_command()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)