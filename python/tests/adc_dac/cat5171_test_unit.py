"""Unit test for the CAT5171 — runs without hardware using the I2C mock.

Verifies instruction-byte encoding, clamping, resistance conversions, the
bare readback, midscale/shutdown tracking and reset by observing what the
driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.cat5171 import CAT5171Minimal, CAT5171Full

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


def mock_with(*reads):
    m = I2CConnectionMock()
    for r in reads:
        m.queue_read(bytes([r]))
    return m


# Construction makes no I2C traffic.
mock = I2CConnectionMock()
pot = CAT5171Minimal(mock)
check_true('init_no_traffic', mock.writes == [])

pot.set_wiper(100)
check_true('set_wiper', mock.writes[-1] == bytes([0x00, 100]))
pot.set_wiper(300)
check_true('set_wiper_clamps_high', mock.writes[-1] == bytes([0x00, 255]))
pot.set_wiper(-5)
check_true('set_wiper_clamps_low', mock.writes[-1] == bytes([0x00, 0]))

# Readback is a bare one-byte read: no write precedes it.
mock = mock_with(17)
pot = CAT5171Minimal(mock)
check_true('get_wiper', pot.get_wiper() == 17)
check_true('get_wiper_no_write', mock.writes == [])

# Resistance: D = round((R - RW) / RAB * 256); 100k/50 default.
mock = I2CConnectionMock()
pot = CAT5171Minimal(mock)
pot.set_resistance(50050.0)
check_true('set_resistance_mid', mock.writes[-1] == bytes([0x00, 128]))
pot.set_resistance(0.0)
check_true('set_resistance_clamps_low', mock.writes[-1] == bytes([0x00, 0]))
pot.set_resistance(1e7)
check_true('set_resistance_clamps_high', mock.writes[-1] == bytes([0x00, 255]))
mock = mock_with(128, 0)
pot = CAT5171Minimal(mock, r_ab=100000.0, r_wiper=50.0)
check_true('get_resistance_mid', abs(pot.get_resistance() - 50050.0) < 0.01)
check_true('get_resistance_zero', abs(pot.get_resistance() - 50.0) < 0.01)

# Full API.
mock = I2CConnectionMock()
full = CAT5171Full(mock)
full.set_ratio(0.25)
check_true('set_ratio_quarter', mock.writes[-1] == bytes([0x00, 64]))
full.set_ratio(1.0)
check_true('set_ratio_clamps_255', mock.writes[-1] == bytes([0x00, 255]))
full.set_resistance_wa(50050.0)
check_true('set_resistance_wa_mid', mock.writes[-1] == bytes([0x00, 128]))

mock = mock_with(128, 250, 250, 128)
full = CAT5171Full(mock)
check_true('get_ratio', full.get_ratio() == 0.5)
check_true('increment_returns', full.increment(3) == 253 and mock.writes[-1] == bytes([0x00, 253]))
check_true('increment_clamps_high', full.increment(100) == 255)
check_true('increment_negative', full.increment(-28) == 100 and mock.writes[-1] == bytes([0x00, 100]))
mock = mock_with(64)
full = CAT5171Full(mock)
check_true('get_resistance_wa', abs(full.get_resistance_wa() - 75050.0) < 0.01)

# Shutdown: SD bit set, tracked code rewritten, later writes keep SD.
mock = I2CConnectionMock()
full = CAT5171Full(mock)
check_true('not_shutdown_initially', not full.is_shutdown())
full.shutdown()
check_true('shutdown_midscale', mock.writes[-1] == bytes([0x20, 128]) and full.is_shutdown())
full.resume()
check_true('resume_write', mock.writes[-1] == bytes([0x00, 128]) and not full.is_shutdown())
full.set_wiper(77)
full.shutdown()
check_true('shutdown_write', mock.writes[-1] == bytes([0x20, 77]))
full.set_wiper(50)
check_true('set_wiper_keeps_sd', mock.writes[-1] == bytes([0x20, 50]))
full.resume()
check_true('resume_tracked', mock.writes[-1] == bytes([0x00, 50]))

# Midscale: RS bit; SD kept while shut down.
full.midscale()
check_true('midscale_write', mock.writes[-1] == bytes([0x40, 0x80]))
full.shutdown()
full.midscale()
check_true('midscale_keeps_sd', mock.writes[-1] == bytes([0x60, 0x80]) and full.is_shutdown())

# Reset: RS bit, SD cleared.
full.reset()
check_true('reset_write', mock.writes[-1] == bytes([0x40, 0x80]))
check_true('reset_clears_shutdown', not full.is_shutdown())

# set_rab changes conversions without traffic.
mock = mock_with(128, 128)
full = CAT5171Full(mock)
full.set_rab(50000.0, 100.0)
check_true('set_rab_no_traffic', mock.writes == [])
check_true('set_rab_conversion', abs(full.get_resistance() - 25100.0) < 0.01)
full.set_rab(50000.0)
check_true('set_rab_keeps_rw', abs(full.get_resistance() - 25100.0) < 0.01)

print('===DONE: %d passed, %d failed===' % (passed, failed))
