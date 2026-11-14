"""Unit test for the AD5251 — runs without hardware using the I2C mock.

Verifies instruction-byte encoding, clamping, resistance conversions,
readback addressing, quick commands, EEMEM store/restore with ACK polling,
user EEMEM and the tolerance conversion by observing what the driver writes
to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ad5251 import AD5251Minimal, AD5251Full

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


# Construction makes no I2C traffic.
mock = I2CConnectionMock()
pot = AD5251Minimal(mock)
check_true('init_no_traffic', mock.writes == [])

pot.set_wiper(0, 40)
check_true('set_wiper_ch0', mock.writes[-1] == bytes([0x01, 40]))
pot.set_wiper(1, 60)
check_true('set_wiper_ch1', mock.writes[-1] == bytes([0x03, 60]))
pot.set_wiper(0, 300)
check_true('set_wiper_clamps_high', mock.writes[-1] == bytes([0x01, 63]))
pot.set_wiper(0, -5)
check_true('set_wiper_clamps_low', mock.writes[-1] == bytes([0x01, 0]))
try:
    pot.set_wiper(2, 1)
    check_true('invalid_channel_raises', False)
except ValueError:
    check_true('invalid_channel_raises', True)

# Readback: the instruction byte is the register pointer.
mock = I2CConnectionMock({0x01: 17, 0x03: 99})
pot = AD5251Minimal(mock)
check_true('get_wiper_ch0', pot.get_wiper(0) == 17)
check_true('get_wiper_ch1', pot.get_wiper(1) == 99)
check_true('get_wiper_pointer_write', mock.writes == [bytes([0x01]), bytes([0x03])])

# Resistance: D = round((R - RW) / RAB * 64); 10k/75 default.
mock = I2CConnectionMock()
pot = AD5251Minimal(mock)
pot.set_resistance(0, 25075.0)
check_true('set_resistance_mid', mock.writes[-1] == bytes([0x01, 32]))
pot.set_resistance(0, 0.0)
check_true('set_resistance_clamps_low', mock.writes[-1] == bytes([0x01, 0]))
pot.set_resistance(1, 1e6)
check_true('set_resistance_clamps_high', mock.writes[-1] == bytes([0x03, 63]))
mock = I2CConnectionMock({0x01: 32, 0x03: 0})
pot = AD5251Minimal(mock, r_ab=50000.0, r_wiper=75.0)
check_true('get_resistance_mid', abs(pot.get_resistance(0) - 25075.0) < 0.01)
check_true('get_resistance_zero', abs(pot.get_resistance(1) - 75.0) < 0.01)

# Full API.
mock = I2CConnectionMock()
full = AD5251Full(mock)
full.set_both(1, 2)
check_true('set_both_one_transaction', mock.writes == [bytes([0x01, 1, 2])])
full.set_ratio(0, 0.25)
check_true('set_ratio_quarter', mock.writes[-1] == bytes([0x01, 16]))
full.set_ratio(0, 1.0)
check_true('set_ratio_clamps_255', mock.writes[-1] == bytes([0x01, 63]))
full.set_resistance_wa(1, 25075.0)
check_true('set_resistance_wa_mid', mock.writes[-1] == bytes([0x03, 32]))

mock = I2CConnectionMock({0x01: 250, 0x03: 32})
full = AD5251Full(mock)
check_true('get_ratio', full.get_ratio(1) == 0.5)
check_true('get_resistance_wa', abs(full.get_resistance_wa(1) - 25075.0) < 0.01)

# Quick commands.
mock = I2CConnectionMock()
full = AD5251Full(mock)
full.increment(0)
full.increment(1)
full.increment()
full.decrement(0)
full.decrement(1)
full.decrement()
check_true('increment_decrement_bytes', mock.writes == [bytes([b]) for b in (0xD1, 0xD3, 0xD8, 0xA9, 0xAB, 0xB0)])
mock.writes.clear()
full.double(0)
full.double(1)
full.double()
full.halve(0)
full.halve(1)
full.halve()
check_true('double_halve_bytes', mock.writes == [bytes([b]) for b in (0xC1, 0xC3, 0xC8, 0x99, 0x9B, 0xA0)])
mock.writes.clear()
full.nop()
check_true('nop', mock.writes == [bytes([0x80])])
try:
    full.increment(2)
    check_true('quick_invalid_channel_raises', False)
except ValueError:
    check_true('quick_invalid_channel_raises', True)

# Store: quick command, then NOP ACK polling until the chip answers.
mock = I2CConnectionMock()
full = AD5251Full(mock)
full.store(1)
check_true('store_ch1', mock.writes == [bytes([0x93]), bytes([0x80])])
mock.writes.clear()
mock.fail_next_writes(2, after=1)
full.store(0)
check_true('store_polls_until_ack', mock.writes == [bytes([0x91]), bytes([0x80]), bytes([0x80]), bytes([0x80])])
mock.writes.clear()
full.store()
check_true('store_both', mock.writes == [bytes([0x91]), bytes([0x80]), bytes([0x93]), bytes([0x80])])
mock = I2CConnectionMock()
full = AD5251Full(mock)
mock.fail_next_writes(1000, after=1)
try:
    full.store(0)
    check_true('store_timeout_raises', False)
except OSError:
    check_true('store_timeout_raises', True)

# Restore: quick command(s) followed by NOP.
mock = I2CConnectionMock()
full = AD5251Full(mock)
full.restore(0)
check_true('restore_ch0', mock.writes == [bytes([0x89]), bytes([0x80])])
mock.writes.clear()
full.restore()
check_true('restore_both', mock.writes == [bytes([0x89]), bytes([0x8B]), bytes([0x80])])

# Reset: 0xB8 then ACK polling.
mock.writes.clear()
full.reset()
check_true('reset', mock.writes == [bytes([0xB8]), bytes([0x80])])

# User EEMEM.
mock = I2CConnectionMock({0x24: 0x5A, 0x2F: 0xA5})
full = AD5251Full(mock)
check_true('read_eemem_4', full.read_eemem(4) == 0x5A)
check_true('read_eemem_15', full.read_eemem(15) == 0xA5)
mock.writes.clear()
full.write_eemem(9, 0x42)
check_true('write_eemem', mock.writes == [bytes([0x29, 0x42]), bytes([0x80])])
for bad in (3, 16):
    try:
        full.read_eemem(bad)
        check_true('read_eemem_range_%d' % bad, False)
    except ValueError:
        check_true('read_eemem_range_%d' % bad, True)
try:
    full.write_eemem(2, 0)
    check_true('write_eemem_range', False)
except ValueError:
    check_true('write_eemem_range', True)

# Tolerance: datasheet example 0x1C / 0x0F -> +28.0586 %; negative via sign bit.
mock = I2CConnectionMock({0x3A: 0x1C, 0x3B: 0x0F, 0x3E: 0x82, 0x3F: 0x80})
full = AD5251Full(mock)
check_true('tolerance_positive', abs(full.read_tolerance(0) - 28.05859375) < 1e-6)
check_true('tolerance_negative', abs(full.read_tolerance(1) + 2.5) < 1e-9)
check_true('rab_actual', abs(full.rab_actual(0) - 64029.296875) < 0.01)

# set_rab changes conversions without traffic.
mock = I2CConnectionMock({0x01: 32})
full = AD5251Full(mock)
full.set_rab(50000.0, 100.0)
check_true('set_rab_no_traffic', mock.writes == [])
check_true('set_rab_conversion', abs(full.get_resistance(0) - 25100.0) < 0.01)
full.set_rab(10000.0)
check_true('set_rab_keeps_rw', abs(full.get_resistance(0) - 5100.0) < 0.01)

print('===DONE: %d passed, %d failed===' % (passed, failed))
