"""ADE7953 hardware-in-loop test for MicroPython."""

import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ade7953 import ADE7953Full, ADE7953Source, ADE7953CFSource

from machine import I2C, Pin

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


VOLTAGE_GAIN = 251.0
CURRENT_GAIN = 30.0

i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
ade = ADE7953Full(connection, VOLTAGE_GAIN, CURRENT_GAIN)

# The mandatory 0xFE/0x120 power-up register setting takes 110 ms inside
# __init__, so give the chip a brief settle before reading.
time.sleep_ms(50)

check_true('voltage non-negative', ade.voltage() >= 0.0)
check_true('current non-negative', ade.current() >= 0.0)
check_true('active_power finite', ade.active_power() > -1000000.0)
check_true('active_energy finite', ade.active_energy() > -1000.0)

# Channel B + reactive/apparent (Full only).
ade.configure_channel_b(CURRENT_GAIN)
check_true('current_b non-negative', ade.current_b() >= 0.0)
check_true('reactive_power finite',  ade.reactive_power() > -1000000.0)
check_true('apparent_power finite',  ade.apparent_power() > -1000000.0)

# Power factor / angle / line frequency.
pf = ade.power_factor()
check_true('power_factor in range', -1.05 <= pf <= 1.05)
check_true('line_period non-negative', ade.line_period() >= 0.0)

# Interrupts — enable a few sources and confirm the status register has
# at least the always-on Reset bit (20).
ade.enable_interrupt(ADE7953Source.ZXV)
ade.enable_interrupt(ADE7953Source.OIA)
status_a = ade.interrupt_status('a')
check_true('IRQSTATA readable', status_a >= 0)
check_true('Reset bit set in IRQSTATA', (status_a & (1 << 20)) != 0)
ade.clear_interrupts('a')
ade.disable_interrupt(ADE7953Source.ZXV)
ade.disable_interrupt(ADE7953Source.OIA)

# No-load configuration round-trip.
ade.configure_no_load(active=0x1234, reactive=0x5678, apparent=0x9ABC)
nl = ade.no_load_status()
check_true('no_load_status readable', nl is not None)
ade.disable_no_load(active=True, reactive=True, apparent=True)

# CF configuration.
ade.configure_cf(1, ADE7953CFSource.ACTIVE_A, 0x3F)
ade.disable_cf(1)

# Reset (re-runs the mandatory power-up sequence).
ade.reset()
check_true('voltage after reset', ade.voltage() >= 0.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))