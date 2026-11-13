"""Linux hardware test for the PCA9541 — runs against real hardware.

Takes and releases the downstream bus, checks connect / disconnect, the
interrupt mask and the INT functional test. Run it with the downstream bus
free: it switches the bus between this master and the other one.
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.other.pca9541 import PCA9541Minimal, PCA9541Full, I2C_ADDRESS

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

selector = PCA9541Minimal(connection)
check_true('construct_minimal', isinstance(selector, PCA9541Minimal))
check_true('has_control_is_bool', selector.has_control() in (True, False))

full = PCA9541Full(connection)
control = full.get_control()
check_true('status_matches_control', full.status()['has_control'] == (((control & 1) != 0) == ((control & 2) != 0)))
ie, c2, istat = full.read_all()
check_true('read_all_three_bytes', 0 <= ie <= 0x0F and 0 <= c2 <= 0xFF and 0 <= istat <= 0xFF)
full.set_interrupt_mask(bus_not_idle=True)
check_true('interrupt_mask_roundtrip', full.get_interrupt_mask() == 0x04)
full.set_interrupt_mask()
check_true('interrupt_mask_cleared', full.get_interrupt_mask() == 0x00)
full.test_interrupt(own=True)
check_true('test_interrupt_sets_teston', full.status()['own_test'] is True)
full.test_interrupt()
check_true('test_interrupt_clears', full.status()['own_test'] is False)
check_true('acquire_takes_bus', full.acquire() is True and full.has_control() is True)
full.disconnect()
check_true('disconnect', full.is_connected() is False)
full.connect()
check_true('connect', full.is_connected() is True)
full.release()
check_true('release_gives_bus_away', full.has_control() is False)
full.acquire()
check_true('reacquire', full.has_control() is True)

print('===DONE: %d passed, %d failed===' % (passed, failed))
