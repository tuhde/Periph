"""Linux hardware test for the AD5243 — runs against real hardware.

Sets and reads back both wipers, checks the resistance conversions, increment,
shutdown/resume (the wiper keeps its value) and reset to midscale.
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.ad5243 import AD5243Minimal, AD5243Full, I2C_ADDRESS

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


I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', hex(I2C_ADDRESS)), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

pot = AD5243Minimal(connection)
check_true('construct_minimal', isinstance(pot, AD5243Minimal))

pot.set_wiper(0, 100)
check_true('wiper0_roundtrip', pot.get_wiper(0) == 100)
pot.set_wiper(1, 200)
check_true('wiper1_roundtrip', pot.get_wiper(1) == 200)
check_true('wiper0_independent', pot.get_wiper(0) == 100)
pot.set_wiper(0, 300)
check_true('wiper_clamps_255', pot.get_wiper(0) == 255)
pot.set_resistance(0, 5000.0)
check_true('set_resistance', abs(pot.get_resistance(0) - 5000.0) < 50.0)

full = AD5243Full(connection)
full.set_both(10, 250)
check_true('set_both', full.get_wiper(0) == 10 and full.get_wiper(1) == 250)
full.set_ratio(0, 0.5)
check_true('ratio_half', full.get_wiper(0) == 128 and abs(full.get_ratio(0) - 0.5) < 0.01)
full.set_resistance_wa(1, 3000.0)
check_true('set_resistance_wa', abs(full.get_resistance_wa(1) - 3000.0) < 50.0)
full.set_wiper(0, 100)
check_true('increment', full.increment(0, 5) == 105 and full.get_wiper(0) == 105)
check_true('increment_clamps', full.increment(0, -500) == 0)

full.set_wiper(1, 77)
full.shutdown(1)
check_true('is_shutdown', full.is_shutdown(1))
check_true('shutdown_keeps_rdac', full.get_wiper(1) == 77)
full.resume(1)
check_true('resume', not full.is_shutdown(1) and full.get_wiper(1) == 77)

full.set_both(1, 2)
full.reset()
check_true('reset_midscale', full.get_wiper(0) == 128 and full.get_wiper(1) == 128)

print('===DONE: %d passed, %d failed===' % (passed, failed))
