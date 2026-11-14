"""MicroPython hardware test for the AD5252 — runs against real hardware.

Sets and reads back both wipers, checks the resistance conversions, the quick
commands, EEMEM store/restore/reset, user EEMEM and the stored tolerance.
Requires WP high; the user EEMEM byte 15 and the stored wipers are overwritten.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5252 import AD5252Minimal, AD5252Full
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

pot = AD5252Minimal(connection)
check_true('construct_minimal', isinstance(pot, AD5252Minimal))

pot.set_wiper(0, 100)
check_true('wiper0_roundtrip', pot.get_wiper(0) == 100)
pot.set_wiper(1, 200)
check_true('wiper1_roundtrip', pot.get_wiper(1) == 200)
check_true('wiper0_independent', pot.get_wiper(0) == 100)
pot.set_wiper(0, 300)
check_true('wiper_clamps_255', pot.get_wiper(0) == 255)
pot.set_resistance(0, 5000.0)
check_true('set_resistance', abs(pot.get_resistance(0) - 5000.0) < 50.0)

full = AD5252Full(connection)
full.set_both(10, 250)
check_true('set_both', full.get_wiper(0) == 10 and full.get_wiper(1) == 250)
full.set_ratio(0, 0.5)
check_true('ratio_half', full.get_wiper(0) == 128 and abs(full.get_ratio(0) - 0.5) < 0.01)
full.set_resistance_wa(1, 3000.0)
check_true('set_resistance_wa', abs(full.get_resistance_wa(1) - 3000.0) < 50.0)
full.set_wiper(0, 100)
full.increment(0)
check_true('increment', full.get_wiper(0) == 101)
full.decrement(0)
check_true('decrement', full.get_wiper(0) == 100)
full.double(0)
check_true('double', full.get_wiper(0) == 200)
full.halve(0)
check_true('halve', full.get_wiper(0) == 100)
full.set_both(10, 20)
full.increment()
check_true('increment_both', full.get_wiper(0) == 11 and full.get_wiper(1) == 21)

full.set_both(40, 60)
full.store()
full.set_both(1, 2)
full.restore()
check_true('store_restore', full.get_wiper(0) == 40 and full.get_wiper(1) == 60)
full.set_both(1, 2)
full.reset()
check_true('reset_restores_eemem', full.get_wiper(0) == 40 and full.get_wiper(1) == 60)

full.write_eemem(15, 0xA5)
check_true('eemem_roundtrip', full.read_eemem(15) == 0xA5)
tol = full.read_tolerance(0)
check_true('tolerance_range', -50.0 < tol < 50.0)
check_true('rab_actual', abs(full.rab_actual(0) - 10000.0 * (1.0 + tol / 100.0)) < 0.01)
full.nop()

print('===DONE: %d passed, %d failed===' % (passed, failed))
