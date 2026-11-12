"""MicroPython hardware test for the DS1881 — runs against real hardware.

Sets and reads back both wipers, checks the attenuation conversions, stereo
writes, Option 2, zero-crossing and the configuration read-back. Does not
write EEPROM (volatile mode throughout).
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds1881 import DS1881Minimal, DS1881Full
from machine import Pin

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


i2c = machine.I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

pot = DS1881Minimal(connection)
pot.init()
check_true('construct_minimal', isinstance(pot, DS1881Minimal))

pot.set_attenuation(0, 20.0)
pot.set_attenuation(1, 33.0)
check_true('position_roundtrip', pot.get_position(0) == 20 and pot.get_position(1) == 33)
check_true('attenuation_roundtrip', pot.get_attenuation(0) == 20.0 and pot.get_attenuation(1) == 33.0)
pot.mute(0)
check_true('mute', pot.get_position(0) == 63 and pot.get_attenuation(0) == 80.0)
pot.set_position(1, 5)
check_true('set_position', pot.get_position(1) == 5)

full = DS1881Full(connection)
full.init()
cfg = full.get_config()
check_true('config_default', cfg['option'] == 1 and cfg['zero_crossing'] and not cfg['nonvolatile'])
full.set_both(10, 40)
check_true('set_both', full.read_all()[:2] == (10, 40))
full.set_attenuation_both(15.0)
check_true('set_attenuation_both', full.read_all()[:2] == (15, 15))

full.set_zero_crossing(False)
check_true('zero_crossing_off', not full.get_config()['zero_crossing'])
full.set_zero_crossing(True)

full.set_option(2)
check_true('option2', full.get_config()['option'] == 2 and full.max_position() == 32)
full.set_both(13, 33)
check_true('option2_positions', full.read_all()[:2] == (13, 33) and full.position_to_db(13) == 14.0)
full.set_option(1)
check_true('option1_again', full.get_config()['option'] == 1)

full.reset()
check_true('reset_mutes', full.read_all()[:2] == (63, 63))

print('===DONE: %d passed, %d failed===' % (passed, failed))
