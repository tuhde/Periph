"""Unit test for the AD5243 — runs without hardware using the I2C mock.

Verifies instruction-byte encoding, clamping, resistance conversions,
readback addressing, shutdown tracking and reset by observing what the driver
writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ad5243 import AD5243Minimal, AD5243Full

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
pot = AD5243Minimal(mock)
check_true('init_no_traffic', mock.writes == [])

pot.set_wiper(0, 100)
check_true('set_wiper_ch0', mock.writes[-1] == bytes([0x00, 100]))
pot.set_wiper(1, 200)
check_true('set_wiper_ch1', mock.writes[-1] == bytes([0x80, 200]))
pot.set_wiper(0, 300)
check_true('set_wiper_clamps_high', mock.writes[-1] == bytes([0x00, 255]))
pot.set_wiper(0, -5)
check_true('set_wiper_clamps_low', mock.writes[-1] == bytes([0x00, 0]))
try:
    pot.set_wiper(2, 1)
    check_true('invalid_channel_raises', False)
except ValueError:
    check_true('invalid_channel_raises', True)

# Readback: instruction byte selects the channel (mock keys registers by instruction).
mock = I2CConnectionMock({0x00: 17, 0x80: 99})
pot = AD5243Minimal(mock)
check_true('get_wiper_ch0', pot.get_wiper(0) == 17)
check_true('get_wiper_ch1', pot.get_wiper(1) == 99)

# Resistance: D = round((R - 2*RW) / RAB * 256); 10k/160 default.
mock = I2CConnectionMock()
pot = AD5243Minimal(mock)
pot.set_resistance(0, 5320.0)
check_true('set_resistance_mid', mock.writes[-1] == bytes([0x00, 128]))
pot.set_resistance(0, 0.0)
check_true('set_resistance_clamps_low', mock.writes[-1] == bytes([0x00, 0]))
pot.set_resistance(1, 1e6)
check_true('set_resistance_clamps_high', mock.writes[-1] == bytes([0x80, 255]))
mock = I2CConnectionMock({0x00: 128, 0x80: 0})
pot = AD5243Minimal(mock, r_ab=10000.0, r_wiper=160.0)
check_true('get_resistance_mid', abs(pot.get_resistance(0) - 5320.0) < 0.01)
check_true('get_resistance_zero', abs(pot.get_resistance(1) - 320.0) < 0.01)

# Full API.
mock = I2CConnectionMock()
full = AD5243Full(mock)
full.set_both(1, 2)
check_true('set_both', mock.writes == [bytes([0x00, 1]), bytes([0x80, 2])])
full.set_ratio(0, 0.25)
check_true('set_ratio_quarter', mock.writes[-1] == bytes([0x00, 64]))
full.set_ratio(0, 1.0)
check_true('set_ratio_clamps_255', mock.writes[-1] == bytes([0x00, 255]))
full.set_resistance_wa(1, 5320.0)
check_true('set_resistance_wa_mid', mock.writes[-1] == bytes([0x80, 128]))

mock = I2CConnectionMock({0x00: 250, 0x80: 128})
full = AD5243Full(mock)
check_true('get_ratio', full.get_ratio(1) == 0.5)
check_true('get_resistance_wa', abs(full.get_resistance_wa(1) - 5320.0) < 0.01)
check_true('increment_returns', full.increment(0, 3) == 253 and mock.writes[-1] == bytes([0x00, 253]))
check_true('increment_clamps_high', full.increment(0, 100) == 255)
check_true('increment_negative', full.increment(1, -28) == 100 and mock.writes[-1] == bytes([0x80, 100]))

# Shutdown: SD bit set, tracked code rewritten, later writes keep SD.
mock = I2CConnectionMock()
full = AD5243Full(mock)
full.set_wiper(1, 77)
check_true('not_shutdown_initially', not full.is_shutdown(1))
full.shutdown(1)
check_true('shutdown_write', mock.writes[-1] == bytes([0xC0, 77]))
check_true('is_shutdown', full.is_shutdown(1) and not full.is_shutdown(0))
full.set_wiper(1, 50)
check_true('set_wiper_keeps_sd', mock.writes[-1] == bytes([0xC0, 50]))
full.resume(1)
check_true('resume_write', mock.writes[-1] == bytes([0x80, 50]) and not full.is_shutdown(1))
full.shutdown(0)
check_true('shutdown_ch0_midscale', mock.writes[-1] == bytes([0x40, 128]))

# Reset: midscale on both channels, SD cleared.
full.reset()
check_true('reset_writes', mock.writes[-2:] == [bytes([0x00, 128]), bytes([0x80, 128])])
check_true('reset_clears_shutdown', not full.is_shutdown(0))

# set_rab changes conversions without traffic.
mock = I2CConnectionMock({0x00: 128})
full = AD5243Full(mock)
full.set_rab(50000.0, 100.0)
check_true('set_rab_no_traffic', mock.writes == [])
check_true('set_rab_conversion', abs(full.get_resistance(0) - 25200.0) < 0.01)
full.set_rab(10000.0)
check_true('set_rab_keeps_rw', abs(full.get_resistance(0) - 5200.0) < 0.01)

print('===DONE: %d passed, %d failed===' % (passed, failed))
