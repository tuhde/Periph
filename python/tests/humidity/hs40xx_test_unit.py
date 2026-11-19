import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.hs40xx import (HS40xxFull, HS40xxMinimal, HS4001Full, HS4001Minimal,
                                          HS4003Full, HS4003Minimal, _crc8)

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


def check_close(label, got, expected, tol=1e-6):
    check_true(label, abs(got - expected) <= tol)


def raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


def ht(h, t):
    d = bytes([h >> 8, h & 0xFF, t >> 8, t & 0xFF])
    return d + bytes([_crc8(d)])


def t_only(t):
    d = bytes([t >> 8, t & 0xFF])
    return d + bytes([_crc8(bytes(2) + d)])


def make(full=True):
    c = I2CConnectionMock()
    return c, (HS40xxFull if full else HS40xxMinimal)(c)


check_true('crc_datasheet_example', _crc8(bytes([0x05, 0x80, 0x05, 0x80])) == 0xF2)

c, s = make()
check_true('constructor_no_bus_access', c.writes == [])

check_true('alias_hs4001_full', HS4001Full is HS40xxFull)
check_true('alias_hs4003_full', HS4003Full is HS40xxFull)
check_true('alias_hs4001_minimal', HS4001Minimal is HS40xxMinimal)
check_true('alias_hs4003_minimal', HS4003Minimal is HS40xxMinimal)

# datasheet example frame: 05 80 05 80 F2
c, s = make(False)
c.queue_read(bytes([0x05, 0x80, 0x05, 0x80, 0xF2]))
m = s.read()
check_true('read_command', c.writes == [bytes([0xF5])])
check_close('example_humidity', m['humidity_pct'], 8.59, 0.01)
check_close('example_temperature', m['temperature_c'], -25.8, 0.05)

# full scale / zero
c, s = make(False)
c.queue_read(ht(0x3FFF, 0x3FFF))
m = s.read()
check_close('humidity_full_scale', m['humidity_pct'], 100.0)
check_close('temperature_full_scale', m['temperature_c'], 125.0)
c.queue_read(ht(0, 0))
m = s.read()
check_close('humidity_zero', m['humidity_pct'], 0.0)
check_close('temperature_zero', m['temperature_c'], -40.0)

# read header NACK is retried
c, s = make(False)
c.fail_next_reads(2)
c.queue_read(ht(0x2000, 0x1000))
check_close('nack_retry', s.read()['humidity_pct'], 50.0, 0.01)

# persistent NACK raises
c, s = make(False)
c.fail_next_reads(100000)
check_true('nack_timeout_raises', raises(s.read, OSError))

# CRC mismatch: retry once, then raise
c, s = make(False)
bad = bytearray(ht(0x2000, 0x1000))
bad[4] ^= 0xFF
c.queue_read(bytes(bad))
c.queue_read(ht(0x2000, 0x1000))
check_close('crc_retry_ok', s.read()['humidity_pct'], 50.0, 0.01)
check_true('crc_retry_reissues_command', c.writes == [bytes([0xF5]), bytes([0xF5])])
c, s = make(False)
c.queue_read(bytes(bad))
c.queue_read(bytes(bad))
check_true('crc_twice_raises', raises(s.read, RuntimeError))

# Full: single values
c, s = make()
c.queue_read(t_only(0x1000))
check_close('read_temperature', s.read_temperature(), 4096 / 16383 * 165.0 - 40.0)
check_true('read_temperature_command', c.writes == [bytes([0xF3])])
c.queue_read(ht(0x2000, 0x1000))
check_close('read_humidity', s.read_humidity(), 50.0, 0.01)
c.queue_read(ht(0x1234, 0x0ABC))
check_true('read_raw', s.read_raw() == (0x1234, 0x0ABC))
c, s = make()
c.queue_read(ht(0x2000, 0x1000))
check_close('read_hold', s.read_hold()['humidity_pct'], 50.0, 0.01)
check_true('read_hold_command', c.writes == [bytes([0xE5])])

# registers
c, s = make()
c.queue_read(bytes([0x5A]))
check_true('read_register', s.read_register(0x02) == 0x5A)
check_true('read_register_command', c.writes == [bytes([0xA7, 0x02])])
c, s = make()
s.write_register(0x03, 0x0A)
check_true('write_register_command', c.writes == [bytes([0xA6, 0x03, 0x0A])])

