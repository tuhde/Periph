"""Linux hardware test for the DS3502 — runs against real hardware.

Sets and reads back the wiper, checks the voltage / ratio / resistance
conversions, increment and the MODE bit (SRAM-only writes after init).
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.adc_dac.ds3502 import DS3502Minimal, DS3502Full, I2C_ADDRESS

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

pot = DS3502Minimal(connection, v_high=15.0, v_low=0.0)
check_true('construct_minimal', isinstance(pot, DS3502Minimal))

pot.set_wiper(100)
check_true('wiper_roundtrip', pot.get_wiper() == 100)
pot.set_wiper(300)
check_true('wiper_clamps_127', pot.get_wiper() == 127)
pot.set_voltage(6.0)
check_true('set_voltage', abs(pot.get_voltage() - 6.0) < 0.1)

full = DS3502Full(connection, v_high=15.0, v_low=0.0)
check_true('init_mode_sram', full.get_mode() is False)
full.set_ratio(0.5)
check_true('ratio_half', full.get_wiper() == 64 and abs(full.get_ratio() - 0.5) < 0.01)
full.set_wiper(100)
check_true('resistance_nominal', abs(full.get_resistance('L') + full.get_resistance('H') - 10000.0) < 1.0)
check_true('increment', full.increment(5) == 105 and full.get_wiper() == 105)
check_true('increment_clamps', full.increment(-500) == 0)
full.set_mode(True)
check_true('set_mode_eeprom', full.get_mode() is True)
full.set_mode(False)
check_true('set_mode_sram', full.get_mode() is False)
full.wait_ready()
check_true('wait_ready', True)
# store_wiper is not exercised: it consumes one of the EEPROM's limited write cycles.

print('===DONE: %d passed, %d failed===' % (passed, failed))
