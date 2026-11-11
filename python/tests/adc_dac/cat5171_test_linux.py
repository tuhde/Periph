"""Linux hardware test for the CAT5171 — runs against real hardware.

Sets and reads back the wiper, checks the resistance conversions, increment,
shutdown/resume (the wiper keeps its value) and reset to midscale.
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.cat5171 import CAT5171Minimal, CAT5171Full, I2C_ADDRESS

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

pot = CAT5171Minimal(connection)
check_true('construct_minimal', isinstance(pot, CAT5171Minimal))

pot.set_wiper(100)
check_true('wiper_roundtrip', pot.get_wiper() == 100)
pot.set_wiper(300)
check_true('wiper_clamps_255', pot.get_wiper() == 255)
pot.set_resistance(50000.0)
check_true('set_resistance', abs(pot.get_resistance() - 50000.0) < 500.0)

full = CAT5171Full(connection)
full.set_ratio(0.5)
check_true('ratio_half', full.get_wiper() == 128 and abs(full.get_ratio() - 0.5) < 0.01)
full.set_resistance_wa(30000.0)
check_true('set_resistance_wa', abs(full.get_resistance_wa() - 30000.0) < 500.0)
full.set_wiper(100)
check_true('increment', full.increment(5) == 105 and full.get_wiper() == 105)
check_true('increment_clamps', full.increment(-500) == 0)

full.set_wiper(77)
full.shutdown()
check_true('is_shutdown', full.is_shutdown())
check_true('shutdown_keeps_wcr', full.get_wiper() == 77)
full.resume()
check_true('resume', not full.is_shutdown() and full.get_wiper() == 77)

full.set_wiper(10)
full.midscale()
check_true('midscale', full.get_wiper() == 128)

full.set_wiper(1)
full.reset()
check_true('reset_midscale', full.get_wiper() == 128)

print('===DONE: %d passed, %d failed===' % (passed, failed))
