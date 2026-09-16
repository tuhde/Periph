"""ADE7953 hardware-in-loop test for Linux (smbus2)."""

import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.power.ade7953 import ADE7953Full, ADE7953Source

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x38'), 16)

VOLTAGE_GAIN = 251.0
CURRENT_GAIN = 30.0

connection = I2CConnection(I2C_BUS, I2C_ADDR)
try:
    ade = ADE7953Full(connection, VOLTAGE_GAIN, CURRENT_GAIN)

    time.sleep(0.2)

    check_true('voltage non-negative', ade.voltage() >= 0.0)
    check_true('current non-negative', ade.current() >= 0.0)
    check_true('active_power finite', ade.active_power() > -1000000.0)
    check_true('active_energy finite', ade.active_energy() > -1000.0)

    ade.enable_interrupt(ADE7953Source.ZXV)
    check_true('IRQSTATA readable', ade.interrupt_status('a') >= 0)
    ade.clear_interrupts('a')
    ade.disable_interrupt(ADE7953Source.ZXV)

    ade.configure_no_load(active=0x1234)
    ade.disable_no_load(active=True)

    ade.reset()
    check_true('voltage after reset', ade.voltage() >= 0.0)

    print('===DONE: {} passed, {} failed==='.format(passed, failed))
finally:
    connection.close()