import time
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.power.ina3221 import INA3221Full

import busio

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
ina = INA3221Full(transport)

check_eq('manufacturer_id', ina.manufacturer_id(), 0x5449)
check_eq('die_id',          ina.die_id(),          0x3220)

for ch in (1, 2, 3):
    check_true('ch{} voltage non-negative'.format(ch), ina.voltage(ch) >= 0.0)
    check_true('ch{} shunt_voltage finite'.format(ch),  abs(ina.shunt_voltage(ch)) < 1.0)
    check_true('ch{} current finite'.format(ch),        abs(ina.current(ch)) < 100.0)
    check_true('ch{} power non-negative'.format(ch),   ina.power(ch) >= 0.0)

check_true('conversion_ready', ina.conversion_ready())

ina.configure(avg=3, vbus_ct=4, vsh_ct=4, mode=7)
check_eq('configure: mfr_id still valid', ina.manufacturer_id(), 0x5449)

ina.set_critical_alert(1, 0.1)
ina.set_warning_alert(2, 0.05)
flags = ina.alert_flags()
check_true('alert_flags readable', flags >= 0)

ina.enable_channel(1, False)
check_true('channel 1 disabled', not ina.channel_enabled(1))
ina.enable_channel(1, True)
check_true('channel 1 re-enabled', ina.channel_enabled(1))

ina.set_summation_channels([1, 2], 0.2)
sv_sum = ina.summation_value()
check_true('summation_value finite', abs(sv_sum) < 10.0)

ina.set_power_valid_limits(5.5, 4.5)
check_true('power_valid readable', isinstance(ina.power_valid(), bool))

ina.shutdown()
time.sleep(0.001)
ina.wake()
check_true('wake: voltage non-negative', ina.voltage(1) >= 0.0)

ina.reset()
check_eq('reset: mfr_id still valid', ina.manufacturer_id(), 0x5449)

print('===DONE: {} passed, {} failed==='.format(passed, failed))