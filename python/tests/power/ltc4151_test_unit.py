import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.power.ltc4151 import LTC4151Minimal, LTC4151Full

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


def close(a, b, tol=1e-9):
    return abs(a - b) < tol


connection = I2CConnectionMock()

# Construction makes no bus access
sensor = LTC4151Full(connection)
check_true('init_no_writes', len(connection.writes) == 0)

# VIN code 0x800 = 2048 -> 51.2 V; registers 0x02/0x03
connection.set_register(0x02, 0x80, 0x00)
check_true('voltage', close(sensor.voltage(), 51.2))

# SENSE code 0x400 = 1024 -> 20.48 mV -> 1.024 A at 20 mOhm
connection.set_register(0x00, 0x40, 0x00)
check_true('sense_voltage', close(sensor.sense_voltage(), 0.02048))
check_true('current', close(sensor.current(), 1.024))

# Power = 51.2 V * 1.024 A
check_true('power', close(sensor.power(), 51.2 * 1.024))

# The four LSBs sit in bits 7:4 of the low register; BUSY / reserved bits are ignored
connection.set_register(0x02, 0x00, 0xF8)
check_true('voltage_low_nibble', close(sensor.voltage(), 15 * 0.025))

# ADIN code 0x7D0 = 2000 -> 1.0 V
connection.set_register(0x04, 0x7D, 0x00)
check_true('adin_voltage', close(sensor.adin_voltage(), 1.0))

# read_all: one block read of six bytes
connection.set_register(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
d = sensor.read_all()
check_true('read_all_vin', close(d['vin'], 51.2))
check_true('read_all_sense', close(d['sense_v'], 0.02048))
check_true('read_all_current', close(d['current'], 1.024))
check_true('read_all_power', close(d['power'], 51.2 * 1.024))
check_true('read_all_adin', close(d['adin'], 1.0))

# read_raw
check_true('read_raw_sense', sensor.read_raw(LTC4151Full.SENSE) == 0x400)
check_true('read_raw_vin', sensor.read_raw(LTC4151Full.VIN) == 0x800)
check_true('read_raw_adin', sensor.read_raw(LTC4151Full.ADIN) == 0x7D0)
try:
    sensor.read_raw(3)
    check_true('read_raw_bad_channel', False)
except ValueError:
    check_true('read_raw_bad_channel', True)

# Sense resistor
sensor.set_sense_resistor(0.01)
check_true('set_sense_resistor', close(sensor.get_sense_resistor(), 0.01) and close(sensor.current(), 2.048))
try:
    sensor.set_sense_resistor(0)
    check_true('set_sense_resistor_zero', False)
except ValueError:
    check_true('set_sense_resistor_zero', True)
sensor.set_sense_resistor(0.02)

# Minimal class: custom resistor, voltage/current/power
minimal = LTC4151Minimal(connection, r_sense=0.005)
check_true('minimal_current', close(minimal.current(), 4.096))
check_true('minimal_power', close(minimal.power(), 51.2 * 4.096))
check_true('minimal_voltage', close(minimal.voltage(), 51.2))

# CONTROL read-modify-write
connection.set_register(0x06, 0x0C)
check_true('read_control', sensor.read_control() == 0x0C)
connection.writes.clear()
sensor.set_stuck_bus_timer(False)
check_true('stuck_bus_off', connection.writes[-1] == bytes([0x06, 0x08]))
connection.set_register(0x06, 0x08)
sensor.set_stuck_bus_timer(True)
check_true('stuck_bus_on', connection.writes[-1] == bytes([0x06, 0x0C]))
sensor.set_page_rw(False)
check_true('page_rw_off', connection.writes[-1] == bytes([0x06, 0x04]))
connection.set_register(0x06, 0x04)
sensor.set_page_rw(True)
check_true('page_rw_on', connection.writes[-1] == bytes([0x06, 0x0C]))

# write_control masks test mode (bit 4)
sensor.write_control(0xFF)
check_true('write_control_masks_test', connection.writes[-1] == bytes([0x06, 0xEF]))
sensor.write_control(0x0C)

# is_snapshot_mode / set_continuous
connection.set_register(0x06, 0xA8)
check_true('is_snapshot_mode_true', sensor.is_snapshot_mode() is True)
sensor.set_continuous()
check_true('set_continuous', connection.writes[-1] == bytes([0x06, 0x28]))
connection.set_register(0x06, 0x0C)
check_true('is_snapshot_mode_false', sensor.is_snapshot_mode() is False)

# is_busy: bit 3 of the channel's low register
connection.set_register(0x01, 0x00)
connection.set_register(0x03, 0x08)
check_true('is_busy_sense_false', sensor.is_busy(LTC4151Full.SENSE) is False)
check_true('is_busy_vin_true', sensor.is_busy(LTC4151Full.VIN) is True)
connection.set_register(0x03, 0x00)

# snapshot(VIN): clears snapshot, writes channel 01 with snapshot set, restores continuous scan
connection.set_register(0x06, 0x0C)
connection.set_register(0x02, 0x80, 0x00)
connection.writes.clear()
value = sensor.snapshot(LTC4151Full.VIN)
check_true('snapshot_value', close(value, 51.2))
ctrl = [w for w in connection.writes if len(w) == 2 and w[0] == 0x06]
check_true('snapshot_clear_first', ctrl[0] == bytes([0x06, 0x0C]))
check_true('snapshot_start', ctrl[1] == bytes([0x06, 0xAC]))
check_true('snapshot_restore', ctrl[-1] == bytes([0x06, 0x0C]))

# snapshot timeout: BUSY never clears
connection.set_register(0x01, 0x08)
try:
    sensor.snapshot(LTC4151Full.SENSE)
    check_true('snapshot_timeout', False)
except OSError:
    check_true('snapshot_timeout', True)
connection.set_register(0x01, 0x00)

# Page R/W disabled: block reads fall back to single-register reads
connection.set_register(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
sensor.set_page_rw(False)
check_true('page_off_read_all', close(sensor.read_all()['power'], 51.2 * 1.024))
sensor.set_page_rw(True)

# temperature_from_adin: 58.82 * (900/1200 - 0.1066)
check_true('temperature_from_adin', close(LTC4151Full.temperature_from_adin(900, 1200), 58.82 * (0.75 - 0.1066)))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
