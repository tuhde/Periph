import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.humidity.hdc1080 import HDC1080Full, HDC1080Minimal

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


def make(full=True):
    c = I2CConnectionMock()
    return c, (HDC1080Full if full else HDC1080Minimal)(c)


c, s = make()
check_true('ctor_no_bus_access', c.writes == [])
s.init()
check_true('init_writes_default_config', c.writes == [bytes([0x02, 0x10, 0x00])])

# sequence read: 0x6666 -> 26 degC (approx), 0x8000 -> 50 %RH
c, s = make(False)
c.queue_read(bytes([0x66, 0x66, 0x80, 0x00]))
t, h = s.read()
check_true('read_trigger_pointer_0', c.writes == [bytes([0x00])])
check_close('read_temperature', t, 0x6666 / 65536.0 * 165.0 - 40.0)
check_close('read_humidity', h, 50.0)

# NACK polling then success
c, s = make(False)
c.fail_next_reads(3)
c.queue_read(bytes([0x66, 0x66, 0x80, 0x00]))
check_close('read_after_nack', s.read()[1], 50.0)

# timeout
c, s = make(False)
c.fail_next_reads(1000)
check_true('read_timeout', raises(s.read, OSError))

# single measurements switch mode
c, s = make()
c.queue_read(bytes([0x80, 0x00]))
check_close('read_temperature_single', s.read_temperature(), 42.5)
check_true('single_mode_cfg', c.writes == [bytes([0x02, 0x00, 0x00]), bytes([0x00])])
c.queue_read(bytes([0x80, 0x00]))
check_close('read_humidity_single', s.read_humidity(), 50.0)
check_true('humidity_pointer_1', c.writes[-1] == bytes([0x01]))

# raw
c, s = make()
c.queue_read(bytes([0x12, 0x34, 0x56, 0x78]))
check_true('read_raw', s.read_raw() == (0x1234, 0x5678))

# resolution
c, s = make()
s.set_resolution(11, 8)
check_true('resolution_11_8', c.writes[-1] == bytes([0x02, 0x16, 0x00]))
s.set_resolution(14, 14)
check_true('resolution_14_14', c.writes[-1] == bytes([0x02, 0x10, 0x00]))
check_true('resolution_bad_t', raises(lambda: s.set_resolution(8, 14), ValueError))
check_true('resolution_bad_h', raises(lambda: s.set_resolution(14, 12), ValueError))

# heater
s.set_heater(True)
check_true('heater_on', c.writes[-1] == bytes([0x02, 0x30, 0x00]))
s.set_heater(False)
check_true('heater_off', c.writes[-1] == bytes([0x02, 0x10, 0x00]))
c.queue_read(bytes([0x30, 0x00]))
check_true('is_heater_on', s.is_heater_on())
c.queue_read(bytes([0x18, 0x00]))
check_true('battery_low', s.battery_low())
c.queue_read(bytes([0x10, 0x00]))
check_true('battery_ok', not s.battery_low())
c.queue_read(bytes([0x10, 0x00]))
check_true('read_config', s.read_config() == 0x1000)

# reset rewrites config
c, s = make()
s.set_heater(True)
s.software_reset()
check_true('reset_then_config', c.writes[-2] == bytes([0x02, 0x80, 0x00]) and c.writes[-1] == bytes([0x02, 0x30, 0x00]))

# serial id
c, s = make()
c.queue_read(bytes([0x00, 0x12]))
c.queue_read(bytes([0x34, 0x56]))
c.queue_read(bytes([0x80, 0x00]))
check_true('serial_id', s.serial_id() == (0x12 << 24) | (0x3456 << 8) | 0x100)

# conversion time
c, s = make()
check_close('conv_time_14_14', s.conversion_time(), 0.01285)
s.set_resolution(11, 8)
check_close('conv_time_11_8', s.conversion_time(), 0.00365 + 0.0025)

# manufacturer and device ID
c, s = make()
c.queue_read(bytes([0x54, 0x49]))
check_true('manufacturer_id', s.manufacturer_id() == 0x5449)
check_true('manufacturer_pointer', c.writes[-1] == bytes([0xFE]))
c.queue_read(bytes([0x10, 0x50]))
check_true('device_id', s.device_id() == 0x1050)
check_true('device_pointer', c.writes[-1] == bytes([0xFF]))

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
