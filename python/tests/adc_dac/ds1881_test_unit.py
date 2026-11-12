"""Unit test for the DS1881 — runs without hardware using the I2C mock.

Verifies the initialization sequence, command-byte encoding, the Option 1 and
2 tables, the three-byte round-robin read, configuration handling, EEPROM
acknowledge polling and reset by observing what the driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.adc_dac.ds1881 import DS1881Minimal, DS1881Full

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


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


def mock_with(*reads):
    m = I2CConnectionMock()
    for r in reads:
        m.queue_read(bytes(r))
    return m


# Construction makes no I2C traffic.
mock = I2CConnectionMock()
pot = DS1881Minimal(mock)
check_true('ctor_no_traffic', mock.writes == [])

# init: configuration differs -> one command byte 0x86; already 0x86 -> nothing sent.
pot.init()
check_true('init_writes_config', mock.writes == [bytes([0x86])])
mock = mock_with([10, 20, 0x86])
pot = DS1881Minimal(mock)
pot.init()
check_true('init_matching_sends_nothing', mock.writes == [])
mock = mock_with([10, 20, 0xFF])  # bits 5:3 are don't care: low 3 bits 0b111 differ
pot = DS1881Minimal(mock)
pot.init()
check_true('init_differs_in_low_bits', mock.writes == [bytes([0x86])])
mock = mock_with([0, 0, 0xB6])  # stray upper bits, low bits already 0b110
pot = DS1881Minimal(mock)
pot.init()
check_true('init_ignores_upper_bits', mock.writes == [])

# Attenuation: command byte (channel << 6) + position, rounding, clamping.
mock = I2CConnectionMock()
pot = DS1881Minimal(mock)
pot.set_attenuation(0, 20.0)
check_true('set_attenuation_ch0', mock.writes[-1] == bytes([20]))
pot.set_attenuation(1, 20.4)
check_true('set_attenuation_ch1_rounds_down', mock.writes[-1] == bytes([0x40 | 20]))
pot.set_attenuation(1, 20.6)
check_true('set_attenuation_rounds_up', mock.writes[-1] == bytes([0x40 | 21]))
pot.set_attenuation(0, 100.0)
check_true('set_attenuation_clamps_62', mock.writes[-1] == bytes([62]))
pot.set_attenuation(0, -5.0)
check_true('set_attenuation_clamps_0', mock.writes[-1] == bytes([0]))
check_true('bad_channel', raises(lambda: pot.set_attenuation(2, 0.0), ValueError))
check_true('bad_channel_get', raises(lambda: pot.get_position(-1), ValueError))

pot.mute(1)
check_true('mute', mock.writes[-1] == bytes([0x7F]))
pot.set_position(0, 99)
check_true('set_position_clamps', mock.writes[-1] == bytes([63]))
pot.set_position(0, -3)
check_true('set_position_clamps_low', mock.writes[-1] == bytes([0]))

# Reads: three-byte round robin, upper bits masked.
mock = mock_with(*[[0xC5, 0x3F, 0x86]] * 4)
pot = DS1881Minimal(mock)
check_true('get_position_ch0', pot.get_position(0) == 0x05)
check_true('get_position_ch1', pot.get_position(1) == 63)
check_true('get_attenuation_mute', pot.get_attenuation(1) == 80.0)
check_true('get_attenuation_value', pot.get_attenuation(0) == 5.0)
check_true('reads_no_writes', mock.writes == [])

# Full: stereo write in one transaction, db1 defaults to db0.
mock = I2CConnectionMock()
full = DS1881Full(mock)
full.set_both(10, 99)
check_true('set_both_one_transaction', mock.writes == [bytes([10, 0x40 | 63])])
full.set_attenuation_both(30.0)
check_true('set_attenuation_both_default', mock.writes[-1] == bytes([30, 0x40 | 30]))
full.set_attenuation_both(5.0, 70.0)
check_true('set_attenuation_both_mute', mock.writes[-1] == bytes([5, 0x7F]))

# Tables.
check_true('opt1_db', full.position_to_db(0) == 0.0 and full.position_to_db(62) == 62.0 and full.position_to_db(63) == 80.0)
check_true('max_position_opt1', full.max_position() == 62)
check_true('db_to_position_opt1', full.db_to_position(12.4) == 12 and full.db_to_position(75.0) == 63)

# configure: Option 2 -> 0x87, not rewritten when unchanged, table switches.
mock = I2CConnectionMock()
full = DS1881Full(mock)
full.configure(2, True, False)
check_true('configure_option2', mock.writes[-1] == bytes([0x87]))
n = len(mock.writes)
full.configure(2, True, False)
check_true('configure_unchanged_no_write', len(mock.writes) == n)
check_true('opt2_table', [full.position_to_db(p) for p in (0, 12, 13, 24, 25, 32, 33)] == [0.0, 12.0, 14.0, 36.0, 39.0, 60.0, 80.0])
check_true('max_position_opt2', full.max_position() == 32)
check_true('db_to_position_opt2', full.db_to_position(14.0) == 13 and full.db_to_position(36.0) == 24 and full.db_to_position(60.0) == 32)
check_true('db_to_position_opt2_nearest', full.db_to_position(37.0) == 24 and full.db_to_position(38.0) == 25)
check_true('db_to_position_opt2_mute', full.db_to_position(70.0) == 33)
full.set_both(40, 40)
check_true('opt2_positions_clamped', mock.writes[-1] == bytes([33, 0x40 | 33]))
full.mute(0)
check_true('opt2_mute', mock.writes[-1] == bytes([33]))
check_true('bad_option', raises(lambda: full.configure(3), ValueError))

# set_option / set_zero_crossing / set_nonvolatile keep the other bits.
mock = I2CConnectionMock()
full = DS1881Full(mock)
full.set_zero_crossing(False)
check_true('zc_off', mock.writes[-1] == bytes([0x84]))
full.set_nonvolatile(True)
check_true('nv_on', mock.writes[-1] == bytes([0x80]))
full.set_option(2)
check_true('option2_keeps_bits', mock.writes[-1] == bytes([0x81]))

# NV mode: wiper writes poll for acknowledge (one failing read, then success).
mock.fail_next_reads(1)
full.set_both(1, 2)
check_true('nv_write_polls', mock.writes[-1] == bytes([1, 0x42]))

# get_config.
mock = mock_with([0, 0, 0x87])
full = DS1881Full(mock)
cfg = full.get_config()
check_true('get_config', cfg == {'option': 2, 'zero_crossing': True, 'nonvolatile': False, 'raw': 0x87})
check_true('get_config_updates_cache', full.max_position() == 32)
mock = mock_with([1, 2, 0x82])
full = DS1881Full(mock)
cfg = full.get_config()
check_true('get_config_nv', cfg['nonvolatile'] is True and cfg['option'] == 1 and cfg['zero_crossing'] is True)

# read_all.
mock = mock_with([0x41, 0x02, 0x86])
full = DS1881Full(mock)
check_true('read_all', full.read_all() == (1, 2, 0x86))

# wait_ready: polls through failures, times out when never ready.
mock = I2CConnectionMock()
full = DS1881Full(mock)
mock.fail_next_reads(3)
full.wait_ready(0.05)
check_true('wait_ready_recovers', True)
mock.fail_next_reads(1000)
check_true('wait_ready_times_out', raises(lambda: full.wait_ready(0.01), OSError))

# store: volatile -> NV config, both wipers in one transaction, back to volatile.
mock = mock_with([12, 34, 0x86])
full = DS1881Full(mock)
full.store()
check_true('store_sequence', mock.writes == [bytes([0x82]), bytes([12, 0x40 | 34]), bytes([0x86])])
# store when already NV: only the wiper rewrite.
mock = I2CConnectionMock()
full = DS1881Full(mock)
full.set_nonvolatile(True)
mock.writes = []
mock.queue_read(bytes([7, 8, 0x82]))
full.store()
check_true('store_nv_only_wipers', mock.writes == [bytes([7, 0x40 | 8])])

# reset.
mock = I2CConnectionMock()
full = DS1881Full(mock)
full.configure(2, False, True)
full.reset()
check_true('reset', mock.writes[-1] == bytes([0x86, 0x3F, 0x7F]))
check_true('reset_restores_option1', full.max_position() == 62)

print('===DONE: %d passed, %d failed===' % (passed, failed))