# resolution: read-modify-write
c, s = make()
c.queue_read(bytes([0xF3]))
s.set_resolution(8, 12)
check_true('set_resolution_sequence', c.writes == [bytes([0xA7, 0]), bytes([0xA6, 0, 0xF2])])
c, s = make()
c.queue_read(bytes([0xFF]))
s.set_resolution(temperature_bits=10)
check_true('set_resolution_temperature_only', c.writes[-1] == bytes([0xA6, 0, 0xFD]))
c, s = make()
check_true('set_resolution_invalid', raises(lambda: s.set_resolution(9, None), ValueError))
check_true('set_resolution_invalid_no_bus', c.writes == [])
c, s = make()
c.queue_read(bytes([0x0B]))
check_true('get_resolution', s.get_resolution() == (12, 14))

# periodic
c, s = make()
c.queue_read(bytes([0x00]))   # alert enable register
c.queue_read(bytes([0x01]))   # periodic register (polarity bit set)
s.start_periodic(2.5)
check_true('start_periodic_sequence', c.writes == [bytes([0xA7, 3]), bytes([0xA7, 2]), bytes([0xA6, 2, 0xA1])])
check_true('start_periodic_invalid', raises(lambda: s.start_periodic(3), ValueError))
c.queue_read(ht(0x2000, 0x1000))
r = s.read_periodic()
check_true('read_periodic_no_alert', r['status'] == 0 and abs(r['humidity_pct'] - 50.0) < 0.01)
s.stop_periodic()
check_true('stop_periodic', c.writes[-1] == bytes([0x30]))

# periodic with alerts: 6-byte frame with status
c, s = make()
c.queue_read(bytes([0x00]))
s.enable_interrupt(s.SOURCE_RH_HIGH)
check_true('enable_interrupt', c.writes == [bytes([0xA7, 3]), bytes([0xA6, 3, 0x02])])
c.queue_read(bytes([0x0A]) + ht(0x2000, 0x1000))
r = s.read_periodic()
check_true('read_periodic_status', r['status'] == 0x0A)
c.queue_read(bytes([0x02]) + ht(0x2000, 0x1000))
check_true('poll_interrupt', s.poll_interrupt() == 0x02)
c.queue_read(bytes([0x02]))
s.disable_interrupt(s.SOURCE_RH_HIGH)
check_true('disable_interrupt', c.writes[-1] == bytes([0xA6, 3, 0x00]))
check_true('poll_interrupt_needs_alerts', raises(s.poll_interrupt, RuntimeError))

# thresholds: 60 %RH -> 9830 = 0x2666; reset 55 %RH -> 9010 = 0x2332
c, s = make()
s.set_alert_thresholds(rh_high=60.0, rh_high_reset=55.0)
check_true('rh_thresholds', c.writes == [bytes([0xA6, 0x0F, 0x66]), bytes([0xA6, 0x10, 0x26]),
                                         bytes([0xA6, 0x0D, 0x33]), bytes([0xA6, 0x0E, 0x23])])
c, s = make()
s.set_alert_thresholds(temp_low=-40.0, temp_high=125.0)
check_true('temp_thresholds', c.writes == [bytes([0xA6, 0x07, 0xFF]), bytes([0xA6, 0x08, 0x3F]),
                                           bytes([0xA6, 0x09, 0x00]), bytes([0xA6, 0x0A, 0x00])])

# polarity
c, s = make()
c.queue_read(bytes([0xB1]))
s.set_alert_polarity(False)
check_true('set_alert_polarity', c.writes[-1] == bytes([0xA6, 2, 0xB0]))

# ID and reset
c, s = make()
c.queue_read(bytes([0xDE, 0xAD, 0xBE, 0xEF]))
check_true('read_id', s.read_id() == 0xDEADBEEF)
check_true('read_id_command', c.writes == [bytes([0xD7])])
c, s = make()
s.soft_reset()
check_true('soft_reset', c.writes == [bytes([0x30]), bytes([0xFE])])

# dew point
c, s = make()
c.queue_read(ht(int(0.5 * 16383), int((25.0 + 40.0) / 165.0 * 16383)))
check_close('dew_point', s.dew_point(), 13.86, 0.1)
c.queue_read(ht(0, 0x1000))
check_close('dew_point_zero_humidity', s.dew_point(), -273.15)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
